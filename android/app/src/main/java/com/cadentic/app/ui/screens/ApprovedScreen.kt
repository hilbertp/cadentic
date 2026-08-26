package com.cadentic.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cadentic.app.OnboardingViewModel
import com.cadentic.app.domain.monthDay
import com.cadentic.app.ui.theme.Ink
import com.cadentic.app.ui.theme.Type
import com.cadentic.app.ui.theme.sans

/**
 * Post-approval exit (onboarding ends here per PRD §8). The weekly overview
 * is the next milestone — this is a deliberate placeholder, not a designed screen.
 */
@Composable
fun ApprovedScreen(vm: OnboardingViewModel) {
    val proposal = vm.draft.proposal ?: return
    Column(
        Modifier.fillMaxSize().background(Ink.screenBg).padding(horizontal = 40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(56.dp).clip(CircleShape).background(Ink.accentTint),
            contentAlignment = Alignment.Center,
        ) {
            Text("✓", style = sans(24.sp, FontWeight.Bold, color = Ink.accentDeep))
        }
        Text("Mesocycle locked.", style = Type.h1(24.sp), modifier = Modifier.padding(top = 20.dp))
        Text(
            "Base phase starts ${proposal.startDate.monthDay()}. " +
                "This cycle: ${proposal.focusThisCycle.joinToString(" + ") { it.priorityTitle }}." +
                (proposal.queuedForLater.takeIf { it.isNotEmpty() }
                    ?.let { " ${it.joinToString(", ") { c -> c.priorityTitle }} waits for the next one." } ?: ""),
            style = Type.intro(13.5.sp).copy(textAlign = TextAlign.Center),
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            "Structure is fixed until the cycle ends — priorities only move between cycles.",
            style = sans(11.5.sp, color = Ink.secondary).copy(textAlign = TextAlign.Center),
            modifier = Modifier.padding(top = 14.dp),
        )
    }
}
