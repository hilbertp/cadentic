package com.cadentic.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cadentic.app.OnboardingViewModel
import com.cadentic.app.domain.DayEntry
import com.cadentic.app.domain.OneOffBlocker
import com.cadentic.app.domain.RecurringBlocker
import com.cadentic.app.domain.Strain
import com.cadentic.app.domain.horizon
import com.cadentic.app.domain.monthDay
import com.cadentic.app.domain.monthShort
import com.cadentic.app.ui.components.PrimaryCta
import com.cadentic.app.ui.components.SectionLabel
import com.cadentic.app.ui.components.StepScaffold
import com.cadentic.app.ui.components.SurfaceCard
import com.cadentic.app.ui.components.dashedBorder
import com.cadentic.app.ui.theme.Ink
import com.cadentic.app.ui.theme.Type
import com.cadentic.app.ui.theme.sans
import com.cadentic.app.ui.theme.sora
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// Which bottom sheet is open. Day cells route here: a day with one entry opens that entity
// directly, a day with several opens the day sheet, an empty future day opens Add.
private sealed interface Sheet {
    data class Recurring(val id: Long) : Sheet
    data class Add(val prefillDate: LocalDate? = null) : Sheet
    data class OneOffDetail(val id: Long) : Sheet
    data class Day(val date: LocalDate) : Sheet
}

private val DAY_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.ENGLISH)
private val FULL_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.ENGLISH)

// Day cells are 40dp tall with a 4dp gap, so each hit rect clears the 44dp minimum.
private val CELL_HEIGHT = 40.dp
private val CELL_GAP = 4.dp
private val MONTH_COL = 30.dp

private fun strainDot(strain: Strain) = when (strain) {
    Strain.LIGHT -> Ink.strainLight
    Strain.MEDIUM -> Ink.strainMedium
    Strain.HARD -> Ink.strainHard
}

private fun DayEntry.kindLabel() = when (this) {
    is DayEntry.OneOff -> "one-off"
    is DayEntry.Practice -> "practice"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockersScreen(vm: OnboardingViewModel) {
    val draft = vm.draft
    val constraints = draft.constraints
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    val nothingScheduled = constraints.recurring.isEmpty() && constraints.oneOffs.isEmpty()

    StepScaffold(
        step = 3,
        cta = { PrimaryCta("Generate my mesocycle") { vm.continueFromStep(3) } },
    ) {
        Text("What must the plan respect?", style = Type.h1(25.sp), modifier = Modifier.padding(top = 20.dp, bottom = 6.dp))
        Text(
            if (nothingScheduled) "Nothing on the calendar yet. Add what the plan has to work around."
            else "Practices repeat weekly. One-offs land on a single date.",
            style = Type.intro(13.5.sp), modifier = Modifier.padding(bottom = 16.dp),
        )

        SectionLabel("Next 6 weeks", Modifier.padding(bottom = 8.dp), size = 10.5.sp, tracking = 1.4.sp)
        HorizonCard(vm, onOpen = { sheet = it })
        Text(
            "Tap any day — what's booked opens for review, a free day takes a new blocker.",
            style = sans(12.sp, lineHeight = 12.sp * 1.5f, color = Ink.secondary),
            modifier = Modifier.padding(top = 9.dp),
        )

        SectionLabel("Sources", Modifier.padding(top = 16.dp, bottom = 8.dp), size = 10.5.sp, tracking = 1.4.sp)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            constraints.recurring.forEach { blocker ->
                SourceRow(
                    dot = strainDot(blocker.strain),
                    title = blocker.label,
                    subtitle = "Every ${blocker.daysDisplay} · ${blocker.timeRange} · ${blocker.strain.label}",
                    action = "Edit",
                ) { sheet = Sheet.Recurring(blocker.id) }
            }
            constraints.oneOffs.forEach { blocker ->
                SourceRow(
                    dot = strainDot(blocker.strain),
                    title = blocker.label,
                    subtitle = blocker.date.format(DAY_DATE) + " · one-off · ${blocker.strain.label}",
                    action = "Edit",
                ) { sheet = Sheet.OneOffDetail(blocker.id) }
            }
            Box(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .dashedBorder(Ink.dashedBorder, 14.dp)
                    .clickable { sheet = Sheet.Add() }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("+ Add one-off or recurring", style = sans(13.5.sp, FontWeight.Medium, color = Ink.secondary))
            }
        }
    }

    // Resolve by id at render time so a sheet never edits a stale copy.
    when (val s = sheet) {
        is Sheet.Recurring -> constraints.recurring.firstOrNull { it.id == s.id }
            ?.let { RecurringSheet(vm, it) { sheet = null } } ?: run { sheet = null }

        is Sheet.Add -> AddBlockerSheet(vm, s.prefillDate) { sheet = null }

        is Sheet.OneOffDetail -> constraints.oneOffs.firstOrNull { it.id == s.id }
            ?.let { OneOffDetailSheet(vm, it) { sheet = null } } ?: run { sheet = null }

        is Sheet.Day -> DaySheet(vm, s.date, onOpen = { sheet = it }) { sheet = null }

        null -> {}
    }
}

