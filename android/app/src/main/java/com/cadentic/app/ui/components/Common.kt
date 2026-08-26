package com.cadentic.app.ui.components

import androidx.compose.animation.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cadentic.app.ui.theme.Ink
import com.cadentic.app.ui.theme.Type

/** Header on every screen: wordmark left, "N / 4" right, 4 progress segments below. */
@Composable
fun CadenticHeader(step: Int) {
    Column {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text("CADENTIC", style = Type.wordmark)
            Text("$step / 4", style = Type.stepCounter)
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            repeat(4) { i ->
                // The newly-earned segment animates in on step entry (~250ms ease-out);
                // each screen is a fresh composition, so start it from the unfilled color.
                val target = if (i < step) Ink.accent else Ink.hairline
                val color = remember { Animatable(if (i == step - 1) Ink.hairline else target) }
                LaunchedEffect(Unit) { color.animateTo(target, tween(250, easing = EaseOut)) }
                Box(
                    Modifier.weight(1f).height(4.dp)
                        .background(color.value, RoundedCornerShape(2.dp))
                )
            }
        }
    }
}

/** Screen frame: 22dp horizontal padding, scrollable content column, CTA pinned to the bottom. */
@Composable
fun StepScaffold(
    step: Int,
    cta: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier.fillMaxSize()
            .background(Ink.screenBg)
            .statusBarsPadding()
            .navigationBarsPadding()
            // edge-to-edge means adjustResize no longer resizes for us — lift content off the keyboard.
            .imePadding()
            .padding(horizontal = 22.dp)
            .padding(top = 10.dp, bottom = 20.dp)
    ) {
        CadenticHeader(step)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            content = content,
        )
        cta()
    }
}

@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 11.sp,
    tracking: TextUnit = 1.2.sp,
) {
    Text(text.uppercase(), style = Type.sectionLabel(size, tracking), modifier = modifier)
}

/** White card with hairline border, default radius 14. */
@Composable
fun SurfaceCard(
    modifier: Modifier = Modifier,
    radius: Dp = 14.dp,
    borderColor: Color = Ink.hairline,
    borderWidth: Dp = 1.dp,
    background: Color = Ink.surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(radius))
            .background(background)
            .border(borderWidth, borderColor, RoundedCornerShape(radius)),
        content = content,
    )
}

/** Full-width pill CTA; disabled = 60% opacity accent (handoff assumption). */
@Composable
fun PrimaryCta(label: String, enabled: Boolean = true, height: Dp = 54.dp, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(999.dp))
            .background(if (enabled) Ink.accent else Ink.accent.copy(alpha = 0.6f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = Type.cta)
    }
}

@Composable
fun HelperText(text: String, modifier: Modifier = Modifier) {
    Text(text, style = Type.helper, modifier = modifier.padding(top = 6.dp))
}

fun Modifier.dashedBorder(color: Color, radius: Dp, strokeWidth: Dp = 1.5.dp): Modifier =
    drawBehind {
        val inset = strokeWidth.toPx() / 2f
        drawRoundRect(
            color = color,
            topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
            size = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2),
            cornerRadius = CornerRadius(radius.toPx()),
            style = Stroke(
                width = strokeWidth.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()), 0f),
            ),
        )
    }
