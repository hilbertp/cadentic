package com.cadentic.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.cadentic.app.R

// Design tokens from design_handoff_cadentic_onboarding/README.md — treat as final intent.

object Ink {
    val screenBg = Color(0xFFF5F2EB)
    val surface = Color(0xFFFFFFFF)
    val primary = Color(0xFF211E17)
    val secondary = Color(0xFF6E6858)
    val hairline = Color(0x1F211E17)      // rgba(33,30,23,.12)
    val divider = Color(0x12211E17)       // rgba(33,30,23,.07)
    val faintFill = Color(0x0D211E17)     // rgba(33,30,23,.05)
    val faintFill7 = Color(0x12211E17)    // rgba(33,30,23,.07)
    val focusBand = Color(0x08211E17)     // rgba(33,30,23,.03)
    val dragBar = Color(0x40211E17)       // rgba(33,30,23,.25)
    val checkboxBorder = Color(0x4D211E17) // rgba(33,30,23,.3)
    val dashedBorder = Color(0x40211E17)  // rgba(33,30,23,.25)
    val chipFaint = Color(0x0F211E17)     // rgba(33,30,23,.06)

    val accent = Color(0xFF2FBF8F)
    val accentTint = Color(0x242FBF8F)    // rgba(47,191,143,.14)
    val accentTint18 = Color(0x2E2FBF8F)  // rgba(47,191,143,.18)
    val accentTint38 = Color(0x612FBF8F)  // rgba(47,191,143,.38)
    val onAccent = Color(0xFF17352A)
    val accentDeep = Color(0xFF157D5F)
    val accentDeeper = Color(0xFF0F5C46)

    // Strain scale — the calendar and every blocker chip key off these.
    val strainLight = Color(0xFFD9A62E)
    val strainLightText = Color(0xFF9A7414)
    val strainMedium = Color(0xFFDE7B35)
    val strainMediumText = Color(0xFFB05A1C)
    val strainHard = Color(0xFFC4513A)
    val strainHardText = Color(0xFFA33D28)
    // The amber heat palette that lived here was removed with the climate system.
}

@OptIn(ExperimentalTextApi::class)
val Sora = FontFamily(
    Font(R.font.sora, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.sora, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.sora, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

@OptIn(ExperimentalTextApi::class)
val InstrumentSans = FontFamily(
    Font(R.font.instrument_sans, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400), FontVariation.width(100f))),
    Font(R.font.instrument_sans, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500), FontVariation.width(100f))),
    Font(R.font.instrument_sans, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600), FontVariation.width(100f))),
)

private val noPadding = PlatformTextStyle(includeFontPadding = false)
private val centerTrim = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)

fun sora(size: TextUnit, weight: FontWeight = FontWeight.SemiBold, lineHeight: TextUnit = TextUnit.Unspecified, letterSpacing: TextUnit = TextUnit.Unspecified, color: Color = Ink.primary) =
    TextStyle(
        fontFamily = Sora, fontSize = size, fontWeight = weight, lineHeight = lineHeight,
        letterSpacing = letterSpacing, color = color, platformStyle = noPadding, lineHeightStyle = centerTrim,
    )

fun sans(size: TextUnit, weight: FontWeight = FontWeight.Normal, lineHeight: TextUnit = TextUnit.Unspecified, letterSpacing: TextUnit = TextUnit.Unspecified, color: Color = Ink.primary) =
    TextStyle(
        fontFamily = InstrumentSans, fontSize = size, fontWeight = weight, lineHeight = lineHeight,
        letterSpacing = letterSpacing, color = color, platformStyle = noPadding, lineHeightStyle = centerTrim,
    )

object Type {
    val wordmark = sora(12.sp, FontWeight.Bold, letterSpacing = 3.5.sp)
    val stepCounter = sora(12.sp, FontWeight.SemiBold, color = Ink.secondary)
    fun h1(size: TextUnit = 26.sp) = sora(size, FontWeight.SemiBold, lineHeight = size * 1.2f)
    fun intro(size: TextUnit = 14.5.sp) = sans(size, FontWeight.Normal, lineHeight = size * 1.5f, color = Ink.secondary)
    fun sectionLabel(size: TextUnit = 11.sp, tracking: TextUnit = 1.2.sp) =
        sans(size, FontWeight.SemiBold, letterSpacing = tracking, color = Ink.secondary)
    val fieldValue = sans(15.sp, FontWeight.Medium)
    val cardTitle = sans(14.sp, FontWeight.SemiBold)
    val meta = sans(12.5.sp, color = Ink.secondary)
    val helper = sans(11.5.sp, lineHeight = 11.5.sp * 1.45f, color = Ink.secondary)
    val cta = sans(15.5.sp, FontWeight.SemiBold, color = Ink.onAccent)
}