@Composable
private fun HorizonCard(vm: OnboardingViewModel, onOpen: (Sheet) -> Unit) {
    val weeks = horizon(vm.draft.constraints, vm.today)
    val today = vm.today
    SurfaceCard {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(CELL_GAP), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(MONTH_COL))
                listOf("M", "T", "W", "T", "F", "S", "S").forEach { d ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Text(d, style = sans(10.sp, color = Ink.secondary))
                    }
                }
            }
            weeks.forEachIndexed { i, week ->
                val showMonth = i == 0 || week.start.month != weeks[i - 1].start.month
                Row(
                    Modifier.padding(top = CELL_GAP),
                    horizontalArrangement = Arrangement.spacedBy(CELL_GAP),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(MONTH_COL), contentAlignment = Alignment.CenterStart) {
                        if (showMonth) {
                            Text(week.start.monthShort(), style = sans(10.5.sp, FontWeight.SemiBold, color = Ink.secondary))
                        }
                    }
                    week.cells.forEach { cell ->
                        DayCellView(
                            cell = cell,
                            isPast = cell.date.isBefore(today),
                            isToday = cell.date == today,
                            modifier = Modifier.weight(1f),
                            onTap = {
                                when {
                                    cell.entries.size > 1 -> onOpen(Sheet.Day(cell.date))
                                    cell.entries.size == 1 -> when (val e = cell.entries.first()) {
                                        is DayEntry.OneOff -> onOpen(Sheet.OneOffDetail(e.blocker.id))
                                        is DayEntry.Practice -> onOpen(Sheet.Recurring(e.blocker.id))
                                    }
                                    else -> onOpen(Sheet.Add(prefillDate = cell.date))
                                }
                            },
                        )
                    }
                }
            }
            Legend(Modifier.padding(top = 12.dp))
        }
    }
}

@Composable
private fun DayCellView(
    cell: com.cadentic.app.domain.DayCell,
    isPast: Boolean,
    isToday: Boolean,
    modifier: Modifier,
    onTap: () -> Unit,
) {
    val primary = cell.primary
    val shape = RoundedCornerShape(9.dp)
    val filled = primary is DayEntry.OneOff

    // Past days can't take a new blocker; anything already booked stays reviewable.
    val interactive = !isPast || cell.entries.isNotEmpty()

    val description = buildString {
        append(cell.date.format(FULL_DATE))
        if (cell.entries.isEmpty()) {
            append(if (isPast) ", past, nothing scheduled" else ", free — add a blocker")
        } else {
            append(", ")
            append(cell.entries.joinToString(", ") { "${it.label} (${it.kindLabel()}, ${it.strain.label} strain)" })
        }
    }

    Box(
        modifier
            .height(CELL_HEIGHT)
            .clip(shape)
            .then(
                when {
                    filled -> Modifier.background(strainDot(primary!!.strain))
                    primary is DayEntry.Practice ->
                        Modifier.border(1.5.dp, strainDot(primary.strain), shape)
                    else -> Modifier.background(Ink.faintFill)
                }
            )
            .then(if (isToday) Modifier.border(1.5.dp, Ink.accent, shape) else Modifier)
            .alpha(if (isPast) 0.4f else 1f)
            .clickable(enabled = interactive, onClick = onTap)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            cell.date.dayOfMonth.toString(),
            style = sora(
                12.sp,
                if (primary != null) FontWeight.SemiBold else FontWeight.Medium,
                color = when {
                    filled -> Ink.surface
                    primary is DayEntry.Practice -> Ink.primary
                    else -> Ink.secondary
                },
            ),
        )
        if (cell.entries.size > 1) {
            // More than one thing on this day — the day sheet lists them all.
            Box(
                Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = 4.dp)
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(if (filled) Ink.surface else Ink.primary)
            )
        }
    }
}

