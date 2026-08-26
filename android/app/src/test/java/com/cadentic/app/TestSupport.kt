package com.cadentic.app

import com.cadentic.app.data.JsonArtifactRepository
import com.cadentic.app.domain.Ids
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

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val dispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) = Dispatchers.resetMain()
}
