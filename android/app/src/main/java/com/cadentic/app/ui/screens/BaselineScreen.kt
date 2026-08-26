package com.cadentic.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cadentic.app.OnboardingViewModel
import com.cadentic.app.domain.Category
import com.cadentic.app.domain.Rating
import com.cadentic.app.domain.SelfAssessment
import com.cadentic.app.domain.Sex
import com.cadentic.app.ui.components.HelperText
import com.cadentic.app.ui.components.PrimaryCta
import com.cadentic.app.ui.components.SectionLabel
import com.cadentic.app.ui.components.StepScaffold
import com.cadentic.app.ui.components.SurfaceCard
import com.cadentic.app.ui.theme.Ink
import com.cadentic.app.ui.theme.Type
import com.cadentic.app.ui.theme.sans

private val fieldShape = RoundedCornerShape(14.dp)

// Lifter levels, not raw years: how an athlete responds to load is what the engine programs against.
private val LIFTING_EXPERIENCE_OPTIONS = listOf(
    "New to lifting — under a year",
    "Novice — 1–2 years",
    "Intermediate — 2–5 years",
    "Advanced — 5–10 years",
    "Elite — 10+ years, competitive",
)

@Composable
fun BaselineScreen(vm: OnboardingViewModel) {
    val draft = vm.draft
    val profile = draft.profile

    StepScaffold(
        step = 1,
        cta = { PrimaryCta("Continue", enabled = vm.stepValid()) { vm.continueFromStep(1) } },
    ) {
        Text("First, your baseline.", style = Type.h1(26.sp), modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
        Text(
            "Two minutes. Every number here shapes the program — nothing is cosmetic.",
            style = Type.intro(14.5.sp), modifier = Modifier.padding(bottom = 14.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
            // Age + Sex row (1fr / 1.6fr)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    SectionLabel("Age", Modifier.padding(bottom = 6.dp))
                    NumberField(profile.age, vm::setAge)
                }
                Column(Modifier.weight(1.6f)) {
                    SectionLabel("Sex", Modifier.padding(bottom = 6.dp))
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(fieldShape)
                            .background(Ink.surface)
                            .border(1.dp, Ink.hairline, fieldShape)
                            .padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Sex.entries.forEach { sex ->
                            val selected = profile.sex == sex
                            Box(
                                Modifier.weight(1f)
                                    .clip(RoundedCornerShape(11.dp))
                                    .background(if (selected) Ink.accentTint else Color.Transparent)
                                    .then(
                                        if (selected) Modifier.border(1.5.dp, Ink.accent, RoundedCornerShape(11.dp))
                                        else Modifier
                                    )
                                    .clickable { vm.setSex(sex) }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    sex.label,
                                    style = if (selected) sans(13.5.sp, FontWeight.SemiBold, color = Ink.accentDeep)
                                    else sans(13.5.sp, color = Ink.secondary),
                                )
                            }
                        }
                    }
                }
            }
            // Height / Weight row
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    SectionLabel("Height", Modifier.padding(bottom = 6.dp))
                    NumberField(profile.heightCm, vm::setHeight, unit = "cm")
                }
                Column(Modifier.weight(1f)) {
                    SectionLabel("Weight", Modifier.padding(bottom = 6.dp))
                    NumberField(profile.weightKg, vm::setWeight, unit = "kg")
                }
            }
            // Lifting experience
            Column {
                SectionLabel("Lifting experience", Modifier.padding(bottom = 6.dp))
                ExperienceSelect(profile.experience, vm::setExperience)
            }
            // Current fitness
            Column {
                SectionLabel("Current fitness — rate or skip", Modifier.padding(bottom = 6.dp))
                SurfaceCard {
                    Category.entries.forEachIndexed { i, category ->
                        FitnessRow(
                            category = category,
                            state = profile.assessment[category] ?: SelfAssessment(),
                            onRate = { vm.setRating(category, it) },
                            onToggleDontCare = { vm.toggleDontCare(category) },
                            showDivider = i < Category.entries.size - 1,
                        )
                    }
                }
                HelperText("“Don't care” drops it as a goal — the plan may still touch it where it supports the rest.")
            }
        }
    }

}

