package com.cadentic.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cadentic.app.OnboardingViewModel
import com.cadentic.app.domain.Status
import com.cadentic.app.ui.screens.ApprovedScreen
import com.cadentic.app.ui.screens.BaselineScreen
import com.cadentic.app.ui.screens.BlockersScreen
import com.cadentic.app.ui.screens.GeneratingScreen
import com.cadentic.app.ui.screens.PrioritiesScreen
import com.cadentic.app.ui.screens.ProposalScreen
import com.cadentic.app.ui.theme.Ink
import com.cadentic.app.ui.theme.sans

// Ordinal per screen so transitions know which way to slide.
private fun screenKey(vm: OnboardingViewModel): Int = when {
    vm.draft.status == Status.APPROVED -> 6
    vm.draft.status == Status.GENERATING -> 4
    vm.draft.status == Status.PROPOSED && vm.step == 4 -> 5
    else -> vm.step
}

@Composable
fun OnboardingApp(vm: OnboardingViewModel) {
    val snackbarHost = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        vm.snackbarFlow.collect { snackbarHost.showSnackbar(it) }
    }

    BackHandler(enabled = screenKey(vm) > 1 && vm.draft.status != Status.APPROVED) {
        vm.back()
    }

    Box(Modifier.fillMaxSize().background(Ink.screenBg)) {
        AnimatedContent(
            targetState = screenKey(vm),
            transitionSpec = {
                val forward = targetState > initialState
                val slide = if (forward) 1 else -1
                (fadeIn(tween(220)) + slideInHorizontally(tween(260)) { slide * it / 10 })
                    .togetherWith(fadeOut(tween(160)) + slideOutHorizontally(tween(260)) { -slide * it / 10 })
            },
            label = "step",
        ) { key ->
            when (key) {
                1 -> BaselineScreen(vm)
                2 -> PrioritiesScreen(vm)
                3 -> BlockersScreen(vm)
                4 -> GeneratingScreen()
                5 -> ProposalScreen(vm)
                else -> ApprovedScreen(vm)
            }
        }
        SnackbarHost(
            hostState = snackbarHost,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 90.dp),
        ) { data ->
            Snackbar(
                snackbarData = data,
                containerColor = Ink.primary,
                contentColor = Ink.screenBg,
            )
        }
    }
}
