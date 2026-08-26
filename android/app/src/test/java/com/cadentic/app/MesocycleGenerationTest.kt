package com.cadentic.app

import com.cadentic.app.domain.EngineError
import com.cadentic.app.domain.Lane
import com.cadentic.app.domain.MesocycleResult
import com.cadentic.app.domain.Status
import com.cadentic.app.domain.Strain
import com.cadentic.app.domain.artifacts.ArtifactRepository
import com.cadentic.app.domain.artifacts.AthleteGoalsArtifact
import com.cadentic.app.domain.artifacts.AthleteProfileArtifact
import com.cadentic.app.domain.artifacts.GoalsLock
import com.cadentic.app.domain.artifacts.MesocyclePlanArtifact
import com.cadentic.app.domain.artifacts.PhaseType
import kotlinx.coroutines.CompletableDeferred
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
 * Generation end to end through the ViewModel (Epic 2 stories 4 and 5): what the athlete sees
 * while it runs, what happens when it fails, and what is on disk once they approve.
 *
 * No socket and no LLM — [FakeEngine] stands in at the domain boundary the real client sits
 * behind, which is the same boundary the old local stub occupied.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MesocycleGenerationTest {

    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val mainDispatcher = MainDispatcherRule()

    private fun dir(): File = File(tmp.root, "artifacts")
    private fun repository(): ArtifactRepository = repositoryIn(dir())

    private fun launchApp(
        engine: com.cadentic.app.domain.MesocycleEngine = engineReturning(),
        repository: ArtifactRepository = repository(),
    ) = OnboardingViewModel(repository, engine, TODAY, FIXED_CLOCK)

    @Before fun freshProcess() = Restart.simulate()

    private fun TestScope.generate(vm: OnboardingViewModel) {
        vm.continueFromStep(1)
        vm.continueFromStep(2)
        vm.continueFromStep(3)
        advanceUntilIdle()
    }

    // --- Story 5: the swap ---------------------------------------------------

    @Test
    fun `generating produces the engine's plan, not a locally invented one`() =
        runTest(mainDispatcher.dispatcher) {
            val plan = samplePlan(durationWeeks = 6, startDate = TODAY.plusDays(3))
            val vm = launchApp(engineReturning(plan))
            generate(vm)

            assertEquals(Status.PROPOSED, vm.draft.status)
            assertEquals(4, vm.step)
            assertEquals(plan, vm.draft.plan)
            assertEquals(6, vm.draft.plan!!.durationWeeks)
            assertEquals(TODAY.plusDays(3), vm.draft.plan!!.startDate)
        }

    @Test
    fun `the proposal screen's view is derived from the plan`() =
        runTest(mainDispatcher.dispatcher) {
            val vm = launchApp(engineReturning(samplePlan(durationWeeks = 6)))
            generate(vm)

            val proposal = vm.draft.proposal!!
            assertEquals("6 weeks, engine first.", proposal.headline)
            assertEquals("longevity-first", proposal.laneLabel)
            assertEquals(5, proposal.sessionsPerWeek)
            // Phase colour and the dashed deload border switch on phaseType, so it has to
            // survive the mapping — a plan calling its first phase anything at all still
            // renders as a BASE segment.
            assertEquals(
                listOf(PhaseType.BASE, PhaseType.BUILD, PhaseType.DELOAD, PhaseType.PEAK),
                proposal.phases.map { it.phaseType },
            )
        }

    @Test
    fun `the athlete waits in GENERATING until the engine answers`() =
        runTest(mainDispatcher.dispatcher) {
            val gate = CompletableDeferred<Unit>()
            val engine = FakeEngine(mutableListOf(MesocycleResult.Ok(samplePlan())), gate)
            val vm = launchApp(engine)

            vm.continueFromStep(1)
            vm.continueFromStep(2)
            vm.continueFromStep(3)

            assertEquals(Status.GENERATING, vm.draft.status)
            assertNull(vm.draft.plan)

            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(Status.PROPOSED, vm.draft.status)
        }

    @Test
    fun `blockers entered on step 3 reach the engine — the writes land first`() =
        runTest(mainDispatcher.dispatcher) {
            val engine = engineReturning()
            val vm = launchApp(engine)
            vm.continueFromStep(1)
            vm.continueFromStep(2)
            // Added on step 3, immediately before generating: the payload is assembled from
            // artifacts, so this only arrives if the step-3 write completed first.
            vm.addOneOff(TODAY.plusDays(10), "Wedding", Strain.LIGHT)
            vm.continueFromStep(3)
            advanceUntilIdle()

            val calendar = repository().readBlockerCalendar()!!
            assertTrue(calendar.oneOffs.any { it.label == "Wedding" })
            assertEquals(1, engine.calls)
        }

    // --- Story 5: failure handling -------------------------------------------

    @Test
    fun `a failed generation lands in FAILED with the reason, and nothing is written`() =
        runTest(mainDispatcher.dispatcher) {
            val vm = launchApp(engineFailing(EngineError.RATE_LIMITED))
            generate(vm)

            assertEquals(Status.FAILED, vm.draft.status)
            assertEquals(EngineError.RATE_LIMITED, vm.draft.generationError)
            assertEquals(4, vm.step)
            assertNull(vm.draft.plan)
            assertNull(repository().readMesocyclePlan())
            assertNull(repository().readGoals()!!.lockedForCycle)
        }

    @Test
    fun `an unreachable backend is not the same failure as an unreachable provider`() =
        runTest(mainDispatcher.dispatcher) {
            // Airplane mode is BACKEND_UNREACHABLE — the app never got there. The backend
            // failing to reach Claude is PROVIDER_UNREACHABLE and says something different.
            val vm = launchApp(engineFailing(EngineError.BACKEND_UNREACHABLE))
            generate(vm)
            assertEquals(EngineError.BACKEND_UNREACHABLE, vm.draft.generationError)
            assertTrue(vm.draft.generationError!!.message.contains("connection"))
            assertFalse(
                EngineError.PROVIDER_UNREACHABLE.message == EngineError.BACKEND_UNREACHABLE.message,
            )
        }

    @Test
    fun `retry runs a fresh generation with a new request id`() =
        runTest(mainDispatcher.dispatcher) {
            val engine = FakeEngine(
                mutableListOf(
                    MesocycleResult.Failed(EngineError.TIMEOUT),
                    MesocycleResult.Ok(samplePlan()),
                ),
            )
            val vm = launchApp(engine)
            generate(vm)
            assertEquals(Status.FAILED, vm.draft.status)

            vm.retryGeneration()
            advanceUntilIdle()

            assertEquals(Status.PROPOSED, vm.draft.status)
            assertNull(vm.draft.generationError)
            assertEquals(2, engine.calls)
            // A deliberate retry is a new request, not a replay: the backend must generate
            // again rather than hand back whatever the failed attempt left behind.
            assertEquals(2, engine.requestIds.distinct().size)
        }

    @Test
    fun `going back from the failure screen returns to blockers and clears the error`() =
        runTest(mainDispatcher.dispatcher) {
            val vm = launchApp(engineFailing(EngineError.FORMAT_FAILED))
            generate(vm)

            assertTrue(vm.back())
            assertEquals(3, vm.step)
            assertEquals(Status.DRAFT, vm.draft.status)
            assertNull(vm.draft.generationError)
        }

    @Test
    fun `back during generation cancels the request`() =
        runTest(mainDispatcher.dispatcher) {
            val gate = CompletableDeferred<Unit>()
            val engine = FakeEngine(mutableListOf(MesocycleResult.Ok(samplePlan())), gate)
            val vm = launchApp(engine)

            vm.continueFromStep(1)
            vm.continueFromStep(2)
            vm.continueFromStep(3)
            assertEquals(Status.GENERATING, vm.draft.status)

            assertTrue(vm.back())
            advanceUntilIdle()

            assertTrue("the engine saw the cancellation", engine.cancelled)
            assertEquals(Status.DRAFT, vm.draft.status)
            assertNull(vm.draft.plan)
        }

    @Test
    fun `a payload that cannot be assembled fails before any engine call`() =
        runTest(mainDispatcher.dispatcher) {
            // A store that loses the profile: assembly must name the gap rather than send a
            // partial athlete to the engine.
            val lossy = object : ArtifactRepository by repository() {
                override fun readProfile(): AthleteProfileArtifact? = null
            }
            val engine = engineReturning()
            val vm = launchApp(engine, lossy)
            generate(vm)

            assertEquals(Status.FAILED, vm.draft.status)
            assertEquals(EngineError.PAYLOAD_INVALID, vm.draft.generationError)
            assertEquals(0, engine.calls)
        }

    // --- Story 4: persistence and the write order ----------------------------

    @Test
    fun `approval writes the plan before the lock`() =
        runTest(mainDispatcher.dispatcher) {
            val order = mutableListOf<String>()
            val recording = object : ArtifactRepository by repository() {
                private val inner = repository()
                override fun writeMesocyclePlan(plan: MesocyclePlanArtifact) {
                    order += "plan"
                    inner.writeMesocyclePlan(plan)
                }
                override fun readMesocyclePlan() = inner.readMesocyclePlan()
                override fun lockGoals(lock: GoalsLock): AthleteGoalsArtifact {
                    order += "lock"
                    return inner.lockGoals(lock)
                }
            }
            val vm = launchApp(repository = recording)
            generate(vm)
            vm.approve()

            // The lock is the commit point, so it must be the *last* thing to land: a crash
            // between the two leaves an unapproved plan, never an approval with no plan.
            assertEquals(listOf("plan", "lock"), order)
            assertEquals(Status.APPROVED, vm.draft.status)
        }

    @Test
    fun `the lock is minted from the persisted plan, not the one in memory`() =
        runTest(mainDispatcher.dispatcher) {
            val plan = samplePlan(startDate = TODAY.plusDays(9), durationWeeks = 5)
            val vm = launchApp(engineReturning(plan))
            generate(vm)
            vm.approve()

            val onDisk = repository().readMesocyclePlan()!!
            val lock = vm.goalsLock!!
            assertEquals(onDisk.startDate, lock.startDate)
            assertEquals(onDisk.endDate, lock.endDate)
            assertEquals(TODAY.plusDays(9), lock.startDate)
        }

    @Test
    fun `the persisted plan survives a restart intact`() =
        runTest(mainDispatcher.dispatcher) {
            val plan = samplePlan(durationWeeks = 5, lane = Lane.PERFORMANCE)
            val vm = launchApp(engineReturning(plan))
            vm.setLane(Lane.PERFORMANCE)
            generate(vm)
            vm.approve()

            Restart.simulate()
            val relaunched = launchApp()

            val restored = relaunched.draft.plan!!
            assertEquals(plan.copy(updatedAt = restored.updatedAt), restored)
            assertEquals(Status.APPROVED, relaunched.draft.status)
            assertNotNull(restored.updatedAt)
        }

    @Test
    fun `a plan with no goals lock is an abandoned attempt, not an approval`() =
        runTest(mainDispatcher.dispatcher) {
            val vm = launchApp()
            generate(vm)
            // Approval never happened, but a plan reaches disk anyway — simulating the
            // process dying between the two writes.
            repository().writeMesocyclePlan(vm.draft.plan!!)
            assertNotNull(repository().readMesocyclePlan())

            Restart.simulate()
            val relaunched = launchApp()

            assertEquals(Status.DRAFT, relaunched.draft.status)
            assertEquals(1, relaunched.step)
            assertNull("the half-state plan is not loaded", relaunched.draft.plan)
            assertNull(relaunched.approvedSummary)
        }

    @Test
    fun `the next generation clears an abandoned plan`() =
        runTest(mainDispatcher.dispatcher) {
            val first = launchApp()
            generate(first)
            repository().writeMesocyclePlan(first.draft.plan!!)

            Restart.simulate()
            val second = launchApp(engineFailing(EngineError.TIMEOUT))
            generate(second)

            // The generation failed, and the stale plan went with the attempt that replaced
            // it — nothing is left on disk pretending to be current.
            assertEquals(Status.FAILED, second.draft.status)
            assertNull(repository().readMesocyclePlan())
        }

    @Test
    fun `a cycle approved before the plan artifact existed still reads as approved`() =
        runTest(mainDispatcher.dispatcher) {
            val vm = launchApp()
            generate(vm)
            vm.approve()
            // An Epic 1 store: the lock is there, the plan artifact never was.
            File(dir(), "mesocycle-plan.json").delete()

            Restart.simulate()
            val relaunched = launchApp()

            // The lock is the commit point, so an approval it recorded is not un-approved by
            // a missing plan. The screen falls back to what the lock carries.
            assertEquals(Status.APPROVED, relaunched.draft.status)
            assertNull(relaunched.draft.plan)
            assertEquals(vm.goalsLock!!.startDate, relaunched.approvedSummary!!.startDate)
        }

    @Test
    fun `approval is refused when the plan cannot be written`() =
        runTest(mainDispatcher.dispatcher) {
            val failing = object : ArtifactRepository by repository() {
                override fun writeMesocyclePlan(plan: MesocyclePlanArtifact) =
                    throw com.cadentic.app.domain.artifacts.ArtifactException(
                        com.cadentic.app.domain.artifacts.ArtifactError.WriteFailed(
                            com.cadentic.app.domain.artifacts.ArtifactId.MESOCYCLE_PLAN,
                            "disk full",
                        ),
                    )
            }
            val vm = launchApp(repository = failing)
            generate(vm)
            vm.approve()

            // No lock without a plan under it: the athlete stays on the proposal rather than
            // seeing a confirmation nothing backs.
            assertEquals(Status.PROPOSED, vm.draft.status)
            assertNull(vm.goalsLock)
            assertNull(repository().readGoals()!!.lockedForCycle)
        }

    @Test
    fun `approval initializes the progression log exactly once`() =
        runTest(mainDispatcher.dispatcher) {
            val vm = launchApp()
            generate(vm)
            vm.approve()
            assertNotNull(repository().readProgressionLog())
            assertTrue(repository().readProgressionLog()!!.entries.isEmpty())
        }
}
