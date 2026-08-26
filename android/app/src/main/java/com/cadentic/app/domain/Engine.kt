package com.cadentic.app.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val MONTH_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)
private val MONTH_SHORT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH)

fun LocalDate.monthDay(): String = format(MONTH_DAY)
fun LocalDate.monthShort(): String = format(MONTH_SHORT)

fun weekStart(date: LocalDate): LocalDate = date.with(DayOfWeek.MONDAY)

/** Persona seed: club basketball, one weekly practice and a season's worth of game days. */
object Seed {
    fun constraints(today: LocalDate): Constraints {
        val wk = weekStart(today)
        // Irregular by design: games Sat wk1, Fri wk2, none wk3, Sun wk4, none wk5,
        // Fri+Sat back-to-back wk6, then more beyond the horizon = 13 total.
        val gameOffsets = listOf(
            0L to DayOfWeek.SATURDAY, 1L to DayOfWeek.FRIDAY, 3L to DayOfWeek.SUNDAY,
            5L to DayOfWeek.FRIDAY, 5L to DayOfWeek.SATURDAY, 6L to DayOfWeek.SATURDAY,
            8L to DayOfWeek.FRIDAY, 9L to DayOfWeek.SATURDAY, 10L to DayOfWeek.SUNDAY,
            11L to DayOfWeek.SATURDAY, 12L to DayOfWeek.FRIDAY, 13L to DayOfWeek.SATURDAY,
            15L to DayOfWeek.SATURDAY,
        )
        // Game days are ordinary one-off blockers: hard, on a fixed date, the athlete's to edit.
        val games = gameOffsets.mapIndexed { i, (weeks, day) ->
            OneOffBlocker(
                id = Ids.next(),
                date = wk.plusWeeks(weeks).with(day),
                label = "Round ${i + 1}",
                strain = Strain.HARD,
            )
        }
        return Constraints(
            recurring = listOf(
                RecurringBlocker(
                    id = Ids.next(),
                    label = "Team practice",
                    days = setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY),
                    timeRange = "19:00–20:30",
                    strain = Strain.MEDIUM,
                )
            ),
            oneOffs = games,
        )
    }
}

/**
 * Process-wide monotonic ids. Constraints are identified by id, never by value.
 *
 * The counter restarts at 0 with the process, so a persisted blocker calendar re-seeds it
 * above its highest id on load ([seedAtLeast]) — otherwise a blocker created after a restart
 * would collide with one already on disk and edits would hit the wrong row.
 */
object Ids {
    private var counter = 0L
    fun next(): Long = ++counter

    fun seedAtLeast(highWaterMark: Long) {
        if (highWaterMark > counter) counter = highWaterMark
    }

    /** Test hook: what process death does to the counter. */
    internal fun resetForTests() {
        counter = 0L
    }
}

/** What actually sits on a given day. Ranked: a one-off outranks a recurring practice. */
sealed interface DayEntry {
    val strain: Strain
    val label: String

    data class OneOff(val blocker: OneOffBlocker) : DayEntry {
        override val strain get() = blocker.strain
        override val label get() = blocker.label
    }

    data class Practice(val blocker: RecurringBlocker) : DayEntry {
        override val strain get() = blocker.strain
        override val label get() = blocker.label
    }
}

/**
 * A day in the horizon grid. [entries] is the full, ranked truth for that date, so the cell's
 * appearance and its tap target are derived from the same value — they can never disagree,
 * and nothing on a shared date becomes invisible or unreachable.
 */
data class DayCell(val date: LocalDate, val entries: List<DayEntry>) {
    val primary: DayEntry? get() = entries.firstOrNull()
    val isEmpty: Boolean get() = entries.isEmpty()
}

data class HorizonWeek(val start: LocalDate, val cells: List<DayCell>)

/** Six weeks from this week's Monday, Mon..Sun per row. */
fun horizon(constraints: Constraints, today: LocalDate, weeks: Int = 6): List<HorizonWeek> {
    val start = weekStart(today)
    return (0 until weeks).map { w ->
        val monday = start.plusWeeks(w.toLong())
        HorizonWeek(
            start = monday,
            cells = (0..6).map { d ->
                val date = monday.plusDays(d.toLong())
                val entries = buildList {
                    constraints.oneOffs.filter { it.date == date }.forEach { add(DayEntry.OneOff(it)) }
                    constraints.recurring.filter { date.dayOfWeek in it.days }.forEach { add(DayEntry.Practice(it)) }
                }
                DayCell(date, entries)
            },
        )
    }
}

/**
 * Local stand-in for the server-side mesocycle engine (production: ~20s generation).
 * Plans structure around the athlete's constraints; conditions like heat are the
 * athlete's own business — the engine stays out of it.
 */
object ProposalEngine {
    fun generate(draft: OnboardingDraft, today: LocalDate): Proposal {
        val start = today
        val end = start.plusWeeks(12).minusDays(1)
        val hardDays = draft.constraints.oneOffs.count {
            it.strain == Strain.HARD && !it.date.isBefore(start) && !it.date.isAfter(end)
        }
        val longevity = draft.lane == Lane.LONGEVITY

        // Reads correctly at 0, 1, and many — every count is reachable, blockers are deletable.
        val hardDaysClause = when (hardDays) {
            0 -> if (longevity)
                "Nothing hard is booked, so the heavy weeks land where the training wants them."
            else
                "Nothing hard is booked, so nothing softens the heavy weeks."
            1 -> if (longevity)
                "One hard day sits inside this cycle; the heavy weeks bend around it, never through it."
            else
                "One hard day sits inside this cycle — expect heavy weeks stacked tight around it."
            else -> if (longevity)
                "$hardDays hard days sit inside this cycle; the heavy weeks bend around them, never through them."
            else
                "$hardDays hard days sit inside this cycle — expect heavy weeks stacked tight between them."
        }

        val coachNote = if (longevity)
            "Twelve weeks, one engine. Base lays the aerobic floor, build loads on top of it, peak sharpens " +
                "the stretch that matters, and the deload lands in week 12 whether you feel you need it or " +
                "not — that's the point. $hardDaysClause"
        else
            "Twelve weeks, output first. Base is short runway, build pushes volume hard, peak is two weeks " +
                "of edge, and the deload in week 12 is non-negotiable even in this lane. $hardDaysClause"

        return Proposal(
            startDate = start,
            endDate = end,
            sessionsPerWeek = 5,
            lane = draft.lane,
            laneLabel = if (longevity) "longevity-first" else "performance-first",
            headline = if (longevity) "12 weeks, engine first." else "12 weeks, output first.",
            phases = listOf(
                Phase("Base", "wk 1–4", 4),
                Phase("Build", "wk 5–9", 5),
                Phase("Peak", "10–11", 2),
                Phase("Deload", "12", 1),
            ),
            coachNote = coachNote,
            focusThisCycle = draft.focus,
            queuedForLater = draft.queued,
        )
    }
}
