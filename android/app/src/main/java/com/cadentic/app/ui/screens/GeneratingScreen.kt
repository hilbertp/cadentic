package com.cadentic.app.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cadentic.app.OnboardingViewModel
import com.cadentic.app.ui.theme.Ink
import com.cadentic.app.ui.theme.Type
import com.cadentic.app.ui.theme.sans
import kotlinx.coroutines.delay
import kotlin.math.exp
import kotlin.math.min

/**
 * The wait (Epic 2 story 5). Generation is a single call that runs for around two minutes,
 * and **the engine reports no progress while it works** — there is no stream, no stage
 * callback, nothing to read a percentage from.
 *
 * So the bar here is honest about what it is. The only real signal is elapsed time, which is
 * shown as a number; the bar is a *pace* against how long this usually takes, and it eases
 * asymptotically toward a ceiling it never reaches. A bar that fills to 100% and then sits
 * there is the standard lie of this screen, and it is the one thing this must not do — when
 * it is nearly full, the honest reading is "longer than usual", and the copy says exactly
 * that rather than pretending the work is nearly done.
 */
@Composable
fun GeneratingScreen(vm: OnboardingViewModel) {
    val startedAt = vm.generationStartedAt

    var elapsedMs by remember(startedAt) { mutableLongStateOf(0L) }
    LaunchedEffect(startedAt) {
        val start = startedAt?.toEpochMilli() ?: System.currentTimeMillis()
        while (true) {
            // Wall clock, not an accumulator: backgrounding the app pauses this coroutine,
            // and on return the count must reflect real time passed, not frames rendered.
            elapsedMs = (System.currentTimeMillis() - start).coerceAtLeast(0L)
            delay(TICK_MS)
        }
    }

    val target = generationProgress(elapsedMs)
    val progress by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = TICK_MS.toInt() * 2),
        label = "progress",
    )

    Column(
        Modifier.fillMaxSize().background(Ink.screenBg).padding(horizontal = 60.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("CADENTIC", style = Type.wordmark)

        Box(
            Modifier.padding(top = 20.dp)
                .width(200.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Ink.hairline),
        ) {
            Box(
                Modifier.fillMaxWidth(progress)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(Ink.accent),
            )
        }

        Text(
            elapsedLabel(elapsedMs),
            style = sans(11.5.sp, color = Ink.secondary),
            modifier = Modifier.padding(top = 10.dp),
        )

        Text(
            "Building your mesocycle around what's really there.",
            style = Type.intro(13.5.sp).copy(textAlign = TextAlign.Center),
            modifier = Modifier.padding(top = 20.dp),
        )
        Text(
            if (elapsedMs >= SLOW_AFTER_MS) {
                "Taking longer than usual — still working."
            } else {
                "Usually about two minutes."
            },
            style = sans(12.sp, color = Ink.secondary).copy(textAlign = TextAlign.Center),
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

private const val TICK_MS = 250L

/** Past this the wait is atypical, and the copy stops implying it is nearly done. */
internal const val SLOW_AFTER_MS = 180_000L

/** Calibrated so a typical ~2-minute generation reads about four-fifths of the way along. */
private const val TAU_MS = 75_000.0

/** The bar never reaches the end, because reaching the end would be a claim we cannot make. */
internal const val PROGRESS_CEILING = 0.94f

/**
 * Asymptotic ease: fast at first, slower the longer it runs, never arriving. Deliberately not
 * linear against a deadline — a linear bar that hits the end while the request is still open
 * has to either freeze or lie, and both read as a hang.
 */
internal fun generationProgress(elapsedMs: Long): Float {
    if (elapsedMs <= 0L) return 0f
    return min(PROGRESS_CEILING, (1.0 - exp(-elapsedMs / TAU_MS)).toFloat())
}

/** `m:ss`, counting up. The one number on this screen that is simply true. */
internal fun elapsedLabel(elapsedMs: Long): String {
    val total = elapsedMs / 1000
    return "%d:%02d".format(total / 60, total % 60)
}
