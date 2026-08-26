package com.cadentic.app.domain

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

data class Phase(val name: String, val weeksLabel: String, val weeks: Int)

// Server-generated in production; stubbed locally by ProposalEngine.
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

enum class Status { DRAFT, GENERATING, PROPOSED, APPROVED }

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
    val constraints: Constraints,
    val proposal: Proposal? = null,
    val status: Status = Status.DRAFT,
) {
    /** Clamped to what actually exists — a single priority can only ever be a single focus. */
    val effectiveFocusCount: Int
        get() = focusCount.coerceIn(1, maxOf(1, minOf(MAX_FOCUS_COUNT, priorities.size)))
    val focus get() = priorities.take(effectiveFocusCount)
    val queued get() = priorities.drop(effectiveFocusCount)
}
