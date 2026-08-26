package com.cadentic.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cadentic.app.OnboardingViewModel
import com.cadentic.app.domain.EngineError
import com.cadentic.app.ui.components.PrimaryCta
import com.cadentic.app.ui.theme.Ink
import com.cadentic.app.ui.theme.Type
import com.cadentic.app.ui.theme.sans

/**
 * Generation failed (Epic 2 story 5). **Deliberately minimal** — this screen is undesigned in
 * the handoff, exactly like GeneratingScreen, and visual polish is out of scope. What it owes
 * the athlete is the truth about what happened and a way forward, and that is all it does.
 *
 * The message comes from [EngineError], so the app never renders a backend string: whatever
 * went wrong on the other side, the words here are the app's own.
 */
@Composable
fun GenerationFailedScreen(vm: OnboardingViewModel) {
    val error = vm.draft.generationError ?: EngineError.UNEXPECTED

    Column(
        Modifier.fillMaxSize().background(Ink.screenBg).padding(horizontal = 40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(56.dp).clip(CircleShape).background(Ink.surface),
            contentAlignment = Alignment.Center,
        ) {
            Text("!", style = sans(24.sp, FontWeight.Bold, color = Ink.accentDeep))
        }

        Text(
            "Couldn't build your mesocycle.",
            style = Type.h1(22.sp),
            modifier = Modifier.padding(top = 20.dp),
        )
        Text(
            error.message,
            style = Type.intro(13.5.sp).copy(textAlign = TextAlign.Center),
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            "Nothing was saved and nothing was locked.",
            style = sans(11.5.sp, color = Ink.secondary).copy(textAlign = TextAlign.Center),
            modifier = Modifier.padding(top = 14.dp),
        )

        Box(Modifier.padding(top = 28.dp).fillMaxWidth()) {
            PrimaryCta("Try again") { vm.retryGeneration() }
        }
        Box(
            Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { vm.back() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Back to my blockers",
                style = sans(13.5.sp, FontWeight.SemiBold).copy(
                    textDecoration = TextDecoration.Underline,
                    textAlign = TextAlign.Center,
                ),
            )
        }
    }
}
