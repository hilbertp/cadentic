package com.cadentic.app.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cadentic.app.ui.theme.Ink
import com.cadentic.app.ui.theme.Type
import com.cadentic.app.ui.theme.sans

/** Minimal by design (undesigned in the handoff): wordmark + progress + the promise. */
@Composable
fun GeneratingScreen() {
    val transition = rememberInfiniteTransition(label = "gen")
    val active by transition.animateFloat(
        initialValue = 0f, targetValue = 4f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "segment",
    )
    Column(
        Modifier.fillMaxSize().background(Ink.screenBg).padding(horizontal = 60.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("CADENTIC", style = Type.wordmark)
        Row(
            Modifier.padding(top = 20.dp).width(160.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            repeat(4) { i ->
                val on = active.toInt() % 4 == i
                Box(
                    Modifier.weight(1f).height(4.dp)
                        .alpha(if (on) 1f else 0.35f)
                        .background(if (on) Ink.accent else Ink.hairline, RoundedCornerShape(2.dp))
                )
            }
        }
        Text(
            "Building your mesocycle around what's really there.",
            style = Type.intro(13.5.sp),
            modifier = Modifier.padding(top = 24.dp),
        )
        Text(
            "Nothing locks until you approve it.",
            style = sans(12.sp, color = Ink.secondary),
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}
