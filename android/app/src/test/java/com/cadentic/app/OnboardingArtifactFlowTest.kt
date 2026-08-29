package com.cadentic.app

import com.cadentic.app.domain.Category
import com.cadentic.app.domain.Lane
import com.cadentic.app.domain.MesocycleEngine
import com.cadentic.app.domain.Status
import com.cadentic.app.domain.Strain
import com.cadentic.app.domain.artifacts.ArtifactError
import com.cadentic.app.domain.artifacts.ArtifactException
import com.cadentic.app.domain.artifacts.ArtifactId
import com.cadentic.app.domain.artifacts.ArtifactRepository
import com.cadentic.app.domain.artifacts.AthleteGoalsArtifact
import com.cadentic.app.domain.artifacts.GoalsLock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The stories end to end, through the ViewModel: what onboarding writes (1–5) and what a
 * relaunch reads back (7). Every "restart" here is a real one for everything that matters —
 * new repository, new ViewModel, id counter back at zero, only the directory survives.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingArtifactFlowTest {

    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val mainDispatcher = MainDispatcherRule()

    private fun dir(): File = File(tmp.root, "artifacts")
    private fun repository(): ArtifactRepository = repositoryIn(dir())
    private fun launchApp(engine: MesocycleEngine = engineReturning()) =
        OnboardingViewModel(repository(), engine, TODAY, FIXED_CLOCK)

    @Before fun freshProcess() = Restart.simulate()

    /** Steps 1 → 2 → 3, generation, approval — the whole shipped flow. */
    private fun TestScope.completeOnboarding(
        vm: OnboardingViewModel,
        onStep: (Int) -> Unit = {},
    ) {
        onStep(1); vm.continueFromStep(1)
        onStep(2); vm.continueFromStep(2)
        onStep(3); vm.continueFromStep(3)
        advanceUntilIdle()
        vm.approve()
    }

    // --- Stories 1 & 2: step 1 writes profile and status --------------------

    @Test
    fun `completing the baseline step writes the profile and status artifacts`() = runTest(mainDispatcher.dispatcher) {
        val vm = launchApp()
        vm.setAge("33")
        vm.setHeight("184")
        vm.setWeight("79")
        vm.continueFromStep(1)

        val profile = repository().readProfile()!!
        assertEquals(33, profile.age)
        assertEquals(184, profile.heightCm)
        assertEquals(79.0, profile.weightKg, 0.0)

        val status = repository().readStatus()!!
        assertEquals("Advanced — 5–10 years", status.experience)
        assertEquals(Category.entries.toSet(), status.selfAssessment.keys)
        // The persona leaves hypertrophy unrated: unknown, not defaulted to a level.
        assertNull(status.selfAssessment[Category.HYPERTROPHY])
    }

    @Test
    fun `injuries entered on step 2 reach the status artifact`() = runTest(mainDispatcher.dispatcher) {
        val vm = launchApp()
        vm.continueFromStep(1)
        vm.addInjury("Shoulder impingement")
        vm.removeInjury("Right ankle instability")
        vm.continueFromStep(2)

        val injuries = repository().readStatus()!!.injuries
        assertTrue(injuries.contains("Shoulder impingement"))
        assertFalse(injuries.contains("Right ankle instability"))
    }

    // --- Story 0's write rule: back, edit, forward again --------------------

    @Test
    fun `a don't-care toggled after step 2 was completed rewrites the goals artifact`() =
        runTest(mainDispatcher.dispatcher) {
            val vm = launchApp()
            vm.continueFromStep(1)
            vm.continueFromStep(2)
            assertTrue(repository().readGoals()!!.priorities.contains(Category.CARDIO))

            // Back to step 1, drop a category, forward again.
            vm.back()
            vm.back()
            assertEquals(1, vm.step)
            vm.toggleDontCare(Category.CARDIO)
            vm.continueFromStep(1)

            val goals = repository().readGoals()!!
            assertFalse("the reordered priorities must reach the artifact", goals.priorities.contains(Category.CARDIO))
            assertEquals(listOf(Category.CARDIO, Category.HYPERTROPHY), goals.excluded)
        }

    // --- Story 7: hydration -------------------------------------------------

    @Test
    fun `a restart mid-onboarding restores what was completed`() = runTest(mainDispatcher.dispatcher) {
        val first = launchApp()
        first.setAge("33")
        first.setWeight("92")
        first.continueFromStep(1)

        Restart.simulate()
        val relaunched = launchApp()

        assertEquals("33", relaunched.draft.profile.age)
        assertEquals("92", relaunched.draft.profile.weightKg)
        assertEquals(1, relaunched.step)
        assertEquals(Status.DRAFT, relaunched.draft.status)
    }

    @Test
    fun `finishing the remaining steps never reverts the earlier ones to defaults`() =
        runTest(mainDispatcher.dispatcher) {
            val first = launchApp()
            first.setAge("41")
            first.continueFromStep(1)

            Restart.simulate()
            val relaunched = launchApp()
            completeOnboarding(relaunched) { step -> if (step == 2) relaunched.setLane(Lane.PERFORMANCE) }

            assertEquals(41, repository().readProfile()!!.age)
            assertEquals(Lane.PERFORMANCE, repository().readGoals()!!.lane)
            assertEquals(Status.APPROVED, relaunched.draft.status)
        }

    @Test
    fun `an approved cycle lands past onboarding on the next launch`() = runTest(mainDispatcher.dispatcher) {
        completeOnboarding(launchApp())

        Restart.simulate()
        val relaunched = launchApp()

        assertEquals(Status.APPROVED, relaunched.draft.status)
        assertEquals(4, relaunched.step)
        // Epic 2 story 4 changed this: the Mesocycle Plan is now an artifact, so a restart
        // restores the whole cycle rather than only the dates the lock happened to carry.
        val plan = relaunched.draft.plan!!
        assertEquals(samplePlan().startDate, plan.startDate)
        assertEquals(8, plan.durationWeeks)
        assertEquals(4, relaunched.draft.proposal!!.phases.size)
        val summary = relaunched.approvedSummary!!
        assertEquals(samplePlan().startDate, summary.startDate)
        assertEquals(listOf(Category.CARDIO, Category.EXPLOSIVENESS), summary.focusThisCycle)
        assertEquals(listOf(Category.STRENGTH), summary.queuedForLater)
    }

    // --- The weekly ceiling (owner decision, 2026-08-30) ---------------------

    @Test
    fun `the ceiling is optional, survives a restart, and null means no cap`() =
        runTest(mainDispatcher.dispatcher) {
            val vm = launchApp()
            vm.setMaxWeeklyDays(4)
            vm.continueFromStep(1)
            vm.continueFromStep(2)

            assertEquals(4, repository().readGoals()!!.maxWeeklyDays)

            Restart.simulate()
            val relaunched = launchApp()
            assertEquals(4, relaunched.draft.maxWeeklyDays)

            // Clearing it is a real answer, not a missing one — and it persists too.
            relaunched.setMaxWeeklyDays(null)
            relaunched.continueFromStep(1)
            assertNull(repository().readGoals()!!.maxWeeklyDays)

            Restart.simulate()
            assertNull(launchApp().draft.maxWeeklyDays)
        }

    @Test
    fun `the ceiling reaches the meso-request payload`() = runTest(mainDispatcher.dispatcher) {
        val vm = launchApp()
        vm.setMaxWeeklyDays(5)
        vm.continueFromStep(1)
        vm.continueFromStep(2)
        vm.continueFromStep(3)
        advanceUntilIdle()

        val payload = (com.cadentic.app.domain.artifacts.MesoRequestAssembler(repository())
            .assemble(TODAY) as com.cadentic.app.domain.artifacts.MesoRequestResult.Ok).payload
        assertEquals(5, payload.goals.maxWeeklyDays)
    }

    // --- Story 3: the lock ---------------------------------------------------

    @Test
    fun `approval locks the goals and the lock survives a restart`() = runTest(mainDispatcher.dispatcher) {
        val vm = launchApp()
        completeOnboarding(vm)

        // Epic 2 story 4 amends where these dates come from: the lock is minted from the
        // persisted Mesocycle Plan, not from an in-memory proposal the engine invented.
        val lock = vm.goalsLock!!
        val plan = repository().readMesocyclePlan()!!
        assertEquals(plan.startDate, lock.startDate)
        assertEquals(plan.endDate, lock.endDate)

        Restart.simulate()
        assertNotNull(launchApp().goalsLock)
        assertNotNull(repository().readGoals()!!.lockedForCycle)
    }

    // --- Story 5: the progression log ---------------------------------------

    @Test
    fun `onboarding completion initializes an empty progression log`() = runTest(mainDispatcher.dispatcher) {
        assertNull("nothing before onboarding completes", repository().readProgressionLog())

        completeOnboarding(launchApp())

        val log = repository().readProgressionLog()!!
        assertTrue(log.entries.isEmpty())
    }

    // --- Story 4: blocker durability ----------------------------------------

    @Test
    fun `identical-looking blockers stay distinct across a restart`() = runTest(mainDispatcher.dispatcher) {
        val first = launchApp()
        val date = TODAY.plusDays(9)
        first.addOneOff(date, "Travel day", Strain.LIGHT)
        first.addOneOff(date, "Travel day", Strain.LIGHT)
        first.continueFromStep(1)

        Restart.simulate()
        val relaunched = launchApp()
        val twins = relaunched.draft.constraints.oneOffs.filter { it.label == "Travel day" }
        assertEquals(2, twins.size)
        assertEquals(2, twins.map { it.id }.distinct().size)

        relaunched.updateOneOff(twins[0].id, "Flight out", Strain.MEDIUM)

        val after = relaunched.draft.constraints.oneOffs.associateBy { it.id }
        assertEquals("Flight out", after[twins[0].id]!!.label)
        assertEquals("Travel day", after[twins[1].id]!!.label)
        assertEquals(Strain.LIGHT, after[twins[1].id]!!.strain)

        // Deleting one twin must leave the other standing, on disk as well as in memory.
        relaunched.removeOneOff(twins[0].id)
        relaunched.continueFromStep(1)
        val surviving = relaunched.draft.constraints.oneOffs.map { it.id }
        assertFalse(twins[0].id in surviving)
        assertTrue(twins[1].id in surviving)
        val onDisk = repository().readBlockerCalendar()!!.oneOffs.map { it.id }
        assertFalse(twins[0].id in onDisk)
        assertTrue(twins[1].id in onDisk)
    }

    // --- Story 0: the approval write is awaited -----------------------------

    @Test
    fun `an approval whose lock cannot be written is not confirmed to the athlete`() =
        runTest(mainDispatcher.dispatcher) {
            // A store that accepts everything except the one write that makes the approval real.
            val failing = object : ArtifactRepository by repository() {
                override fun lockGoals(lock: GoalsLock): AthleteGoalsArtifact =
                    throw ArtifactException(ArtifactError.WriteFailed(ArtifactId.ATHLETE_GOALS, "disk full"))
            }
            val vm = OnboardingViewModel(failing, engineReturning(), TODAY, FIXED_CLOCK)
            vm.continueFromStep(1)
            vm.continueFromStep(2)
            vm.continueFromStep(3)
            advanceUntilIdle()

            vm.approve()

            // The athlete stays on the proposal rather than seeing a lock that does not exist.
            assertEquals(Status.PROPOSED, vm.draft.status)
            assertNull(vm.goalsLock)
            assertNull(repository().readGoals()!!.lockedForCycle)
        }

    @Test
    fun `a blocker added after a restart cannot collide with a persisted id`() =
        runTest(mainDispatcher.dispatcher) {
            val first = launchApp()
            first.addOneOff(TODAY.plusDays(3), "Wedding", Strain.MEDIUM)
            first.continueFromStep(1)
            val persistedIds = first.draft.constraints.let { c ->
                (c.recurring.map { it.id } + c.oneOffs.map { it.id }).toSet()
            }

            // The id counter restarts at 0 with the process; the calendar re-seeds it.
            Restart.simulate()
            val relaunched = launchApp()
            relaunched.addOneOff(TODAY.plusDays(4), "Conference", Strain.LIGHT)

            val added = relaunched.draft.constraints.oneOffs.single { it.label == "Conference" }
            assertFalse("id ${added.id} collides with a persisted blocker", added.id in persistedIds)
        }

    @Test
    fun `the persona seed does not re-run once a calendar has been persisted`() =
        runTest(mainDispatcher.dispatcher) {
            val first = launchApp()
            val seededGameDays = first.draft.constraints.oneOffs.size
            first.continueFromStep(1)
            first.removeOneOff(first.draft.constraints.oneOffs.first().id)
            first.continueFromStep(2)

            Restart.simulate()
            val relaunched = launchApp()

            // Without the guard, every launch would push a fresh set of game days back in.
            assertEquals(seededGameDays - 1, relaunched.draft.constraints.oneOffs.size)
            assertEquals(seededGameDays - 1, repository().readBlockerCalendar()!!.oneOffs.size)
        }
}
