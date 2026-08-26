package com.cadentic.app

import com.cadentic.app.domain.Category
import com.cadentic.app.domain.Lane
import com.cadentic.app.domain.Rating
import com.cadentic.app.domain.Sex
import com.cadentic.app.domain.Strain
import com.cadentic.app.domain.artifacts.ARTIFACT_SCHEMA_VERSION
import com.cadentic.app.domain.artifacts.ArtifactError
import com.cadentic.app.domain.artifacts.ArtifactException
import com.cadentic.app.domain.artifacts.ArtifactId
import com.cadentic.app.domain.artifacts.AthleteGoalsArtifact
import com.cadentic.app.domain.artifacts.AthleteProfileArtifact
import com.cadentic.app.domain.artifacts.AthleteStatusArtifact
import com.cadentic.app.domain.artifacts.GoalsLock
import com.cadentic.app.domain.artifacts.highestId
import com.cadentic.app.domain.artifacts.ProgressionEntry
import com.cadentic.app.domain.artifacts.ProgressionLogArtifact
import com.cadentic.app.domain.artifacts.ProgressionSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import java.time.LocalDate

/** Story 0 — the persistence foundation every other story writes through. */
class ArtifactRepositoryTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun dir(): File = File(tmp.root, "artifacts")

    private val profile = AthleteProfileArtifact(
        updatedAt = Instant.EPOCH, age = 27, sex = Sex.MALE, heightCm = 191, weightKg = 88.0,
    )

    private val goals = AthleteGoalsArtifact(
        updatedAt = Instant.EPOCH,
        lane = Lane.LONGEVITY,
        priorities = listOf(Category.CARDIO, Category.EXPLOSIVENESS, Category.STRENGTH),
        focusCount = 2,
        excluded = listOf(Category.HYPERTROPHY),
    )

    @Test
    fun `artifact survives process restart and comes back structurally equal`() {
        repositoryIn(dir()).writeProfile(profile)

        Restart.simulate()
        val readBack = repositoryIn(dir()).readProfile()

        // updatedAt is stamped by the repository, so equality is asserted on the parsed
        // artifact, never on bytes — see story 0.
        assertNotNull(readBack)
        assertEquals(profile.copy(updatedAt = readBack!!.updatedAt), readBack)
    }

    @Test
    fun `every artifact carries schemaVersion and updatedAt`() {
        val repo = repositoryIn(dir())
        repo.writeProfile(profile)
        repo.writeGoals(goals)
        repo.writeStatus(
            AthleteStatusArtifact(
                updatedAt = Instant.EPOCH,
                experience = "Advanced — 5–10 years",
                selfAssessment = Category.entries.associateWith { null },
                injuries = emptyList(),
            ),
        )
        repo.initializeProgressionLogIfAbsent()

        listOf(
            ArtifactId.ATHLETE_PROFILE, ArtifactId.ATHLETE_GOALS,
            ArtifactId.ATHLETE_STATUS, ArtifactId.PROGRESSION_LOG,
        ).forEach { id ->
            val text = File(dir(), id.fileName).readText()
            assertTrue("${id.fileName} misses schemaVersion", text.contains("\"schemaVersion\": $ARTIFACT_SCHEMA_VERSION"))
            assertTrue("${id.fileName} misses updatedAt", text.contains("\"updatedAt\": \"2026-08-26T09:00:00Z\""))
        }
    }

    @Test
    fun `a newer schemaVersion is refused by name, never half-read`() {
        repositoryIn(dir()).writeProfile(profile)
        val f = File(dir(), ArtifactId.ATHLETE_PROFILE.fileName)
        f.writeText(f.readText().replace("\"schemaVersion\": $ARTIFACT_SCHEMA_VERSION", "\"schemaVersion\": 99"))

        val error = assertThrowsArtifactError { repositoryIn(dir()).readProfile() }
        assertTrue(error is ArtifactError.UnsupportedSchemaVersion)
        assertEquals(ArtifactId.ATHLETE_PROFILE, error.artifact)
        assertTrue(error.message.contains("99"))
    }

    @Test
    fun `a corrupt artifact is named, not silently ignored`() {
        dir().mkdirs()
        File(dir(), ArtifactId.ATHLETE_PROFILE.fileName).writeText("{ not json")

        val error = assertThrowsArtifactError { repositoryIn(dir()).readProfile() }
        assertTrue(error is ArtifactError.Corrupt)
        assertEquals(ArtifactId.ATHLETE_PROFILE, error.artifact)
    }

    @Test
    fun `an unwritten artifact reads as null rather than failing`() {
        assertNull(repositoryIn(dir()).readProfile())
        assertNull(repositoryIn(dir()).readGoals())
    }

    @Test
    fun `writes leave no temp file behind`() {
        val repo = repositoryIn(dir())
        repo.writeProfile(profile)
        repo.writeProfile(profile.copy(weightKg = 87.0))

        val leftovers = dir().listFiles()!!.filter { it.name.endsWith(".tmp") }
        assertTrue("temp files left: $leftovers", leftovers.isEmpty())
    }

    @Test
    fun `serialization is canonical - equal data writes byte-identical apart from updatedAt`() {
        val repo = repositoryIn(dir())
        repo.writeGoals(goals)
        val first = File(dir(), ArtifactId.ATHLETE_GOALS.fileName).readText()

        // Same data, different collection instances and iteration history.
        repo.writeGoals(goals.copy(priorities = goals.priorities.toMutableList()))
        val second = File(dir(), ArtifactId.ATHLETE_GOALS.fileName).readText()

        assertEquals(first, second)
    }

    @Test
    fun `re-editing updates the artifact in place rather than accumulating copies`() {
        val repo = repositoryIn(dir())
        repo.writeProfile(profile)
        repo.writeProfile(profile.copy(weightKg = 86.0))

        assertEquals(86.0, repo.readProfile()!!.weightKg, 0.0)
        assertEquals(1, dir().listFiles()!!.count { it.name == ArtifactId.ATHLETE_PROFILE.fileName })
    }

    // --- Story 3: the lock -------------------------------------------------

    @Test
    fun `goals writes are refused once the cycle is locked - and still refused after a restart`() {
        val repo = repositoryIn(dir())
        repo.writeGoals(goals)
        repo.lockGoals(GoalsLock(FIXED_CLOCK.instant(), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 11, 23)))

        Restart.simulate()
        val afterRestart = repositoryIn(dir())
        assertNotNull("the lock itself must be durable", afterRestart.readGoals()!!.lockedForCycle)

        val error = assertThrowsArtifactError { afterRestart.writeGoals(goals.copy(lane = Lane.PERFORMANCE)) }
        assertTrue(error is ArtifactError.GoalsLocked)
        assertEquals(Lane.LONGEVITY, afterRestart.readGoals()!!.lane)
    }

    @Test
    fun `locking twice is refused - an approved cycle cannot be re-approved into new dates`() {
        val repo = repositoryIn(dir())
        repo.writeGoals(goals)
        val lock = GoalsLock(FIXED_CLOCK.instant(), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 11, 23))
        repo.lockGoals(lock)

        val error = assertThrowsArtifactError {
            repo.lockGoals(lock.copy(startDate = LocalDate.of(2027, 1, 1)))
        }
        assertTrue(error is ArtifactError.GoalsLocked)
        assertEquals(LocalDate.of(2026, 9, 1), repo.readGoals()!!.lockedForCycle!!.startDate)
    }

    @Test
    fun `locking goals that were never written is refused by name`() {
        val error = assertThrowsArtifactError {
            repositoryIn(dir()).lockGoals(GoalsLock(FIXED_CLOCK.instant(), TODAY, TODAY.plusWeeks(12)))
        }
        assertTrue(error is ArtifactError.Missing)
        assertEquals(ArtifactId.ATHLETE_GOALS, error.artifact)
    }

    // --- Schema migration ---------------------------------------------------

    @Test
    fun `a v1 calendar folds its imported fixtures into one-offs`() {
        dir().mkdirs()
        // Exactly what v1 wrote, back when league games were a separate kind.
        File(dir(), ArtifactId.BLOCKER_CALENDAR.fileName).writeText(
            """
            {
              "schemaVersion": 1,
              "updatedAt": "2026-08-26T09:00:00Z",
              "recurring": [
                { "id": 14, "label": "Team practice", "days": ["TUESDAY", "THURSDAY"],
                  "timeRange": "19:00–20:30", "strain": "MEDIUM" }
              ],
              "fixtures": [
                { "id": 1, "date": "2026-08-29", "label": "Round 1", "strain": "HARD" },
                { "id": 2, "date": "2026-09-04", "label": "Round 2", "strain": "MEDIUM" }
              ],
              "fixtureSourceLabel": "Season schedule",
              "oneOffs": [
                { "id": 15, "date": "2026-09-12", "label": "Travel day", "strain": "LIGHT" }
              ]
            }
            """.trimIndent(),
        )

        val calendar = repositoryIn(dir()).readBlockerCalendar()!!

        // Nothing is lost, and the athlete's own strain edits come with it.
        assertEquals(
            listOf("Round 1", "Round 2", "Travel day"),
            calendar.oneOffs.map { it.label },
        )
        assertEquals(Strain.MEDIUM, calendar.oneOffs.single { it.label == "Round 2" }.strain)
        assertEquals("Team practice", calendar.recurring.single().label)
        // Ids survive the fold, so the counter still re-seeds above the highest one.
        assertEquals(listOf(1L, 2L, 15L), calendar.oneOffs.map { it.id })
        assertEquals(15L, calendar.highestId)
    }

    // --- Story 5: the progression log --------------------------------------

    @Test
    fun `progression log initializes empty and is never clobbered afterwards`() {
        val repo = repositoryIn(dir())
        assertEquals(emptyList<ProgressionEntry>(), repo.initializeProgressionLogIfAbsent().entries)

        repo.writeProgressionLog(
            ProgressionLogArtifact(
                updatedAt = Instant.EPOCH,
                entries = listOf(
                    ProgressionEntry(
                        date = TODAY,
                        exercise = "Back squat",
                        sets = listOf(ProgressionSet(reps = 5, weightKg = 100.0)),
                        durationMin = 45,
                        completed = true,
                    ),
                ),
            ),
        )

        Restart.simulate()
        val afterRestart = repositoryIn(dir())
        // A second cycle's onboarding must not erase logged training.
        assertEquals(1, afterRestart.initializeProgressionLogIfAbsent().entries.size)
        assertEquals("Back squat", afterRestart.readProgressionLog()!!.entries.single().exercise)
    }

    @Test
    fun `a skipped rating is stored as an explicit null, not omitted`() {
        repositoryIn(dir()).writeStatus(
            AthleteStatusArtifact(
                updatedAt = Instant.EPOCH,
                experience = "Advanced — 5–10 years",
                selfAssessment = mapOf(
                    Category.CARDIO to Rating.MID,
                    Category.STRENGTH to Rating.MID,
                    Category.EXPLOSIVENESS to Rating.LOW,
                    Category.HYPERTROPHY to null,
                ),
                injuries = listOf("Lower-back disc (L4/L5)"),
            ),
        )

        val text = File(dir(), ArtifactId.ATHLETE_STATUS.fileName).readText()
        assertTrue("HYPERTROPHY must be present as null", text.contains("\"HYPERTROPHY\": null"))

        Restart.simulate()
        val status = repositoryIn(dir()).readStatus()!!
        assertTrue(status.selfAssessment.containsKey(Category.HYPERTROPHY))
        assertNull(status.selfAssessment[Category.HYPERTROPHY])
    }

    private fun assertThrowsArtifactError(block: () -> Unit): ArtifactError {
        try {
            block()
        } catch (e: ArtifactException) {
            return e.error
        }
        fail("expected an ArtifactException")
        error("unreachable")
    }
}
