package com.cadentic.app.domain

import com.cadentic.app.domain.artifacts.PhaseType
import java.time.DayOfWeek
import java.time.LocalDate

// Categories self-assessed in step 1 ("Cardio") surface as goal priorities in step 2 ("Endurance").
enum class Category(val assessmentLabel: String, val priorityTitle: String, val prioritySubtitle: String) {
    CARDIO("Cardio", "Endurance", "aerobic engine"),
    STRENGTH("Strength", "Strength", "base numbers hold"),
    EXPLOSIVENESS("Explosiveness", "Explosiveness", "vertical — the dunk"),
    HYPERTROPHY("Hypertrophy", "Hypertrophy", "muscle where it earns its keep"),
}

enum class Rating(val label: String) { LOW("Low"), MID("Mid"), HIGH("High") }

// "Rate or skip": rating stays null when skipped. dontCare drops the category as a goal.
data class SelfAssessment(val rating: Rating? = null, val dontCare: Boolean = false)

enum class Lane(val title: String, val subtitle: String) {
    LONGEVITY("Longevity first", "no red-zone weeks — adaptation compounds"),
    PERFORMANCE("Pure performance", "maximal output, accepts wear"),
}

enum class Strain(val label: String) { LIGHT("Light"), MEDIUM("Medium"), HARD("Hard") }

enum class Sex(val label: String) { MALE("Male"), FEMALE("Female") }

data class Profile(
    val age: String = "27",
    val sex: Sex = Sex.MALE,
    val heightCm: String = "191",
    val weightKg: String = "88",
    val experience: String = "Advanced — 5–10 years",
    val assessment: Map<Category, SelfAssessment> = mapOf(
        Category.CARDIO to SelfAssessment(Rating.MID),
        Category.STRENGTH to SelfAssessment(Rating.MID),
        Category.EXPLOSIVENESS to SelfAssessment(Rating.LOW),
        Category.HYPERTROPHY to SelfAssessment(dontCare = true),
    ),
)

/**
 * Every constraint carries a stable [id]. Two blockers can legitimately look identical
 * (same day, same label, same strain), so identity must never be structural — editing or
 * deleting one must not touch its twin.
 */
data class RecurringBlocker(
    val id: Long,
    val label: String,
    val days: Set<DayOfWeek>,
    val timeRange: String,
    val strain: Strain,
) {
    val daysDisplay: String
        get() = days.sorted().joinToString(" & ") {
            it.name.lowercase().replaceFirstChar(Char::uppercase).take(3)
        }
}

data class OneOffBlocker(
    val id: Long,
    val date: LocalDate,
    val label: String,
    val strain: Strain,
)

/**
 * Two kinds, and only two: something that repeats weekly, and something that lands on a
 * single date. League games are the latter — they were once a third kind fed by a schedule
 * import, but with no import to be authoritative about their dates there was nothing left
 * to distinguish them from any other one-off the athlete owns.
 *
 * Location/climate deliberately absent: athletes manage heat and conditions themselves.
 */
data class Constraints(
    val recurring: List<RecurringBlocker>,
    val oneOffs: List<OneOffBlocker> = emptyList(),
)

/**
 * A phase as the timeline draws it. [phaseType] is what the UI switches on — [name] is the
 * engine's display text and may be anything, so colouring or dashing by it would break the
 * moment a plan came back saying "Foundation" instead of "Base".
 */
data class Phase(val phaseType: PhaseType, val name: String, val weeksLabel: String, val weeks: Int)

/**
 * What the proposal screen renders: a view over the generated Mesocycle Plan, with
 * `laneLabel`, `weeksLabel`, `headline` and `coachNote` composed on the client
 * ([com.cadentic.app.domain.PlanNarrative]) rather than persisted or sent by the engine.
 */
data class Proposal(
    val startDate: LocalDate,
    val endDate: LocalDate,
    val sessionsPerWeek: Int,
    val lane: Lane,
    val laneLabel: String,
    val headline: String,
    val phases: List<Phase>,
    val coachNote: String,
    // Locked at approval: the focus pair for this cycle, and what waits for the next one.
    val focusThisCycle: List<Category>,
    val queuedForLater: List<Category>,
)

/**
 * [FAILED] arrived with Epic 2: generation is a network call now, and a call that fails needs
 * somewhere to land other than a silent slide back to DRAFT with no explanation.
 */
enum class Status { DRAFT, GENERATING, PROPOSED, FAILED, APPROVED }

// Never more than two priorities per cycle; one is a valid — and sharper — choice.
const val MAX_FOCUS_COUNT = 2
const val DEFAULT_FOCUS_COUNT = 2

data class OnboardingDraft(
    val profile: Profile = Profile(),
    // Ordered category ids, excluding dont_care. Indices < focusCount are active this cycle;
    // the rest queue for later cycles — priorities never change *within* a mesocycle.
    val priorities: List<Category> = listOf(Category.CARDIO, Category.EXPLOSIVENESS, Category.STRENGTH),
    // How many of them get programmed now. The athlete may narrow this to a single focus.
    val focusCount: Int = DEFAULT_FOCUS_COUNT,
    val lane: Lane = Lane.LONGEVITY,
    val injuries: List<String> = listOf("Lower-back disc (L4/L5)", "Right ankle instability"),
    /**
     * Optional athlete ceiling (owner decision, 2026-08-30): the most days per week that may
     * carry effort of any kind — planned training AND commitments like games or practices,
     * counted as distinct days. The coach prescribes the frequency; this only caps it.
     * null = no cap, which is the default and stays one tap away from being the answer.
     */
    val maxWeeklyDays: Int? = null,
    val constraints: Constraints,
    /** The generated cycle, once the engine has returned one. Persisted only at approval. */
    val plan: com.cadentic.app.domain.artifacts.MesocyclePlanArtifact? = null,
    val status: Status = Status.DRAFT,
    /** Set with [Status.FAILED] and cleared on every new attempt. Why the last one failed. */
    val generationError: EngineError? = null,
) {
    /**
     * Derived, never stored: the proposal screen's view of [plan]. Keeping it computed means
     * the two can't disagree — there is no second copy to forget to update.
     */
    val proposal: Proposal? get() = plan?.toProposal(constraints)

    /** Clamped to what actually exists — a single priority can only ever be a single focus. */
    val effectiveFocusCount: Int
        get() = focusCount.coerceIn(1, maxOf(1, minOf(MAX_FOCUS_COUNT, priorities.size)))
    val focus get() = priorities.take(effectiveFocusCount)
    val queued get() = priorities.drop(effectiveFocusCount)
}