@Composable
private fun FitnessRow(
    category: Category,
    state: SelfAssessment,
    onRate: (Rating) -> Unit,
    onToggleDontCare: () -> Unit,
    showDivider: Boolean,
) {
    val dimmed = state.dontCare
    // 200ms opacity/color transition into the dimmed state.
    val pillAlpha by animateFloatAsState(if (dimmed) 0.45f else 1f, tween(200), label = "pills")
    val nameColor by animateColorAsState(if (dimmed) Ink.secondary else Ink.primary, tween(200), label = "name")
    Column {
        // Row is ≥44dp tall and the clickables fill its height, so the small visual
        // pills/checkbox get full-height hit targets (spec: hit targets ≥44px everywhere).
        Row(
            Modifier.fillMaxWidth()
                .heightIn(min = 44.dp)
                .height(IntrinsicSize.Min)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                category.assessmentLabel,
                style = sans(12.5.sp, FontWeight.SemiBold, color = nameColor),
                modifier = Modifier.weight(1f),
            )
            Row(Modifier.alpha(pillAlpha), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Rating.entries.forEach { rating ->
                    val selected = !dimmed && state.rating == rating
                    Box(
                        Modifier.fillMaxHeight()
                            .clickable(enabled = !dimmed) { onRate(rating) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier.clip(RoundedCornerShape(999.dp))
                                .background(if (selected) Ink.accentTint else Ink.faintFill)
                                // Transparent border on unselected keeps row height stable.
                                .border(
                                    1.5.dp,
                                    if (selected) Ink.accent else Color.Transparent,
                                    RoundedCornerShape(999.dp),
                                )
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text(
                                rating.label,
                                style = if (selected) sans(11.sp, FontWeight.SemiBold, color = Ink.accentDeep)
                                else sans(11.sp, color = Ink.secondary),
                            )
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxHeight().clickable { onToggleDontCare() },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Box(
                    Modifier.size(16.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(if (dimmed) Ink.accent else Color.Transparent)
                        .then(
                            if (dimmed) Modifier
                            else Modifier.border(1.5.dp, Ink.checkboxBorder, RoundedCornerShape(5.dp))
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (dimmed) Text("✓", style = sans(11.sp, FontWeight.Bold, color = Ink.onAccent))
                }
                Text(
                    "don't care",
                    style = if (dimmed) sans(10.5.sp, FontWeight.SemiBold, color = Ink.accentDeep)
                    else sans(10.5.sp, color = Ink.secondary),
                )
            }
        }
        if (showDivider) Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.divider))
    }
}

@Composable
private fun NumberField(value: String, onChange: (String) -> Unit, unit: String? = null) {
    Row(
        Modifier.fillMaxWidth()
            .clip(fieldShape)
            .background(Ink.surface)
            .border(1.dp, Ink.hairline, fieldShape)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = Type.fieldValue,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        if (unit != null) Text(unit, style = sans(12.5.sp, color = Ink.secondary))
    }
}

@Composable
private fun ExperienceSelect(value: String, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth()
                .clip(fieldShape)
                .background(Ink.surface)
                .border(1.dp, Ink.hairline, fieldShape)
                .clickable { open = true }
                .padding(horizontal = 16.dp, vertical = 13.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(value, style = Type.fieldValue)
            Text("▾", style = sans(12.sp, color = Ink.secondary))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Ink.surface) {
            LIFTING_EXPERIENCE_OPTIONS.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            option,
                            style = if (option == value) sans(14.sp, FontWeight.SemiBold, color = Ink.accentDeep)
                            else sans(14.sp),
                        )
                    },
                    onClick = { onChange(option); open = false },
                )
            }
        }
    }
}
