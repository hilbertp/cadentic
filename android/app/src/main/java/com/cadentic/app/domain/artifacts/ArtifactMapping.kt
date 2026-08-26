package com.cadentic.app.domain.artifacts

import com.cadentic.app.domain.Category
import com.cadentic.app.domain.Constraints
import com.cadentic.app.domain.Fixture
import com.cadentic.app.domain.Ids
import com.cadentic.app.domain.OnboardingDraft
import com.cadentic.app.domain.OneOffBlocker
import com.cadentic.app.domain.Profile
import com.cadentic.app.domain.ProfileRules
import com.cadentic.app.domain.RecurringBlocker
import com.cadentic.app.domain.SelfAssessment
import java.time.Instant

/**
 * The seam between the shipped UI model and the durable artifacts (Epic 1 open point 2:
 * **the Kotlin domain classes stay as they are and the split happens here**). `Profile`
 * holds UI state — strings the athlete is still typing, and the self-assessment the PRD
 * files under *Status* — so refactoring it to match the artifact boundary would have
 * churned all four screens to move one map. Mapping in one place costs less and keeps the
 * artifact schemas free to change without touching the UI.
 *
 * Everything crossing into an artifact is canonicalised here: strings become numbers,
 * day sets become sorted lists, blockers get a stable order, and `focusCount` is coerced
 * to its effective value — so an impossible state cannot be serialized.
 */

// --- Draft → artifacts ---------------------------------------------------------------

/**
 * @throws ArtifactException when a base-data field is not a number in range. The UI's
 * Continue button is gated on the same [ProfileRules], so this is a contract backstop,
 * not a path the athlete can walk into.
 */
fun OnboardingDraft.toProfileArtifact(now: Instant): AthleteProfileArtifact {
    val id = ArtifactId.ATHLETE_PROFILE
    val age = ProfileRules.age(profile.age)
        ?: ArtifactError.InvalidField(id, "age", "'${profile.age}' is not ${ProfileRules.AGE}").raise()
    val height = ProfileRules.heightCm(profile.heightCm)
        ?: ArtifactError.InvalidField(id, "heightCm", "'${profile.heightCm}' is not ${ProfileRules.HEIGHT_CM}").raise()
    val weight = ProfileRules.weightKg(profile.weightKg)
        ?: ArtifactError.InvalidField(id, "weightKg", "'${profile.weightKg}' is not ${ProfileRules.WEIGHT_KG}").raise()
    return AthleteProfileArtifact(
        updatedAt = now,
        age = age,
        sex = profile.sex,
        heightCm = height,
        weightKg = weight,
    )
}

/**
 * Ratings, experience and injuries — status facts, even though the code nests the first two
 * in `Profile`. A "don't care" category keeps whatever rating it was given: the exclusion is
 * a *goals* decision and lives there, so a category can come back next cycle with its
 * assessment intact.
 */
fun OnboardingDraft.toStatusArtifact(now: Instant): AthleteStatusArtifact =
    AthleteStatusArtifact(
        updatedAt = now,
        experience = profile.experience,
        selfAssessment = Category.entries.associateWith { profile.assessment[it]?.rating },
        injuries = injuries.toList(),
    )

/** [lock] is carried through untouched; only [ArtifactRepository.lockGoals] ever sets one. */
fun OnboardingDraft.toGoalsArtifact(now: Instant, lock: GoalsLock? = null): AthleteGoalsArtifact =
    AthleteGoalsArtifact(
        updatedAt = now,
        lane = lane,
        priorities = priorities.toList(),
        // The effective value, never the raw one: focusCount 2 with a single priority is an
        // impossible state and must not be expressible in an artifact or a payload.
        focusCount = effectiveFocusCount,
        excluded = Category.entries.filter { profile.assessment[it]?.dontCare == true },
        lockedForCycle = lock,
    )

