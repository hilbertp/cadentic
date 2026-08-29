@file:UseSerializers(LocalDateSerializer::class, DayOfWeekSerializer::class)

package com.cadentic.app.domain.artifacts

import com.cadentic.app.domain.Category
import com.cadentic.app.domain.Lane
import com.cadentic.app.domain.MAX_FOCUS_COUNT
import com.cadentic.app.domain.ProfileRules
import com.cadentic.app.domain.Rating
import com.cadentic.app.domain.Sex
import com.cadentic.app.domain.Strain
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * The exact input the Mesocycle Engine will send to the LLM: Athlete Profile + Goals +
 * Status + Blocker Calendar (PRD §5.3), composed from persisted artifacts alone.
 *
 * **Shape (Epic 1 open point 4): nested per-artifact sections.** A flat object would lose
 * the business decomposition the PRD is built on and make it ambiguous which store a field
 * must be written back to after the Post-Mesocycle Review. Per-artifact `schemaVersion` and
 * `updatedAt` are dropped — the payload carries its own version — and **blocker ids are
 * stripped**: they are local storage identifiers with no meaning to a planner, and passing
 * them would invite the LLM to reference handles that mean nothing on its side.
 *
 * The calendar has two kinds and only two: `recurring` (weekly) and `oneOffs` (a single
 * date). League games are one-offs — v1 modelled them separately, on the assumption of a
 * schedule import that would own their dates; there is no such import, so there was nothing
 * left to distinguish them.
 *
 * Cycle N>1 needs no extra input — the Post-Mesocycle Review updates Status and Goals in
 * place (PRD §14), so a later assembly is the same read of the same four artifacts.
 */
const val MESO_REQUEST_SCHEMA_VERSION: Int = 2

@Serializable
data class MesoRequestPayload(
    val schemaVersion: Int = MESO_REQUEST_SCHEMA_VERSION,
    /**
     * The temporal anchor, injected at composition time. Not optional: the cycle is
     * calendar-anchored (PRD §14) and one-off blockers carry absolute dates, so phases and
     * deloads cannot be laid against a September game without knowing when the cycle starts.
     */
    val requestDate: LocalDate,
    val profile: ProfileSection,
    val goals: GoalsSection,
    val status: StatusSection,
    val blockerCalendar: CalendarSection,
) {
    @Serializable
    data class ProfileSection(val age: Int, val sex: Sex, val heightCm: Int, val weightKg: Double)

    @Serializable
    data class GoalsSection(
        val lane: Lane,
        val priorities: List<Category>,
        val focusCount: Int,
        val focusThisCycle: List<Category>,
        val queuedForLater: List<Category>,
        val excluded: List<Category>,
        /** Optional athlete ceiling on occupied days per week, commitments included. */
        val maxWeeklyDays: Int? = null,
    )

    @Serializable
    data class StatusSection(
        val experience: String,
        val selfAssessment: Map<Category, Rating?>,
        val injuries: List<String>,
    )

    @Serializable
    data class CalendarSection(
        val recurring: List<Recurring>,
        val oneOffs: List<Dated>,
    ) {
        @Serializable
        data class Recurring(
            val label: String,
            val days: List<DayOfWeek>,
            /** Opaque free text — do not parse. */
            val timeRange: String,
            val strain: Strain,
        )

        @Serializable
        data class Dated(val date: LocalDate, val label: String, val strain: Strain)
    }
}

/** Assembly either produces a payload or names everything that stopped it. Never a partial. */
sealed interface MesoRequestResult {
    data class Ok(val payload: MesoRequestPayload, val json: JsonObject) : MesoRequestResult
    data class Invalid(val errors: List<ArtifactError>) : MesoRequestResult {
        val message: String get() = errors.joinToString("; ") { it.message }
    }
}

/**
 * Reads artifacts only — no UI state, no ViewModel. This is the call the Mesocycle Engine
 * will make in the next epic, and it works with the app freshly launched and no onboarding
 * screen ever shown.
 */
class MesoRequestAssembler(private val repository: ArtifactRepository) {

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json { prettyPrint = true; prettyPrintIndent = "  "; encodeDefaults = true; explicitNulls = true }

    fun assemble(requestDate: LocalDate): MesoRequestResult {
        val errors = mutableListOf<ArtifactError>()

        // A read that throws (corrupt file, newer schemaVersion) is a validation failure too:
        // the caller gets one named error rather than an exception mid-assembly.
        val profile = readOrNull(ArtifactId.ATHLETE_PROFILE, errors) { repository.readProfile() }
        val goals = readOrNull(ArtifactId.ATHLETE_GOALS, errors) { repository.readGoals() }
        val status = readOrNull(ArtifactId.ATHLETE_STATUS, errors) { repository.readStatus() }
        val calendar = readOrNull(ArtifactId.BLOCKER_CALENDAR, errors) { repository.readBlockerCalendar() }

        profile?.let { errors += it.validate() }
        goals?.let { errors += it.validate() }
        status?.let { errors += it.validate() }
        calendar?.let { errors += it.validate() }

        if (errors.isNotEmpty() || profile == null || goals == null || status == null || calendar == null) {
            return MesoRequestResult.Invalid(errors)
        }

        val payload = MesoRequestPayload(
            requestDate = requestDate,
            profile = MesoRequestPayload.ProfileSection(
                age = profile.age, sex = profile.sex, heightCm = profile.heightCm, weightKg = profile.weightKg,
            ),
            goals = MesoRequestPayload.GoalsSection(
                lane = goals.lane,
                priorities = goals.priorities,
                focusCount = goals.focusCount,
                focusThisCycle = goals.priorities.take(goals.focusCount),
                queuedForLater = goals.priorities.drop(goals.focusCount),
                excluded = goals.excluded,
                maxWeeklyDays = goals.maxWeeklyDays,
            ),
            status = MesoRequestPayload.StatusSection(
                experience = status.experience,
                selfAssessment = status.selfAssessment,
                injuries = status.injuries,
            ),
            blockerCalendar = MesoRequestPayload.CalendarSection(
                recurring = calendar.recurring.map {
                    MesoRequestPayload.CalendarSection.Recurring(it.label, it.days, it.timeRange, it.strain)
                },
                oneOffs = calendar.oneOffs.map {
                    MesoRequestPayload.CalendarSection.Dated(it.date, it.label, it.strain)
                },
            ),
        )

        val encoded = json.encodeToJsonElement(MesoRequestPayload.serializer(), payload) as JsonObject
        // Contract backstop on the serialized form: the engine consumes JSON, not the Kotlin
        // object, so the keys it depends on — requestDate above all — are checked as keys.
        val absent = REQUIRED_KEYS.filterNot { encoded.containsKey(it) }
        if (absent.isNotEmpty()) {
            return MesoRequestResult.Invalid(
                absent.map { ArtifactError.MissingField(ArtifactId.ATHLETE_PROFILE, "meso-request.$it") },
            )
        }
        return MesoRequestResult.Ok(payload, encoded)
    }

