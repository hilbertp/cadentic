package com.cadentic.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cadentic.app.OnboardingViewModel
import com.cadentic.app.domain.Proposal
import com.cadentic.app.domain.artifacts.PhaseType
import com.cadentic.app.domain.isDeload
import com.cadentic.app.domain.monthDay
import com.cadentic.app.ui.components.PrimaryCta
import com.cadentic.app.ui.components.StepScaffold
import com.cadentic.app.ui.components.dashedBorder
import com.cadentic.app.ui.theme.Ink
import com.cadentic.app.ui.theme.Type
import com.cadentic.app.ui.theme.sans

@Composable
fun ProposalScreen(vm: OnboardingViewModel) {
    val proposal = vm.draft.proposal ?: return

    StepScaffold(
        step = 4,
        cta = {
            Column {
                PrimaryCta("Approve mesocycle") { vm.approve() }
                // 44dp-min touch container; the text keeps its 12dp visual gap below the CTA.
                Box(
                    Modifier.fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .clickable { vm.askForChanges() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Ask for changes",
                        style = sans(13.5.sp, FontWeight.SemiBold).copy(
                            textDecoration = TextDecoration.Underline,
                            textAlign = TextAlign.Center,
                        ),
                    )
                }
            }
        },
    ) {
        Text(
            "PROPOSAL — REVIEW & APPROVE",
            style = sans(10.5.sp, FontWeight.SemiBold, letterSpacing = 1.6.sp, color = Ink.accentDeep),
            modifier = Modifier.padding(top = 24.dp, bottom = 6.dp),
        )
        // Headline follows the chosen lane — "engine first" contradicts a performance cycle.
        Text(proposal.headline, style = Type.h1(25.sp), modifier = Modifier.padding(bottom = 6.dp))
        Text(
            "${proposal.startDate.monthDay()} → ${proposal.endDate.monthDay()} · " +
                "${proposal.sessionsPerWeek} sessions / wk · ${proposal.laneLabel}",
            style = sans(13.sp, color = Ink.secondary),
            modifier = Modifier.padding(bottom = 16.dp),
        )

        PhaseTimeline(proposal)

        Column(
            Modifier.fillMaxWidth()
                .padding(top = 18.dp, bottom = 12.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Ink.surface)
                .border(1.dp, Ink.hairline, RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Text(coachNoteText(proposal.coachNote), style = sans(13.sp, lineHeight = 13.sp * 1.5f))
        }
    }
}

private fun coachNoteText(note: String) = buildAnnotatedString {
    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Ink.accentDeep)) { append("Coach's note. ") }
    append(note)
}

@Composable
private fun PhaseTimeline(proposal: Proposal) {
    Row(Modifier.fillMaxWidth().height(46.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        proposal.phases.forEach { phase ->
            // Width is proportional to weeks, with a floor so a 1-week segment still fits its label.
            val weight = phase.weeks.toFloat().coerceAtLeast(1.4f)
            // Switched on phaseType, never on the name: the name is whatever the engine chose
            // to call the phase, and a plan that says "Foundation" instead of "Base" must
            // still colour and dash correctly.
            val (bg, nameColor, weekColor) = when (phase.phaseType) {
                PhaseType.BASE -> Triple(Ink.faintFill7, Ink.primary, Ink.secondary)
                PhaseType.BUILD -> Triple(Ink.accentTint18, Ink.accentDeep, Ink.accentDeep)
                PhaseType.PEAK -> Triple(Ink.accentTint38, Ink.accentDeeper, Ink.accentDeeper)
                PhaseType.DELOAD -> Triple(Ink.surface, Ink.accentDeep, Ink.accentDeep)
            }
            val isDeload = phase.phaseType.isDeload
            Column(
                Modifier.weight(weight)
                    .fillMaxSize()
                    .clip(RoundedCornerShape(10.dp))
                    .background(bg)
                    .then(
                        if (isDeload) Modifier.dashedBorder(Ink.accent, 10.dp)
                        else Modifier
                    ),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // One line each, ellipsised. The engine picks these names now, and a long
                // one on a one-week phase used to wrap and push the week label out of the
                // 46dp row entirely. The contract caps the length; this makes sure an
                // unusually wide name degrades to "Power buil…" rather than breaking the row.
                Text(
                    phase.name,
                    style = sans(if (isDeload) 11.sp else 12.sp, FontWeight.SemiBold, color = nameColor),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 3.dp),
                )
                Text(
                    phase.weeksLabel,
                    style = sans(10.5.sp, color = weekColor),
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
            }
        }
    }
}
