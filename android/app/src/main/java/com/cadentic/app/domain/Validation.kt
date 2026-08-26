package com.cadentic.app.domain

/**
 * The accepted ranges for the baseline step, in the domain layer so the UI's `stepValid()`
 * and the artifact writer share one source of truth (Epic 1 story 1). The UI holds these
 * fields as strings; the artifact stores numbers, and the only conversion lives here — a
 * value that fails [age]/[heightCm]/[weightKg] can never reach an artifact.
 */
object ProfileRules {
    val AGE: IntRange = 14..90
    val HEIGHT_CM: IntRange = 120..230
    val WEIGHT_KG: IntRange = 35..250

    fun age(raw: String): Int? = raw.toIntOrNull()?.takeIf { it in AGE }
    fun heightCm(raw: String): Int? = raw.toIntOrNull()?.takeIf { it in HEIGHT_CM }

    /** Digit-only in the UI today; typed decimal so a scale-synced weight stays expressible. */
    fun weightKg(raw: String): Double? =
        raw.toDoubleOrNull()?.takeIf { it >= WEIGHT_KG.first && it <= WEIGHT_KG.last }

    /** Base data complete and in range — everything but the "at least one goal" rule. */
    fun baseDataValid(profile: Profile): Boolean =
        age(profile.age) != null && heightCm(profile.heightCm) != null && weightKg(profile.weightKg) != null
}