@Composable
private fun Legend(modifier: Modifier) {
    Column(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.size(9.dp).clip(RoundedCornerShape(3.dp)).border(1.5.dp, Ink.secondary, RoundedCornerShape(3.dp)))
                Text("Recurring", style = sans(11.sp, color = Ink.secondary))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.size(9.dp).clip(RoundedCornerShape(3.dp)).background(Ink.secondary))
                Text("Game or one-off", style = sans(11.sp, color = Ink.secondary))
            }
        }
        Row(
            Modifier.padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Strain.entries.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(strainDot(s)))
                    Text(s.label, style = sans(11.sp, color = Ink.secondary))
                }
            }
        }
    }
}

@Composable
private fun SourceRow(
    dot: Color,
    title: String,
    subtitle: String,
    action: String,
    borderColor: Color = Ink.hairline,
    onAction: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Ink.surface)
            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
            .clickable(onClick = onAction)
            .padding(horizontal = 15.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(dot))
        Column(Modifier.weight(1f)) {
            Text(title, style = sans(14.sp, FontWeight.SemiBold))
            Text(subtitle, style = sans(12.5.sp, color = Ink.secondary), modifier = Modifier.padding(top = 1.dp))
        }
        Text(action, style = sans(12.sp, FontWeight.SemiBold, color = Ink.accentDeep))
    }
}

// --- Sheets ----------------------------------------------------------------

/**
 * Sheets open fully expanded and scroll — a half-height sheet silently hides whatever
 * sits below the fold (Delete, Add), making it unreachable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SheetFrame(
    onDismiss: () -> Unit,
    title: String,
    intro: String,
    scrollable: Boolean = true,
    content: @Composable () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Ink.screenBg,
    ) {
        Column(
            Modifier
                .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(horizontal = 22.dp)
                .padding(bottom = 28.dp)
                .imePadding(),
        ) {
            Text(title, style = Type.h1(20.sp), modifier = Modifier.padding(bottom = 4.dp))
            Text(intro, style = Type.intro(13.5.sp), modifier = Modifier.padding(bottom = 14.dp))
            content()
        }
    }
}

/** Destructive action — full-width, clearly a delete, never hidden below a primary CTA. */
@Composable
private fun RemoveButton(label: String = "Remove", onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth()
            .padding(top = 8.dp)
            .height(52.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(Ink.strainHard.copy(alpha = 0.10f))
            .border(1.5.dp, Ink.strainHard.copy(alpha = 0.45f), RoundedCornerShape(999.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = sans(15.sp, FontWeight.SemiBold, color = Ink.strainHardText))
    }
}

/** A day holding more than one thing — list them rather than silently picking one. */
@Composable
private fun DaySheet(
    vm: OnboardingViewModel,
    date: LocalDate,
    onOpen: (Sheet) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = vm.draft.constraints
    val oneOffs = c.oneOffs.filter { it.date == date }
    val practices = c.recurring.filter { date.dayOfWeek in it.days }
    SheetFrame(onDismiss, date.format(FULL_DATE), "Everything the plan has to work around on this day.") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            oneOffs.forEach { b ->
                DayEntryRow(b.label, "One-off · ${b.strain.label}", strainDot(b.strain)) {
                    onOpen(Sheet.OneOffDetail(b.id))
                }
            }
            practices.forEach { r ->
                DayEntryRow(r.label, "Recurring · ${r.timeRange} · ${r.strain.label}", strainDot(r.strain)) {
                    onOpen(Sheet.Recurring(r.id))
                }
            }
            Box(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .dashedBorder(Ink.dashedBorder, 14.dp)
                    .clickable { onOpen(Sheet.Add(prefillDate = date)) }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("+ Add another blocker", style = sans(13.5.sp, FontWeight.Medium, color = Ink.secondary))
            }
        }
    }
}

@Composable
private fun DayEntryRow(title: String, subtitle: String, dot: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Ink.surface)
            .border(1.dp, Ink.hairline, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(dot))
        Column(Modifier.weight(1f)) {
            Text(title, style = sans(14.sp, FontWeight.SemiBold))
            Text(subtitle, style = sans(12.5.sp, color = Ink.secondary), modifier = Modifier.padding(top = 1.dp))
        }
        Text("Edit", style = sans(12.sp, FontWeight.SemiBold, color = Ink.accentDeep))
    }
}