    fun assembleJsonOrThrow(requestDate: LocalDate): String = when (val r = assemble(requestDate)) {
        is MesoRequestResult.Ok -> json.encodeToString(MesoRequestPayload.serializer(), r.payload)
        is MesoRequestResult.Invalid -> throw ArtifactException(r.errors.first())
    }

    private fun <T> readOrNull(
        id: ArtifactId,
        errors: MutableList<ArtifactError>,
        read: () -> T?,
    ): T? = try {
        read() ?: null.also { errors += ArtifactError.Missing(id) }
    } catch (e: ArtifactException) {
        errors += e.error
        null
    }

    private companion object {
        val REQUIRED_KEYS = listOf("schemaVersion", "requestDate", "profile", "goals", "status", "blockerCalendar")
    }
}

// --- Per-artifact validation ---------------------------------------------------------

private fun AthleteProfileArtifact.validate(): List<ArtifactError> {
    val id = ArtifactId.ATHLETE_PROFILE
    return buildList {
        if (age !in ProfileRules.AGE) add(ArtifactError.InvalidField(id, "age", "$age is not ${ProfileRules.AGE}"))
        if (heightCm !in ProfileRules.HEIGHT_CM) {
            add(ArtifactError.InvalidField(id, "heightCm", "$heightCm is not ${ProfileRules.HEIGHT_CM}"))
        }
        if (weightKg < ProfileRules.WEIGHT_KG.first || weightKg > ProfileRules.WEIGHT_KG.last) {
            add(ArtifactError.InvalidField(id, "weightKg", "$weightKg is not ${ProfileRules.WEIGHT_KG}"))
        }
    }
}

private fun AthleteGoalsArtifact.validate(): List<ArtifactError> {
    val id = ArtifactId.ATHLETE_GOALS
    return buildList {
        if (priorities.isEmpty()) {
            add(ArtifactError.MissingField(id, "priorities"))
        } else {
            if (priorities.distinct().size != priorities.size) {
                add(ArtifactError.InvalidField(id, "priorities", "contains duplicates"))
            }
            priorities.firstOrNull { it in excluded }?.let {
                add(ArtifactError.InvalidField(id, "priorities", "$it is also excluded"))
            }
            // The rule the whole cycle rests on: at most two priorities are programmed at
            // once, and never more than actually exist (PRD §5.1).
            val ceiling = minOf(MAX_FOCUS_COUNT, priorities.size)
            if (focusCount !in 1..ceiling) {
                add(ArtifactError.InvalidField(id, "focusCount", "$focusCount is not 1..$ceiling"))
            }
        }
        maxWeeklyDays?.let {
            if (it !in 1..7) add(ArtifactError.InvalidField(id, "maxWeeklyDays", "$it is not 1..7"))
        }
    }
}

private fun AthleteStatusArtifact.validate(): List<ArtifactError> {
    val id = ArtifactId.ATHLETE_STATUS
    return buildList {
        if (experience.isBlank()) add(ArtifactError.MissingField(id, "experience"))
        // Every category must be present; its value may be null, which means "unknown".
        Category.entries.filterNot { selfAssessment.containsKey(it) }.forEach {
            add(ArtifactError.MissingField(id, "selfAssessment.$it"))
        }
        if (injuries.any { it.isBlank() }) {
            add(ArtifactError.InvalidField(id, "injuries", "contains a blank entry"))
        }
    }
}

private fun BlockerCalendarArtifact.validate(): List<ArtifactError> {
    val id = ArtifactId.BLOCKER_CALENDAR
    return buildList {
        // An empty calendar is legitimate — an athlete with nothing booked. Malformed
        // entries are not: a blocker with no days or no label cannot be planned around.
        recurring.forEach {
            if (it.label.isBlank()) add(ArtifactError.MissingField(id, "recurring[${it.id}].label"))
            if (it.days.isEmpty()) add(ArtifactError.MissingField(id, "recurring[${it.id}].days"))
        }
        oneOffs.forEach {
            if (it.label.isBlank()) add(ArtifactError.MissingField(id, "oneOffs[${it.id}].label"))
        }
        val ids = recurring.map { it.id } + oneOffs.map { it.id }
        if (ids.distinct().size != ids.size) {
            add(ArtifactError.InvalidField(id, "id", "blocker ids are not unique"))
        }
    }
}
