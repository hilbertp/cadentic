package com.cadentic.app

import com.cadentic.app.ui.screens.PROGRESS_CEILING
import com.cadentic.app.ui.screens.SLOW_AFTER_MS
import com.cadentic.app.ui.screens.elapsedLabel
import com.cadentic.app.ui.screens.generationProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The generating screen's two derived values. The engine reports no progress while it works,
 * so the bar is a pace estimate and the clock is the only true number — these tests pin the
 * one property that matters: the bar must never claim the work is finished.
 */
class GeneratingProgressTest {

    @Test
    fun `progress never reaches the end, however long it runs`() {
        // A bar that fills completely while the request is still open has to freeze or lie.
        listOf(0L, 60_000L, 120_000L, 300_000L, 3_600_000L, Long.MAX_VALUE / 2).forEach {
            assertTrue("at ${it}ms", generationProgress(it) <= PROGRESS_CEILING)
        }
        assertEquals(PROGRESS_CEILING, generationProgress(3_600_000L), 0.001f)
    }

    @Test
    fun `it starts empty and only ever moves forward`() {
        assertEquals(0f, generationProgress(0L), 0.0001f)
        assertEquals(0f, generationProgress(-5_000L), 0.0001f)
        var previous = 0f
        for (s in 0..600) {
            val p = generationProgress(s * 1000L)
            assertTrue("regressed at ${s}s", p >= previous)
            previous = p
        }
    }

    @Test
    fun `a typical two-minute generation reads as most of the way along`() {
        // Calibrated against the real thing: live runs land around 120s.
        val p = generationProgress(120_000L)
        assertTrue("was $p", p > 0.7f && p < 0.85f)
    }

    @Test
    fun `the copy switches to honest waiting past three minutes`() {
        assertEquals(180_000L, SLOW_AFTER_MS)
        assertTrue(generationProgress(SLOW_AFTER_MS) > 0.85f)
    }

    @Test
    fun `the clock reads as minutes and seconds`() {
        assertEquals("0:00", elapsedLabel(0L))
        assertEquals("0:07", elapsedLabel(7_400L))
        assertEquals("0:59", elapsedLabel(59_999L))
        assertEquals("1:00", elapsedLabel(60_000L))
        assertEquals("2:13", elapsedLabel(133_000L))
        assertEquals("12:00", elapsedLabel(720_000L))
    }
}