/** Per-one-off view: rename, adjust strain, or delete it. */
@Composable
private fun OneOffDetailSheet(vm: OnboardingViewModel, blocker: OneOffBlocker, onDismiss: () -> Unit) {
    var label by remember(blocker.id) { mutableStateOf(blocker.label) }
    var strain by remember(blocker.id) { mutableStateOf(blocker.strain) }
    SheetFrame(
        onDismiss,
        blocker.label,
        "${blocker.date.format(FULL_DATE)} · one-off blocker.",
    ) {
        SectionLabel("What is it?", Modifier.padding(bottom = 6.dp))
        SheetTextField(label) { label = it }
        SectionLabel("Strain", Modifier.padding(top = 11.dp, bottom = 6.dp))
        StrainPicker(strain) { strain = it }
        Box(Modifier.padding(top = 18.dp)) {
            PrimaryCta("Save", enabled = label.isNotBlank(), height = 52.dp) {
                vm.updateOneOff(blocker.id, label.trim(), strain)
                onDismiss()
            }
        }
        RemoveButton("Delete this blocker") {
            vm.removeOneOff(blocker.id)
            onDismiss()
        }
    }
}

/** Any weekly-recurring blocker: rename, re-day, re-time, re-strain, or delete. */
@Composable
private fun RecurringSheet(vm: OnboardingViewModel, existing: RecurringBlocker, onDismiss: () -> Unit) {
    var label by remember(existing.id) { mutableStateOf(existing.label) }
    var days by remember(existing.id) { mutableStateOf(existing.days) }
    var time by remember(existing.id) { mutableStateOf(existing.timeRange) }
    var strain by remember(existing.id) { mutableStateOf(existing.strain) }
    SheetFrame(
        onDismiss,
        existing.label,
        "Recurs weekly — the engine plans training volume around it.",
    ) {
        SectionLabel("What is it?", Modifier.padding(bottom = 6.dp))
        SheetTextField(label) { label = it }
        SectionLabel("Days", Modifier.padding(top = 11.dp, bottom = 6.dp))
        DayPicker(days) { days = it }
        SectionLabel("Time", Modifier.padding(top = 11.dp, bottom = 6.dp))
        SheetTextField(time) { time = it }
        SectionLabel("Strain", Modifier.padding(top = 11.dp, bottom = 6.dp))
        StrainPicker(strain) { strain = it }
        Box(Modifier.padding(top = 18.dp)) {
            PrimaryCta(
                "Save",
                enabled = label.isNotBlank() && days.isNotEmpty() && time.isNotBlank(),
                height = 52.dp,
            ) {
                vm.updateRecurring(existing.id, label.trim(), days, time.trim(), strain)
                onDismiss()
            }
        }
        RemoveButton("Delete this blocker") {
            vm.removeRecurring(existing.id)
            onDismiss()
        }
    }
}

