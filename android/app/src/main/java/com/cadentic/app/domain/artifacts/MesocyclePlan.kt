@file:UseSerializers(InstantSerializer::class, LocalDateSerializer::class, DayOfWeekSerializer::class)

package com.cadentic.app.domain.artifacts

import com.cadentic.app.domain.Category
import com.cadentic.app.domain.Lane
import com.cadentic.app.domain.Strain
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate

/**
 * The **Mesocycle Plan** artifact (PRD §5.2 row 5, Epic 2 story 4) — the locked cycle the
 * daily layer and the tracker read.
 *
 * Its shape is not defined here. `contracts/mesocycle-api.schema.json` is the contract, and
 * these classes are its Kotlin face; `ContractSchemaTest` asserts they still agree, so a
 * field added on one side is a failing test rather than a runtime surprise. The one thing
 * that is local is [updatedAt]: the backend returns a plan, the app stamps when it persisted
 * it, exactly as every other artifact works.
 *
 * What is deliberately *not* here:
 *
 * - **Exercises**, at any level. The mesocycle prescribes structure; the daily layer, which
 *   knows about equipment and facilities, prescribes movement (PRD §5.1).
 * - **A headline or coach's note.** Plan surfaces never render model free text (PRD §8), so
 *   those are composed from these structured facts by [com.cadentic.app.domain.PlanNarrative]
 *   and are the same words every time for the same plan.
 * - **Game and practice days.** They live in the Blocker Calendar and the GUI overlays them.
 *   A day here is what the athlete is being asked to *train*.
 */
const val MESOCYCLE_PLAN_SCHEMA_VERSION: Int = 1

/** The role a phase plays. The UI switches on this; [MesocyclePhase.name] is display text. */
@Serializable
enum class PhaseType { BASE, BUILD, PEAK, DELOAD }

/** What a planned day is for. Pinned to PRD §5.1 — and there is no "game" among them. */
@Serializable
enum class DayType { STRENGTH, ENDURANCE, MOBILITY, RECOVERY, REST }

/**
 * Reuses the [Strain] vocabulary so the product speaks one intensity language: a HARD day and
 * a HARD blocker mean the same kind of thing to an athlete reading the calendar.
 */
typealias Intensity = Strain

/** Which mode produced a plan. Persisted so a plan can always be traced to its billing path. */
@Serializable
enum class GenerationMode {
    @SerialName("max-plan-oauth") MAX_PLAN_OAUTH,
    @SerialName("user-api-key") USER_API_KEY,
}

@Serializable
data class GeneratedBy(
    val mode: GenerationMode,
    val model: String,
    /** Which version of the standard prompt produced this plan. */
    val promptVersion: Int,
)

@Serializable
data class MesocyclePhase(
    val phaseType: PhaseType,
    /** Display text only — never switched on. */
    val name: String,
    val weeks: Int,
)

@Serializable
data class PlannedDay(
    val day: DayOfWeek,
    val type: DayType,
    /** `null` on a REST day and on no other day type. */
    val intensity: Intensity? = null,
)

@Serializable
data class PlannedWeek(
    /** 1-based, contiguous across the cycle. */
    val week: Int,
    /** All seven days, Monday→Sunday. A day off is REST, never an omitted entry. */
    val days: List<PlannedDay>,
)

@Serializable
data class Progression(
    val intraWeek: String,
    val interWeek: String,
)

/**
 * The response body and, with [updatedAt] added, `mesocycle-plan.json`.
 *
 * [lane], [focus] and [queued] are stamped by the backend from the request payload — the
 * Goals artifact stays the single source of truth for priorities, and nothing the model says
 * about them is trusted.
 */
@Serializable
data class MesocyclePlanArtifact(
    val schemaVersion: Int = MESOCYCLE_PLAN_SCHEMA_VERSION,
    /**
     * Absent on the wire (the backend returns a plan, not an artifact) and always present on
     * disk. The repository stamps it on write like every other artifact.
     */
    val updatedAt: Instant? = null,
    val generatedBy: GeneratedBy,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val durationWeeks: Int,
    val sessionsPerWeek: Int,
    val lane: Lane,
    val focus: List<Category>,
    val queued: List<Category>,
    val phases: List<MesocyclePhase>,
    val weeklyStructure: List<PlannedWeek>,
    val progression: Progression,
) {
    /** Training days in the typical week — the same modal rule the backend validates against. */
    val trainingDaysPerWeek: Int get() = sessionsPerWeek

    fun deloadWeeks(): List<Int> {
        var week = 1
        val out = mutableListOf<Int>()
        for (phase in phases) {
            if (phase.phaseType == PhaseType.DELOAD) out += (week until week + phase.weeks)
            week += phase.weeks
        }
        return out
    }
}
