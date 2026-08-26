package com.cadentic.app

import com.cadentic.app.data.JsonArtifactRepository
import com.cadentic.app.domain.Category
import com.cadentic.app.domain.EngineError
import com.cadentic.app.domain.Ids
import com.cadentic.app.domain.Lane
import com.cadentic.app.domain.MesocycleEngine
import com.cadentic.app.domain.MesocycleRequest
import com.cadentic.app.domain.MesocycleResult
import com.cadentic.app.domain.Strain
import com.cadentic.app.domain.artifacts.DayType
import com.cadentic.app.domain.artifacts.GeneratedBy
import com.cadentic.app.domain.artifacts.GenerationMode
import com.cadentic.app.domain.artifacts.MesocyclePhase
import com.cadentic.app.domain.artifacts.MesocyclePlanArtifact
import com.cadentic.app.domain.artifacts.PhaseType
import com.cadentic.app.domain.artifacts.PlannedDay
import com.cadentic.app.domain.artifacts.PlannedWeek
import com.cadentic.app.domain.artifacts.Progression
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.File
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Process restart is simulated the way it actually happens: everything in memory goes —
 * a fresh repository, a fresh ViewModel, and the process-wide [Ids] counter back at zero —
 * while the artifact directory on disk stays exactly as it was. That is the whole of what
 * survives a real kill, so a test that passes here passes for the same reason the app does.
 */
object Restart {
    fun simulate() = Ids.resetForTests()
}

val FIXED_CLOCK: Clock = Clock.fixed(Instant.parse("2026-08-26T09:00:00Z"), ZoneOffset.UTC)
val TODAY: LocalDate = LocalDate.of(2026, 8, 26)

fun repositoryIn(dir: File, clock: Clock = FIXED_CLOCK) = JsonArtifactRepository(dir, clock)

// --- Mesocycle Engine test doubles (Epic 2) ------------------------------------------

private val WEEK = DayOfWeek.entries.sortedBy { it.value }

/**
 * A plan shaped like one the engine really returns: eight weeks, five training days and two
 * REST in every week, and a deload before the peak. Built rather than pasted so a schema
 * change surfaces here instead of rotting quietly.
 */
fun samplePlan(
    startDate: LocalDate = TODAY.plusDays(6),
    durationWeeks: Int = 8,
    lane: Lane = Lane.LONGEVITY,
    focus: List<Category> = listOf(Category.CARDIO, Category.EXPLOSIVENESS),
    queued: List<Category> = listOf(Category.STRENGTH),
): MesocyclePlanArtifact = MesocyclePlanArtifact(
    generatedBy = GeneratedBy(GenerationMode.MAX_PLAN_OAUTH, "claude-opus-5", promptVersion = 1),
    startDate = startDate,
    endDate = startDate.plusWeeks(durationWeeks.toLong()).minusDays(1),
    durationWeeks = durationWeeks,
    sessionsPerWeek = 5,
    lane = lane,
    focus = focus,
    queued = queued,
    phases = listOf(
        MesocyclePhase(PhaseType.BASE, "Base", 3),
        MesocyclePhase(PhaseType.BUILD, "Build", 3),
        MesocyclePhase(PhaseType.DELOAD, "Deload", 1),
        MesocyclePhase(PhaseType.PEAK, "Peak", 1),
    ),
    weeklyStructure = (1..durationWeeks).map { w ->
        PlannedWeek(
            week = w,
            days = WEEK.mapIndexed { i, day ->
                val rest = i == 2 || i == 6
                PlannedDay(
                    day = day,
                    type = if (rest) DayType.REST else if (i % 2 == 0) DayType.STRENGTH else DayType.ENDURANCE,
                    intensity = if (rest) null else if (i == 4) Strain.HARD else Strain.MEDIUM,
                )
            },
        )
    },
    progression = Progression(
        intraWeek = "Hardest day mid-week, easing into the weekend.",
        interWeek = "Volume climbs for three weeks, then steps back.",
    ),
)

/**
 * Answers from a script. [requestIds] records what the app sent, so the request-id rule can
 * be asserted without a socket.
 */
class FakeEngine(
    private val answers: MutableList<MesocycleResult> = mutableListOf(),
    /** Suspends until released, for testing cancellation and the GENERATING state. */
    private val gate: CompletableDeferred<Unit>? = null,
) : MesocycleEngine {

    val requestIds = mutableListOf<String>()
    var calls = 0
        private set
    var cancelled = false
        private set

    constructor(vararg answers: MesocycleResult) : this(answers.toMutableList())

    override suspend fun generate(request: MesocycleRequest): MesocycleResult {
        calls += 1
        requestIds += request.requestId
        if (gate != null) {
            try {
                gate.await()
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
        }
        return answers.removeFirstOrNull()
            ?: error("FakeEngine ran out of scripted answers (call $calls)")
    }
}

fun engineReturning(plan: MesocyclePlanArtifact = samplePlan()) =
    FakeEngine(MesocycleResult.Ok(plan))

fun engineFailing(error: EngineError) = FakeEngine(MesocycleResult.Failed(error))

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val dispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) = Dispatchers.resetMain()
}
