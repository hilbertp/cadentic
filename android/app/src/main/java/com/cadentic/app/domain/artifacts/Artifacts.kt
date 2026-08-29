@file:UseSerializers(InstantSerializer::class, LocalDateSerializer::class, DayOfWeekSerializer::class)

package com.cadentic.app.domain.artifacts

import com.cadentic.app.domain.Category
import com.cadentic.app.domain.Lane
import com.cadentic.app.domain.Rating
import com.cadentic.app.domain.Sex
import com.cadentic.app.domain.Strain
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate

/**
 * The durable athlete artifacts (PRD §5.2) — the input the Mesocycle Engine assembles its
 * LLM request from. They are the binding contract of Epic 1; the storage tech behind them
 * ([JsonArtifactRepository]) is not.
 *
 * v2 dropped the blocker calendar's separate `fixtures` kind and its `fixtureSourceLabel`:
 * league games are one-off blockers like any other. v1 documents are migrated on read.
 *
 * Every artifact carries [ARTIFACT_SCHEMA_VERSION] and an `updatedAt` stamp. Property
 * declaration order *is* the serialized key order (kotlinx.serialization), and every
 * collection is written in a canonical order by [toArtifact] — so two writes of equal data
 * differ only in `updatedAt`.
 */
const val ARTIFACT_SCHEMA_VERSION: Int = 2

/** Names used for errors and for the on-disk file names. */
enum class ArtifactId(val fileName: String) {
    ATHLETE_PROFILE("athlete-profile.json"),
    ATHLETE_GOALS("athlete-goals.json"),
    ATHLETE_STATUS("athlete-status.json"),
    BLOCKER_CALENDAR("blocker-calendar.json"),
    PROGRESSION_LOG("progression-log.json"),

    /**
     * The generated cycle (Epic 2 story 4). Versioned independently of the athlete artifacts
     * — it is the engine's contract, defined in `contracts/mesocycle-api.schema.json`, not
     * a PRD §5.2 athlete record — so it carries [MESOCYCLE_PLAN_SCHEMA_VERSION].
     */
    MESOCYCLE_PLAN("mesocycle-plan.json"),
}

// --- 1 · Athlete Profile -------------------------------------------------------------
// Near-static; realistically only weight changes (PRD §5.2 row 1).

@Serializable
data class AthleteProfileArtifact(
    val schemaVersion: Int = ARTIFACT_SCHEMA_VERSION,
    val updatedAt: Instant,
    val age: Int,
    val sex: Sex,
    val heightCm: Int,
    /** Decimal-typed for forward compatibility; the UI's digit-only input is integer-valued. */
    val weightKg: Double,
)

// --- 2 · Athlete Goals ---------------------------------------------------------------
// Locked per mesocycle: renegotiated only between cycles (PRD §5.2 row 2).

/**
 * Minted at approval from the in-memory `Proposal`. Its presence *is* the lock — the goals
 * artifact rejects writes while it is set, and no unlock path exists yet. The full Mesocycle
 * Plan (phases, coach note, weekly distribution) is the next epic's artifact; this snapshot
 * carries only what proves the lock and anchors the cycle in the calendar.
 */
@Serializable
data class GoalsLock(
    val approvedAt: Instant,
    val startDate: LocalDate,
    val endDate: LocalDate,
)

@Serializable
data class AthleteGoalsArtifact(
    val schemaVersion: Int = ARTIFACT_SCHEMA_VERSION,
    val updatedAt: Instant,
    val lane: Lane,
    /** Ordered; indices below [focusCount] are programmed this cycle, the rest queue. */
    val priorities: List<Category>,
    /** The *effective* count: coerced to `1..min(MAX_FOCUS_COUNT, priorities.size)` on write. */
    val focusCount: Int,
    /** "Don't care" categories. A goals exclusion — an excluded category may still be rated. */
    val excluded: List<Category>,
    /**
     * Optional athlete ceiling on days per week that may carry effort, commitments included
     * (owner decision, 2026-08-30). A goals trade-off, so it lives here and locks with the
     * cycle. Additive with a null default, so v2 documents written before it decode as "no
     * cap" — which is what not having been asked meant.
     */
    val maxWeeklyDays: Int? = null,
    val lockedForCycle: GoalsLock? = null,
) {
    val isLocked: Boolean get() = lockedForCycle != null
}

// --- 3 · Athlete Status --------------------------------------------------------------
// Changes slowly, re-determined between cycles (PRD §5.2 row 3).

@Serializable
data class AthleteStatusArtifact(
    val schemaVersion: Int = ARTIFACT_SCHEMA_VERSION,
    val updatedAt: Instant,
    val experience: String,
    /**
     * All four categories are always present. A skipped rating is `null` — *unknown*, which
     * is a fact about the athlete, not the same as absent and never defaulted to a value.
     */
    val selfAssessment: Map<Category, Rating?>,
    val injuries: List<String>,
)

// --- Blocker Calendar (supporting store) ---------------------------------------------

/**
 * Blocker ids are stable across process restarts (Epic 1 story 4): they are persisted as
 * written and the in-process counter is re-seeded above the highest one on load. Two
 * identical-looking blockers stay distinct, so editing one never touches its twin.
 */
@Serializable
data class RecurringBlockerRecord(
    val id: Long,
    val label: String,
    /** Canonical Mon→Sun order. */
    val days: List<DayOfWeek>,
    /** OPAQUE free text ("19:00–20:30"). Consumers — the LLM included — must not parse it. */
    val timeRange: String,
    val strain: Strain,
)

@Serializable
data class OneOffBlockerRecord(
    val id: Long,
    val date: LocalDate,
    val label: String,
    val strain: Strain,
)

/** Two kinds only: what repeats weekly, and what lands on a single date. */
@Serializable
data class BlockerCalendarArtifact(
    val schemaVersion: Int = ARTIFACT_SCHEMA_VERSION,
    val updatedAt: Instant,
    val recurring: List<RecurringBlockerRecord>,
    val oneOffs: List<OneOffBlockerRecord>,
)

// --- 4 · Progression Log -------------------------------------------------------------
// Appended every training day (PRD §5.2 row 4). Epic 1 fixes the schema and initializes
// the store empty; the daily-tracking epic writes it, and the History Engine and
// Mesocycle Tracker (PRD §5.3) read it. It deliberately does NOT feed the meso-request.

@Serializable
data class ProgressionSet(
    val reps: Int,
    /** Absent for bodyweight or timed work. */
    val weightKg: Double? = null,
)

@Serializable
data class ProgressionEntry(
    val date: LocalDate,
    val exercise: String,
    val sets: List<ProgressionSet> = emptyList(),
    val durationMin: Int? = null,
    val completed: Boolean,
    /** True when the athlete logged this beyond the prescription. */
    val addedByAthlete: Boolean = false,
    /** Free-text setup notes (angles, seat settings) — informational, never used for progression. */
    val notes: String? = null,
)

@Serializable
data class ProgressionLogArtifact(
    val schemaVersion: Int = ARTIFACT_SCHEMA_VERSION,
    val updatedAt: Instant,
    val entries: List<ProgressionEntry> = emptyList(),
)
