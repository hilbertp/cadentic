package com.cadentic.app

import com.cadentic.app.domain.Category
import com.cadentic.app.domain.OnboardingDraft
import com.cadentic.app.domain.Seed
import com.cadentic.app.domain.artifacts.ARTIFACT_SCHEMA_VERSION
import com.cadentic.app.domain.artifacts.ArtifactError
import com.cadentic.app.domain.artifacts.ArtifactId
import com.cadentic.app.domain.artifacts.ArtifactRepository
import com.cadentic.app.domain.artifacts.MesoRequestAssembler
import com.cadentic.app.domain.artifacts.MesoRequestResult
import com.cadentic.app.domain.artifacts.toArtifact
import com.cadentic.app.domain.artifacts.toGoalsArtifact
import com.cadentic.app.domain.artifacts.toProfileArtifact
import com.cadentic.app.domain.artifacts.toStatusArtifact
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Story 6 — the epic's definition of done: a payload built from artifacts alone. */
class MesoRequestAssemblerTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun dir(): File = File(tmp.root, "artifacts")

    @Before fun freshProcess() = Restart.simulate()

    /** Writes a complete set of artifacts the way onboarding would, then forgets everything. */
    private fun persistCompleteOnboarding(
        mutate: OnboardingDraft.() -> OnboardingDraft = { this },
    ) {
        val draft = OnboardingDraft(constraints = Seed.constraints(TODAY)).mutate()
        val now = FIXED_CLOCK.instant()
        repositoryIn(dir()).apply {
            writeProfile(draft.toProfileArtifact(now))
            writeStatus(draft.toStatusArtifact(now))
            writeGoals(draft.toGoalsArtifact(now))
            writeBlockerCalendar(draft.constraints.toArtifact(now))
        }
    }

    private fun assembleAfterRestart(): MesoRequestResult {
        Restart.simulate()
        // Nothing but a repository — no ViewModel, no onboarding UI, no seeded draft.
        val repository: ArtifactRepository = repositoryIn(dir())
        return MesoRequestAssembler(repository).assemble(TODAY)
    }

    @Test
    fun `a complete payload assembles from persisted artifacts after a restart`() {
        persistCompleteOnboarding()

        val result = assembleAfterRestart()

        assertTrue((result as? MesoRequestResult.Invalid)?.message ?: "", result is MesoRequestResult.Ok)
        val payload = (result as MesoRequestResult.Ok).payload
        assertEquals(27, payload.profile.age)
        assertEquals("Advanced — 5–10 years", payload.status.experience)
        assertEquals(listOf(Category.CARDIO, Category.EXPLOSIVENESS, Category.STRENGTH), payload.goals.priorities)
        assertEquals(listOf(Category.CARDIO, Category.EXPLOSIVENESS), payload.goals.focusThisCycle)
        assertEquals(listOf(Category.STRENGTH), payload.goals.queuedForLater)
        // 13 seeded game days, all one-offs now.
        assertEquals(13, payload.blockerCalendar.oneOffs.size)
        assertEquals("Team practice", payload.blockerCalendar.recurring.single().label)
    }

    @Test
    fun `the assembler injects the requestDate as a top-level key`() {
        persistCompleteOnboarding()

        val ok = assembleAfterRestart() as MesoRequestResult.Ok

        assertEquals(TODAY, ok.payload.requestDate)
        assertEquals(TODAY.toString(), ok.json["requestDate"]!!.jsonPrimitive.content)
    }

    @Test
    fun `blocker ids are stripped from the payload`() {
        persistCompleteOnboarding()

        val json = (assembleAfterRestart() as MesoRequestResult.Ok).json.toString()

        assertFalse("local storage ids have no meaning to the engine", json.contains("\"id\""))
        assertTrue("the blockers themselves must survive", json.contains("Round 1"))
    }

    @Test
    fun `a missing artifact fails validation by name - never a silent partial payload`() {
        persistCompleteOnboarding()
        File(dir(), ArtifactId.ATHLETE_STATUS.fileName).delete()

        val invalid = assembleAfterRestart() as MesoRequestResult.Invalid

        assertTrue(invalid.errors.any { it is ArtifactError.Missing && it.artifact == ArtifactId.ATHLETE_STATUS })
        assertTrue(invalid.message.contains("athlete-status.json"))
    }

    @Test
    fun `nothing persisted at all names every missing artifact`() {
        val invalid = assembleAfterRestart() as MesoRequestResult.Invalid

        assertEquals(
            setOf(
                ArtifactId.ATHLETE_PROFILE, ArtifactId.ATHLETE_GOALS,
                ArtifactId.ATHLETE_STATUS, ArtifactId.BLOCKER_CALENDAR,
            ),
            invalid.errors.map { it.artifact }.toSet(),
        )
    }

    @Test
    fun `a missing required field names the artifact and the field`() {
        persistCompleteOnboarding()
        val f = File(dir(), ArtifactId.ATHLETE_STATUS.fileName)
        f.writeText(f.readText().replace("\"experience\": \"Advanced — 5–10 years\"", "\"experience\": \"\""))

        val invalid = assembleAfterRestart() as MesoRequestResult.Invalid

        val error = invalid.errors.filterIsInstance<ArtifactError.MissingField>().single()
        assertEquals(ArtifactId.ATHLETE_STATUS, error.artifact)
        assertEquals("experience", error.field)
    }

    @Test
    fun `an artifact this build cannot read fails assembly by name instead of throwing`() {
        persistCompleteOnboarding()
        val f = File(dir(), ArtifactId.ATHLETE_GOALS.fileName)
        f.writeText(f.readText().replace("\"schemaVersion\": $ARTIFACT_SCHEMA_VERSION", "\"schemaVersion\": 42"))

        val invalid = assembleAfterRestart() as MesoRequestResult.Invalid

        assertTrue(invalid.errors.any { it is ArtifactError.UnsupportedSchemaVersion })
    }

    @Test
    fun `focusCount above the cycle limit is rejected as a contract backstop`() {
        persistCompleteOnboarding()
        val f = File(dir(), ArtifactId.ATHLETE_GOALS.fileName)
        // Hand-edited past what the mapper would ever write — the payload contract must
        // still refuse it: at most two priorities are programmed per cycle.
        f.writeText(f.readText().replace("\"focusCount\": 2", "\"focusCount\": 3"))

        val invalid = assembleAfterRestart() as MesoRequestResult.Invalid

        val error = invalid.errors.filterIsInstance<ArtifactError.InvalidField>().single()
        assertEquals(ArtifactId.ATHLETE_GOALS, error.artifact)
        assertEquals("focusCount", error.field)
    }

    @Test
    fun `focusCount above the number of priorities is rejected too`() {
        persistCompleteOnboarding { copy(priorities = listOf(Category.CARDIO)) }
        val f = File(dir(), ArtifactId.ATHLETE_GOALS.fileName)
        f.writeText(f.readText().replace("\"focusCount\": 1", "\"focusCount\": 2"))

        val invalid = assembleAfterRestart() as MesoRequestResult.Invalid

        assertTrue(invalid.errors.any { it is ArtifactError.InvalidField && it.field == "focusCount" })
    }

    @Test
    fun `an empty blocker calendar is legitimate - an athlete with nothing booked still plans`() {
        persistCompleteOnboarding {
            copy(constraints = constraints.copy(recurring = emptyList(), oneOffs = emptyList()))
        }

        assertTrue(assembleAfterRestart() is MesoRequestResult.Ok)
    }

    @Test
    fun `the progression log is deliberately absent from the payload`() {
        persistCompleteOnboarding()
        repositoryIn(dir()).initializeProgressionLogIfAbsent()

        val json = (assembleAfterRestart() as MesoRequestResult.Ok).json

        // PRD routes the log to the History Engine and Mesocycle Tracker, not to this prompt.
        assertFalse(json.containsKey("progressionLog"))
        assertEquals(
            setOf("schemaVersion", "requestDate", "profile", "goals", "status", "blockerCalendar"),
            json.keys,
        )
    }
}
