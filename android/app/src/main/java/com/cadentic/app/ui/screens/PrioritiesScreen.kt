package com.cadentic.app.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.cadentic.app.OnboardingViewModel
import com.cadentic.app.domain.Category
import com.cadentic.app.domain.MAX_FOCUS_COUNT
import com.cadentic.app.domain.Lane
import com.cadentic.app.ui.components.HelperText
import com.cadentic.app.ui.components.PrimaryCta
import com.cadentic.app.ui.components.SectionLabel
import com.cadentic.app.ui.components.StepScaffold
import com.cadentic.app.ui.components.SurfaceCard
import com.cadentic.app.ui.components.dashedBorder
import com.cadentic.app.ui.theme.Ink
import com.cadentic.app.ui.theme.Type
import com.cadentic.app.ui.theme.sans
import com.cadentic.app.ui.theme.sora
import kotlin.math.roundToInt

private val ROW_HEIGHT = 60.dp
private val DIVIDER_HEIGHT = 26.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrioritiesScreen(vm: OnboardingViewModel) {
    val draft = vm.draft
    var addingInjury by remember { mutableStateOf(false) }

    StepScaffold(
        step = 2,
        cta = { PrimaryCta("Continue", enabled = vm.stepValid(), height = 52.dp) { vm.continueFromStep(2) } },
    ) {
        Text("What matters, in order.", style = Type.h1(24.sp), modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
        Text(
            "Rank it, flag what's fragile, pick your lane. No essay needed.",
            style = Type.intro(13.5.sp), modifier = Modifier.padding(bottom = 14.dp),
        )

        Row(
            Modifier.fillMaxWidth().padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionLabel("Priorities — drag to order")
            // Only meaningful once there is more than one thing to choose between.
            if (draft.priorities.size > 1) {
                FocusCountToggle(draft.effectiveFocusCount, vm::setFocusCount)
            }
        }
        PriorityList(vm)
        HelperText(priorityHelper(draft.effectiveFocusCount, draft.queued))

        SectionLabel("Your lane", Modifier.padding(top = 14.dp, bottom = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Lane.entries.forEach { lane ->
                val selected = draft.lane == lane
                Column(
                    Modifier.weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (selected) Ink.accentTint else Ink.surface)
                        .border(
                            if (selected) 1.5.dp else 1.dp,
                            if (selected) Ink.accent else Ink.hairline,
                            RoundedCornerShape(14.dp),
                        )
                        .clickable { vm.setLane(lane) }
                        .padding(horizontal = 13.dp, vertical = 11.dp),
                ) {
                    Text(
                        lane.title,
                        style = sans(13.5.sp, FontWeight.SemiBold, color = if (selected) Ink.accentDeep else Ink.primary),
                    )
                    Text(
                        lane.subtitle,
                        style = sans(11.5.sp, lineHeight = 11.5.sp * 1.4f, color = if (selected) Ink.accentDeep else Ink.secondary),
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
        }

        SectionLabel("Injuries & limitations", Modifier.padding(top = 14.dp, bottom = 6.dp))
        InjuryChips(
            injuries = draft.injuries,
            onRemove = vm::removeInjury,
            onAdd = { addingInjury = true },
        )
        HelperText("Chronic, healing, or structural — the plan routes around these, permanently.")
    }

    if (addingInjury) {
        val sheetState = rememberModalBottomSheetState()
        var text by remember { mutableStateOf("") }
        ModalBottomSheet(
            onDismissRequest = { addingInjury = false },
            sheetState = sheetState,
            containerColor = Ink.screenBg,
        ) {
            Column(Modifier.padding(horizontal = 22.dp).padding(bottom = 28.dp)) {
                Text("Add an injury or limitation", style = Type.h1(20.sp), modifier = Modifier.padding(bottom = 4.dp))
                Text(
                    "Name it the way a physio would — joint, side, what it doesn't tolerate.",
                    style = Type.intro(13.5.sp), modifier = Modifier.padding(bottom = 14.dp),
                )
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Ink.surface)
                        .border(1.dp, Ink.hairline, RoundedCornerShape(14.dp))
                        .padding(horizontal = 16.dp, vertical = 13.dp),
                ) {
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        textStyle = Type.fieldValue,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Box(Modifier.padding(top = 18.dp)) {
                    PrimaryCta("Add", enabled = text.isNotBlank(), height = 52.dp) {
                        vm.addInjury(text)
                        addingInjury = false
                    }
                }
            }
        }
    }
}

private fun priorityHelper(focusCount: Int, queued: List<Category>): String {
    val names = queued.map { it.priorityTitle }
    val list = when (names.size) {
        0 -> ""
        1 -> names.first()
        else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
    }
    val waits = if (names.size == 1) "waits" else "wait"
    return when {
        names.isEmpty() && focusCount == 1 -> "This gets programmed now — nothing waits."
        names.isEmpty() -> "Both of these get programmed now — nothing waits."
        focusCount == 1 ->
            "One focus this cycle — the narrowest, fastest way to move it. $list $waits for a later cycle."
        names.size == 1 ->
            "The top two get real attention now. A third dilutes all of them — $list rejoins in a later cycle."
        else ->
            "The top two get real attention now. More than that dilutes all of them — $list rejoin in later cycles."
    }
}

/** How many priorities get programmed this cycle. One focus is a valid, sharper choice. */
@Composable
private fun FocusCountToggle(selected: Int, onSelect: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(
            "THIS CYCLE",
            style = sans(10.sp, FontWeight.SemiBold, letterSpacing = 1.sp, color = Ink.secondary),
        )
        Row(
            Modifier.clip(RoundedCornerShape(999.dp))
                .background(Ink.surface)
                .border(1.dp, Ink.hairline, RoundedCornerShape(999.dp))
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            (1..MAX_FOCUS_COUNT).forEach { n ->
                val isSelected = n == selected
                Box(
                    Modifier.size(width = 42.dp, height = 38.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (isSelected) Ink.accentTint else Color.Transparent)
                        .then(
                            if (isSelected) Modifier.border(1.5.dp, Ink.accent, RoundedCornerShape(999.dp))
                            else Modifier
                        )
                        .clickable { onSelect(n) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "$n",
                        style = if (isSelected) sora(13.sp, FontWeight.SemiBold, color = Ink.accentDeep)
                        else sora(13.sp, FontWeight.Medium, color = Ink.secondary),
                    )
                }
            }
        }
    }
}

/**
 * Long-press-drag reorder. Rows are absolutely positioned in fixed slots; the focus-line
 * divider sits after the last focused slot. Reorder happens live while dragging; the
 * focus rule falls out of list order + the athlete's chosen focus count.
 */
@Composable
private fun PriorityList(vm: OnboardingViewModel) {
    val priorities = vm.draft.priorities
    val n = priorities.size
    val fc = vm.draft.effectiveFocusCount
    val showDivider = n > fc
    val density = LocalDensity.current
    val rowPx = with(density) { ROW_HEIGHT.toPx() }
    val divPx = with(density) { DIVIDER_HEIGHT.toPx() }

    fun slotY(index: Int): Float =
        if (!showDivider || index < fc) index * rowPx
        else fc * rowPx + divPx + (index - fc) * rowPx

    var dragged by remember { mutableStateOf<Category?>(null) }
    var dragStartIndex by remember { mutableStateOf(0) }
    var dragDelta by remember { mutableFloatStateOf(0f) }

    val totalHeight = ROW_HEIGHT * n + (if (showDivider) DIVIDER_HEIGHT else 0.dp)

    SurfaceCard {
        Box(Modifier.fillMaxWidth().height(totalHeight)) {
            if (showDivider) {
                FocusLine(
                    Modifier
                        .offset { IntOffset(0, (fc * rowPx).roundToInt()) }
                        .fillMaxWidth()
                        .height(DIVIDER_HEIGHT)
                )
            }
            priorities.forEachIndexed { index, category ->
                key(category) {
                val isDragged = dragged == category
                val restY = slotY(index).roundToInt()
                val animatedY by animateIntAsState(restY, tween(150), label = "slot-${category.name}")
                val y = if (isDragged) (slotY(dragStartIndex) + dragDelta).roundToInt() else animatedY
                val belowLine = index >= fc
                val rowAlpha by animateFloatAsState(if (belowLine) 0.55f else 1f, tween(200), label = "dim-${category.name}")

                PriorityRow(
                    category = category,
                    index = index,
                    belowLine = belowLine,
                    // Hairline between adjacent rows; the focus band replaces it at the boundary.
                    showBottomHairline = index < n - 1 && !(showDivider && index == fc - 1),
                    modifier = Modifier
                        .offset { IntOffset(0, y) }
                        .fillMaxWidth()
                        .height(ROW_HEIGHT)
                        .zIndex(if (isDragged) 1f else 0f)
                        .alpha(rowAlpha)
                        .then(
                            if (isDragged) Modifier
                                .shadow(6.dp, RoundedCornerShape(10.dp))
                                .background(Ink.surface)
                            else Modifier
                        )
                        .pointerInput(category) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    dragged = category
                                    dragStartIndex = vm.draft.priorities.indexOf(category)
                                    dragDelta = 0f
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragDelta += amount.y
                                    val current = vm.draft.priorities.indexOf(category)
                                    val visualCenter = slotY(dragStartIndex) + dragDelta + rowPx / 2
                                    val target = (0 until vm.draft.priorities.size).minByOrNull {
                                        kotlin.math.abs(slotY(it) + rowPx / 2 - visualCenter)
                                    } ?: current
                                    if (target != current) vm.movePriority(current, target)
                                },
                                onDragEnd = { dragged = null },
                                onDragCancel = { dragged = null },
                            )
                        },
                )
                }
            }
        }
    }
}

