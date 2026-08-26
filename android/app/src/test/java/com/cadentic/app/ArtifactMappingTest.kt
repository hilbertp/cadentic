package com.cadentic.app

import com.cadentic.app.domain.Category
import com.cadentic.app.domain.Constraints
import com.cadentic.app.domain.Fixture
import com.cadentic.app.domain.Ids
import com.cadentic.app.domain.OnboardingDraft
import com.cadentic.app.domain.OneOffBlocker
import com.cadentic.app.domain.Profile
import com.cadentic.app.domain.Rating
import com.cadentic.app.domain.RecurringBlocker
import com.cadentic.app.domain.Seed
import com.cadentic.app.domain.SelfAssessment
import com.cadentic.app.domain.Sex
import com.cadentic.app.domain.Strain
import com.cadentic.app.domain.artifacts.ArtifactError
import com.cadentic.app.domain.artifacts.ArtifactException
import com.cadentic.app.domain.artifacts.ArtifactId
import com.cadentic.app.domain.artifacts.toArtifact
import com.cadentic.app.domain.artifacts.toGoalsArtifact
import com.cadentic.app.domain.artifacts.toProfileArtifact
import com.cadentic.app.domain.artifacts.toStatusArtifact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant

/** Stories 1–4, write side: what the UI model becomes once it crosses into an artifact. */
class ArtifactMappingTest {

    private val now: Instant = FIXED_CLOCK.instant()

    @Before fun freshProcess() = Restart.simulate()

    private fun draft(profile: Profile = Profile()) =
        OnboardingDraft(profile = profile, constraints = Seed.constraints(TODAY))

    // --- Story 1: profile --------------------------------------------------

    @Test
    fun `string inputs become numbers`() {
        val artifact = draft().toProfileArtifact(now)

        assertEquals(27, artifact.age)
        assertEquals(191, artifact.heightCm)
        assertEquals(88.0, artifact.weightKg, 0.0)
        assertEquals(Sex.MALE, artifact.sex)
    }

    @Test
    fun `out-of-range base data cannot reach the artifact`() {
        listOf(
            "age" to Profile(age = "9"),
            "heightCm" to Profile(heightCm = "3"),
            "weightKg" to Profile(weightKg = "5"),
        ).forEach { (field, profile) ->
            try {
                draft(profile).toProfileArtifact(now)
                fail("$field: expected the write to be refused")
            } catch (e: ArtifactException) {
                val error = e.error
                assertTrue(error is ArtifactError.InvalidField)
                assertEquals(ArtifactId.ATHLETE_PROFILE, error.artifact)
                assertEquals(field, (error as ArtifactError.InvalidField).field)
            }
        }
    }

    @Test
    fun `an emptied field is refused rather than defaulted`() {
        try {
            draft(Profile(age = "")).toProfileArtifact(now)
            fail("expected the write to be refused")
        } catch (e: ArtifactException) {
            assertTrue(e.error is ArtifactError.InvalidField)
        }
    }

    // --- Story 2: status ---------------------------------------------------

    @Test
    fun `all four categories are present, a skipped one as null`() {
        val artifact = draft(
            Profile(
                assessment = mapOf(
                    Category.CARDIO to SelfAssessment(Rating.MID),
                    Category.STRENGTH to SelfAssessment(Rating.HIGH),
                    // EXPLOSIVENESS and HYPERTROPHY never rated.
                ),
            ),
        ).toStatusArtifact(now)

        assertEquals(Category.entries.toSet(), artifact.selfAssessment.keys)
        assertEquals(Rating.MID, artifact.selfAssessment[Category.CARDIO])
        assertNull(artifact.selfAssessment[Category.EXPLOSIVENESS])
        assertNull(artifact.selfAssessment[Category.HYPERTROPHY])
    }

    @Test
    fun `experience and the full injuries list land in status`() {
        val artifact = draft().copy(injuries = listOf("Right ankle instability", "Shoulder impingement"))
            .toStatusArtifact(now)

        assertEquals("Advanced — 5–10 years", artifact.experience)
        assertEquals(listOf("Right ankle instability", "Shoulder impingement"), artifact.injuries)
    }