@Composable
private fun AddBlockerSheet(vm: OnboardingViewModel, prefillDate: LocalDate?, onDismiss: () -> Unit) {
    var recurring by remember { mutableStateOf(false) }
    var label by remember { mutableStateOf("") }
    var dateText by remember { mutableStateOf((prefillDate ?: vm.today.plusDays(7)).toString()) }
    // A day tapped in the grid preselects that weekday if the user switches to Recurring.
    var days by remember { mutableStateOf(prefillDate?.let { setOf(it.dayOfWeek) } ?: emptySet()) }
    var time by remember { mutableStateOf("18:00–19:00") }
    var strain by remember { mutableStateOf(Strain.MEDIUM) }

    val parsed = runCatching { LocalDate.parse(dateText.trim()) }.getOrNull()
    val dateError = when {
        dateText.isBlank() -> null
        parsed == null -> "Use the format YYYY-MM-DD."
        parsed.isBefore(vm.today) -> "That day has already passed."
        else -> null
    }
    val dateOk = parsed != null && !parsed.isBefore(vm.today)
    val valid = label.isNotBlank() && if (recurring) days.isNotEmpty() && time.isNotBlank() else dateOk

    SheetFrame(
        onDismiss,
        // Title tracks the mode — a weekday rule isn't "Tuesday, Sep 8".
        if (!recurring && prefillDate != null) prefillDate.format(FULL_DATE) else "Add a blocker",
        "One-off gets a date; recurring gets weekdays. Strain tells the engine how much it costs you.",
    ) {
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Ink.surface)
                .border(1.dp, Ink.hairline, RoundedCornerShape(14.dp))
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            listOf(false to "One-off", true to "Recurring").forEach { (isRecurring, optionLabel) ->
                val selected = recurring == isRecurring
                Box(
                    Modifier.weight(1f)
                        .clip(RoundedCornerShape(11.dp))
                        .background(if (selected) Ink.accentTint else Color.Transparent)
                        .then(if (selected) Modifier.border(1.5.dp, Ink.accent, RoundedCornerShape(11.dp)) else Modifier)
                        .clickable { recurring = isRecurring }
                        .heightIn(min = 44.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        optionLabel,
                        style = if (selected) sans(13.5.sp, FontWeight.SemiBold, color = Ink.accentDeep)
                        else sans(13.5.sp, color = Ink.secondary),
                    )
                }
            }
        }
        SectionLabel("What is it?", Modifier.padding(top = 11.dp, bottom = 6.dp))
        SheetTextField(label, placeholder = "e.g. Club tournament") { label = it }
        if (recurring) {
            SectionLabel("Days", Modifier.padding(top = 11.dp, bottom = 6.dp))
            DayPicker(days) { days = it }
            SectionLabel("Time", Modifier.padding(top = 11.dp, bottom = 6.dp))
            SheetTextField(time) { time = it }
        } else {
            SectionLabel("Date", Modifier.padding(top = 11.dp, bottom = 6.dp))
            SheetTextField(dateText, placeholder = "YYYY-MM-DD") { dateText = it }
            if (dateError != null) {
                Text(
                    dateError,
                    style = sans(11.5.sp, FontWeight.Medium, color = Ink.strainHardText),
                    modifier = Modifier.padding(top = 4.dp),
                )
            } else if (parsed != null) {
                Text(
                    parsed.format(FULL_DATE) + ", " + parsed.year,
                    style = sans(11.5.sp, color = Ink.secondary),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        SectionLabel("Strain", Modifier.padding(top = 11.dp, bottom = 6.dp))
        StrainPicker(strain) { strain = it }
        Box(Modifier.padding(top = 18.dp)) {
            PrimaryCta("Add", enabled = valid, height = 52.dp) {
                if (recurring) vm.addRecurring(label.trim(), days, time.trim(), strain)
                else vm.addOneOff(parsed!!, label.trim(), strain)
                onDismiss()
            }
        }
    }
}

@Composable
private fun DayPicker(selected: Set<DayOfWeek>, onChange: (Set<DayOfWeek>) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        DayOfWeek.entries.forEach { day ->
            val isSelected = day in selected
            Box(
                Modifier.weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (isSelected) Ink.accentTint else Ink.surface)
                    .border(
                        if (isSelected) 1.5.dp else 1.dp,
                        if (isSelected) Ink.accent else Ink.hairline,
                        RoundedCornerShape(9.dp),
                    )
                    .clickable { onChange(if (isSelected) selected - day else selected + day) }
                    .heightIn(min = 46.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    day.name.take(1) + day.name.drop(1).take(1).lowercase(),
                    style = if (isSelected) sans(11.5.sp, FontWeight.SemiBold, color = Ink.accentDeep)
                    else sans(11.5.sp, color = Ink.secondary),
                )
            }
        }
    }
}

@Composable
private fun StrainPicker(selected: Strain, onChange: (Strain) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Strain.entries.forEach { strain ->
            val isSelected = strain == selected
            val tint = strainDot(strain).copy(alpha = 0.15f)
            val text = when (strain) {
                Strain.LIGHT -> Ink.strainLightText
                Strain.MEDIUM -> Ink.strainMediumText
                Strain.HARD -> Ink.strainHardText
            }
            Box(
                Modifier.weight(1f)
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (isSelected) tint else Ink.surface)
                    .border(
                        if (isSelected) 1.5.dp else 1.dp,
                        if (isSelected) strainDot(strain) else Ink.hairline,
                        RoundedCornerShape(999.dp),
                    )
                    .clickable { onChange(strain) }
                    .heightIn(min = 46.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    strain.label,
                    style = if (isSelected) sans(12.sp, FontWeight.SemiBold, color = text)
                    else sans(12.sp, color = Ink.secondary),
                )
            }
        }
    }
}

@Composable
private fun SheetTextField(value: String, placeholder: String = "", onChange: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Ink.surface)
            .border(1.dp, Ink.hairline, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 13.dp),
    ) {
        Box(Modifier.fillMaxWidth()) {
            if (value.isEmpty() && placeholder.isNotEmpty()) {
                Text(placeholder, style = sans(15.sp, color = Ink.secondary.copy(alpha = 0.6f)))
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                textStyle = Type.fieldValue,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