@Composable
private fun PriorityRow(
    category: Category,
    index: Int,
    belowLine: Boolean,
    showBottomHairline: Boolean,
    modifier: Modifier,
) {
    Column(modifier) {
        Row(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Drag handle: three 12×2 bars.
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                repeat(3) {
                    Box(Modifier.width(12.dp).height(2.dp).background(Ink.dragBar, RoundedCornerShape(1.dp)))
                }
            }
            Box(
                Modifier.size(20.dp).clip(CircleShape).background(Ink.faintFill7),
                contentAlignment = Alignment.Center,
            ) {
                Text("${index + 1}", style = sora(11.sp, FontWeight.SemiBold))
            }
            Column(Modifier.weight(1f)) {
                Text(category.priorityTitle, style = sans(14.sp, FontWeight.SemiBold))
                Text(category.prioritySubtitle, style = sans(11.5.sp, color = Ink.secondary))
            }
            if (belowLine) {
                Chip("later cycles", Ink.chipFaint, Ink.secondary)
            } else {
                Chip("this cycle", Ink.accentTint, Ink.accentDeep)
            }
        }
        if (showBottomHairline) Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.divider))
    }
}

@Composable
private fun Chip(label: String, bg: Color, fg: Color) {
    Box(Modifier.clip(RoundedCornerShape(999.dp)).background(bg).padding(horizontal = 10.dp, vertical = 4.dp)) {
        Text(label, style = sans(11.sp, FontWeight.SemiBold, color = fg))
    }
}