    @Test
    fun `a don't-care category keeps its rating in status - the exclusion lives in goals`() {
        val d = draft(
            Profile(
                assessment = mapOf(
                    Category.CARDIO to SelfAssessment(Rating.MID),
                    Category.STRENGTH to SelfAssessment(Rating.MID),
                    Category.EXPLOSIVENESS to SelfAssessment(Rating.LOW),
                    // Rated *and* excluded: the athlete knows where they stand, and still
                    // doesn't want it programmed this cycle.
                    Category.HYPERTROPHY to SelfAssessment(Rating.HIGH, dontCare = true),
                ),
            ),
        ).copy(priorities = listOf(Category.CARDIO, Category.EXPLOSIVENESS, Category.STRENGTH))

        assertEquals(Rating.HIGH, d.toStatusArtifact(now).selfAssessment[Category.HYPERTROPHY])
        assertEquals(listOf(Category.HYPERTROPHY), d.toGoalsArtifact(now).excluded)
    }

    // --- Story 3: goals ----------------------------------------------------

    @Test
    fun `focusCount is serialized as the effective value, never an impossible one`() {
        val single = draft().copy(priorities = listOf(Category.CARDIO), focusCount = 2)
        assertEquals(1, single.toGoalsArtifact(now).focusCount)

        val zeroed = draft().copy(focusCount = 0)
        assertEquals(1, zeroed.toGoalsArtifact(now).focusCount)
    }

    @Test
    fun `priority order is preserved exactly - it is the ranking`() {
        val ordered = listOf(Category.STRENGTH, Category.HYPERTROPHY, Category.CARDIO)
        assertEquals(ordered, draft().copy(priorities = ordered).toGoalsArtifact(now).priorities)
    }

    @Test
    fun `the lane is carried through`() {
        assertEquals(draft().lane, draft().toGoalsArtifact(now).lane)
    }

    // --- Story 4: blocker calendar -----------------------------------------

    @Test
    fun `all three blocker kinds, their strain, and the fixture source are persisted`() {
        val constraints = Constraints(
            recurring = listOf(
                RecurringBlocker(
                    Ids.next(), "Team practice",
                    setOf(DayOfWeek.THURSDAY, DayOfWeek.TUESDAY), "19:00–20:30", Strain.MEDIUM,
                ),
            ),
            fixtures = listOf(Fixture(Ids.next(), TODAY.plusDays(11), "League game", Strain.HARD)),
            fixtureSourceLabel = "Season schedule",
            oneOffs = listOf(OneOffBlocker(Ids.next(), TODAY.plusDays(17), "Travel day", Strain.LIGHT)),
        )

        val artifact = constraints.toArtifact(now)

        assertEquals("Season schedule", artifact.fixtureSourceLabel)
        assertEquals(Strain.MEDIUM, artifact.recurring.single().strain)
        assertEquals("19:00–20:30", artifact.recurring.single().timeRange)
        assertEquals(Strain.HARD, artifact.fixtures.single().strain)
        assertEquals(Strain.LIGHT, artifact.oneOffs.single().strain)
        // Canonical Mon→Sun regardless of the order the athlete tapped the chips.
        assertEquals(listOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY), artifact.recurring.single().days)
    }

    @Test
    fun `fixtures are serialized in canonical date order`() {
        val late = Fixture(Ids.next(), TODAY.plusDays(20), "Round 3", Strain.HARD)
        val early = Fixture(Ids.next(), TODAY.plusDays(2), "Round 1", Strain.HARD)
        val constraints = Constraints(
            recurring = emptyList(), fixtures = listOf(late, early),
            fixtureSourceLabel = "Season schedule", oneOffs = emptyList(),
        )

        assertEquals(listOf("Round 1", "Round 3"), constraints.toArtifact(now).fixtures.map { it.label })
    }
}
