package com.cadentic.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cadentic.app.data.JsonArtifactRepository
import com.cadentic.app.domain.Category
import com.cadentic.app.domain.Ids
import com.cadentic.app.domain.Status
import com.cadentic.app.domain.Strain
import com.cadentic.app.domain.artifacts.ArtifactId
import com.cadentic.app.domain.artifacts.GoalsLock
import com.cadentic.app.domain.artifacts.MesoRequestAssembler
import com.cadentic.app.domain.artifacts.MesoRequestResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate

/**
 * The epic's definition of done, on a real device: onboarding writes artifacts to app-private
 * storage, the process "restarts", and a complete meso-request payload assembles from those
 * files alone. Runs on device because the JVM tests cannot prove the parts that are actually
 * platform-specific — `ATOMIC_MOVE` and `fsync` on Android's filesystem, and the ViewModel
 * hydrating on the real main looper.
 */
@RunWith(AndroidJUnit4::class)
class ArtifactsOnDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val today: LocalDate = LocalDate.of(2026, 8, 26)

    private lateinit var dir: File

    @Before
    fun freshStore() {
        dir = File(instrumentation.targetContext.filesDir, "artifacts-test")
        dir.deleteRecursively()
        Ids.resetForTests()
    }

    private fun repository() = JsonArtifactRepository(dir)

    /** The ViewModel reads and writes on the main thread, exactly as the app does. */
    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    @Test
    fun onboardingWritesArtifactsThatSurviveAProcessRestart() {
        lateinit var first: OnboardingViewModel
        onMain {
            first = OnboardingViewModel(repository(), today)
            first.setAge("34")
            first.setWeight("81")
            first.continueFromStep(1)
            first.addInjury("Right ankle instability — recurring")
            first.continueFromStep(2)
            first.addOneOff(today.plusDays(9), "Travel day", Strain.LIGHT)
            first.addOneOff(today.plusDays(9), "Travel day", Strain.LIGHT)
        }
        // Blockers reach disk on the way out of step 3; generation itself is not needed here.
        onMain { first.continueFromStep(3) }

        listOf(
            ArtifactId.ATHLETE_PROFILE, ArtifactId.ATHLETE_STATUS,
            ArtifactId.ATHLETE_GOALS, ArtifactId.BLOCKER_CALENDAR,
        ).forEach {
            assertTrue("${it.fileName} was not written", File(dir, it.fileName).exists())
        }
        assertTrue("a temp file was left behind", dir.listFiles()!!.none { it.name.endsWith(".tmp") })

        // --- process restart ---
        Ids.resetForTests()
        lateinit var relaunched: OnboardingViewModel
        onMain { relaunched = OnboardingViewModel(repository(), today) }

        assertEquals("34", relaunched.draft.profile.age)
        assertEquals("81", relaunched.draft.profile.weightKg)
        assertTrue(relaunched.draft.injuries.contains("Right ankle instability — recurring"))

        val twins = relaunched.draft.constraints.oneOffs.filter { it.label == "Travel day" }
        assertEquals(2, twins.size)
        assertEquals("twins must keep distinct ids", 2, twins.map { it.id }.distinct().size)
        onMain { relaunched.updateOneOff(twins[0].id, "Flight out", Strain.MEDIUM) }
        assertEquals(
            "Travel day",
            relaunched.draft.constraints.oneOffs.single { it.id == twins[1].id }.label,
        )
    }

    @Test
    fun theMesoRequestPayloadAssemblesFromArtifactsAloneAfterARestart() {
        onMain {
            val vm = OnboardingViewModel(repository(), today)
            vm.continueFromStep(1)
            vm.continueFromStep(2)
            vm.continueFromStep(3)
        }

        // --- process restart: nothing but the files on disk survives ---
        Ids.resetForTests()
        val result = MesoRequestAssembler(repository()).assemble(today)

        assertTrue(
            (result as? MesoRequestResult.Invalid)?.message ?: "",
            result is MesoRequestResult.Ok,
        )
        val ok = result as MesoRequestResult.Ok
        assertEquals(today, ok.payload.requestDate)
        assertEquals(27, ok.payload.profile.age)
        assertEquals(listOf(Category.CARDIO, Category.EXPLOSIVENESS), ok.payload.goals.focusThisCycle)
        assertEquals("Season schedule", ok.payload.blockerCalendar.fixtureSourceLabel)
        assertFalse("local ids must not reach the engine", ok.json.toString().contains("\"id\""))
    }

    @Test
    fun anApprovedCycleLocksGoalsAndSurvivesARestart() {
        onMain {
            val vm = OnboardingViewModel(repository(), today)
            vm.continueFromStep(1)
            vm.continueFromStep(2)
            vm.continueFromStep(3)
        }
        repository().lockGoals(GoalsLock(java.time.Instant.now(), today, today.plusWeeks(12)))

        Ids.resetForTests()
        lateinit var relaunched: OnboardingViewModel
        onMain { relaunched = OnboardingViewModel(repository(), today) }

        assertNotNull(relaunched.goalsLock)
        assertEquals(Status.APPROVED, relaunched.draft.status)
        assertEquals(4, relaunched.step)
        assertNotNull(relaunched.approvedSummary)
        assertNotNull(repository().readGoals()!!.lockedForCycle)
    }
}