@Composable
private fun FocusLine(modifier: Modifier) {
    Row(
        modifier.background(Ink.focusBand).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DashedRule(Modifier.weight(1f))
        Text(
            "FOCUS LINE — BELOW WAITS",
            style = sans(10.sp, FontWeight.SemiBold, letterSpacing = 1.sp, color = Ink.secondary),
        )
        DashedRule(Modifier.weight(1f))
    }
}

@Composable
private fun DashedRule(modifier: Modifier) {
    Box(
        modifier.height(1.5.dp).drawBehind {
            val dash = 4.dp.toPx()
            var x = 0f
            while (x < size.width) {
                drawRect(
                    color = Color(0x33211E17), // dashed rule: rgba(33,30,23,.2)
                    topLeft = Offset(x, 0f),
                    size = Size(minOf(dash, size.width - x), size.height),
                )
                x += dash * 2
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InjuryChips(injuries: List<String>, onRemove: (String) -> Unit, onAdd: () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        injuries.forEach { injury ->
            // Remove is scoped to the × ("× removes"), with a full-height widened touch zone —
            // a stray tap on the label must not silently drop a permanent guardrail.
            Row(
                Modifier.clip(RoundedCornerShape(999.dp))
                    .background(Ink.surface)
                    .border(1.dp, Ink.hairline, RoundedCornerShape(999.dp))
                    .height(IntrinsicSize.Min),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    injury,
                    style = sans(12.5.sp, FontWeight.Medium),
                    modifier = Modifier.padding(start = 13.dp, top = 8.dp, bottom = 8.dp),
                )
                // Destructive control — widened to a real touch target, not just the glyph.
                Box(
                    Modifier.fillMaxHeight()
                        .clickable { onRemove(injury) }
                        .padding(start = 10.dp, end = 15.dp)
                        .widthIn(min = 20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("×", style = sans(15.sp, color = Ink.secondary))
                }
            }
        }
        Box(
            Modifier.clip(RoundedCornerShape(999.dp))
                .dashedBorder(Ink.dashedBorder, 999.dp)
                .clickable(onClick = onAdd)
                .padding(horizontal = 13.dp, vertical = 8.dp),
        ) {
            Text("+ Add", style = sans(12.5.sp, FontWeight.Medium, color = Ink.secondary))
        }
    }
}