fun Constraints.toArtifact(now: Instant): BlockerCalendarArtifact =
    BlockerCalendarArtifact(
        updatedAt = now,
        recurring = recurring
            .sortedBy { it.id }
            .map { RecurringBlockerRecord(it.id, it.label, it.days.sorted(), it.timeRange, it.strain) },
        fixtures = fixtures
            .sortedWith(compareBy({ it.date }, { it.id }))
            .map { FixtureRecord(it.id, it.date, it.label, it.strain) },
        fixtureSourceLabel = fixtureSourceLabel,
        oneOffs = oneOffs
            .sortedWith(compareBy({ it.date }, { it.id }))
            .map { OneOffBlockerRecord(it.id, it.date, it.label, it.strain) },
    )

// --- Artifacts → draft (hydration, story 7) ------------------------------------------

fun BlockerCalendarArtifact.toConstraints(): Constraints =
    Constraints(
        recurring = recurring.map { RecurringBlocker(it.id, it.label, it.days.toSet(), it.timeRange, it.strain) },
        fixtures = fixtures.map { Fixture(it.id, it.date, it.label, it.strain) },
        fixtureSourceLabel = fixtureSourceLabel,
        oneOffs = oneOffs.map { OneOffBlocker(it.id, it.date, it.label, it.strain) },
    )

/**
 * The highest id this calendar has handed out. `Ids` is a process-wide counter that restarts
 * at 0, so it is re-seeded above this on load (Epic 1 open point 5: **persisted high-water
 * mark, not UUID re-keying** — the artifact already carries every live id, so the mark needs
 * no separate document, and keeping `Long` ids leaves the UI's identity handling untouched).
 * Ids of deleted blockers are never reused for a live one: every surviving id is below the mark.
 */
val BlockerCalendarArtifact.highestId: Long
    get() = maxOf(
        recurring.maxOfOrNull { it.id } ?: 0L,
        fixtures.maxOfOrNull { it.id } ?: 0L,
        oneOffs.maxOfOrNull { it.id } ?: 0L,
    )

fun BlockerCalendarArtifact.reseedIds() = Ids.seedAtLeast(highestId)

/**
 * Rebuilds the in-memory draft from whatever has been persisted, falling back to [fallback]
 * (the seeded persona draft) field-group by field-group. A half-finished onboarding restores
 * exactly the steps that were completed — the missing ones stay at their defaults instead of
 * overwriting what is already on disk.
 */
fun hydrateDraft(
    fallback: OnboardingDraft,
    profile: AthleteProfileArtifact?,
    status: AthleteStatusArtifact?,
    goals: AthleteGoalsArtifact?,
    calendar: BlockerCalendarArtifact?,
): OnboardingDraft {
    val base = profile?.let {
        Profile(
            age = it.age.toString(),
            sex = it.sex,
            heightCm = it.heightCm.toString(),
            weightKg = it.weightKg.asInputText(),
            experience = fallback.profile.experience,
            assessment = fallback.profile.assessment,
        )
    } ?: fallback.profile

    val excluded = goals?.excluded?.toSet()
    val withStatus = if (status == null && excluded == null) {
        base
    } else {
        base.copy(
            experience = status?.experience ?: base.experience,
            assessment = Category.entries.associateWith { category ->
                val previous = base.assessment[category]
                SelfAssessment(
                    rating = if (status != null) status.selfAssessment[category] else previous?.rating,
                    dontCare = excluded?.contains(category) ?: (previous?.dontCare ?: false),
                )
            },
        )
    }

    return fallback.copy(
        profile = withStatus,
        priorities = goals?.priorities ?: fallback.priorities,
        focusCount = goals?.focusCount ?: fallback.focusCount,
        lane = goals?.lane ?: fallback.lane,
        injuries = status?.injuries ?: fallback.injuries,
        constraints = calendar?.toConstraints() ?: fallback.constraints,
    )
}

/** The baseline inputs are digit-only, so an integral weight must not come back as "88.0". */
private fun Double.asInputText(): String =
    if (this % 1.0 == 0.0) toLong().toString() else toString()
