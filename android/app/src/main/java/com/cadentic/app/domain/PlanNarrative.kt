package com.cadentic.app.domain

import com.cadentic.app.domain.artifacts.MesocyclePhase
import com.cadentic.app.domain.artifacts.MesocyclePlanArtifact
import com.cadentic.app.domain.artifacts.PhaseType

/**
 * The words on the proposal screen, composed from the plan's structured facts.
 *
 * **PRD §8: plan surfaces never render model free text.** The engine returns structure; every
 * sentence an athlete reads is assembled here, from that structure, by code. Nothing the
 * model wrote is displayed — which is what keeps the GUI deterministic and what stops a
 * confidently-worded hallucination from reaching someone's training.
 *
 * **This lives on the client (Epic 2, implementor open point 5).** Either side could compose
 * it deterministically, so the tie-breaker is that the wording is a *display* concern: it
 * belongs next to the screen that shows it, changes with design rather than with the engine,
 * and keeps the backend's response a pure data contract with nothing to translate.
 *
 * The one exception is the plan's own `progression` prose, which *is* model text — and it is
 * deliberately not rendered on the proposal screen. It is persisted for the daily layer,
 * where it informs prescription rather than being read out as coaching.
 */
object PlanNarrative {

    fun laneLabel(lane: Lane): String = when (lane) {
        Lane.LONGEVITY -> "longevity-first"
        Lane.PERFORMANCE -> "performance-first"
    }

    /** "8 weeks, engine first." — the duration is the plan's, not a fixed twelve. */
    fun headline(plan: MesocyclePlanArtifact): String {
        val emphasis = when (plan.lane) {
            Lane.LONGEVITY -> "engine first"
            Lane.PERFORMANCE -> "output first"
        }
        return "${plan.durationWeeks} weeks, $emphasis."
    }

    /**
     * Phase labels for the timeline. The first segment carries the "wk" so the row reads as
     * weeks without repeating the unit four times; a one-week phase is just its number.
     */
    fun weeksLabels(phases: List<MesocyclePhase>): List<String> {
        var week = 1
        return phases.mapIndexed { index, phase ->
            val first = week
            val last = week + phase.weeks - 1
            week = last + 1
            val range = if (phase.weeks == 1) "$first" else "$first–$last"
            if (index == 0) "wk $range" else range
        }
    }

    /**
     * The coach's note. Every clause is derived from something in the plan or the calendar —
     * the phase arc, when the deload lands, and how many hard days the athlete already has
     * booked inside the cycle.
     */
    fun coachNote(plan: MesocyclePlanArtifact, constraints: Constraints): String =
        listOf(
            arcClause(plan),
            deloadClause(plan),
            hardDaysClause(plan, constraints),
        ).filter { it.isNotEmpty() }.joinToString(" ")

    private fun arcClause(plan: MesocyclePlanArtifact): String {
        val arc = plan.phases.joinToString(", then ") { it.name.lowercase() }
        val sessions = plan.sessionsPerWeek
        val weekly = if (sessions == 1) "one session a week" else "$sessions sessions a week"
        return "${plan.durationWeeks} weeks at $weekly: $arc."
    }

    private fun deloadClause(plan: MesocyclePlanArtifact): String {
        val weeks = plan.deloadWeeks()
        return when {
            // A cycle with no deload is the model's call, and saying nothing beats
            // inventing a reassurance the plan does not support.
            weeks.isEmpty() -> ""
            weeks.size == 1 ->
                "The deload lands in week ${weeks.first()} whether you feel you need it or not — that's the point."
            else ->
                "Deload weeks ${weeks.joinToString(" and ")} are not optional, however good you feel."
        }
    }

    /** Reads correctly at 0, 1 and many — every count is reachable, blockers are deletable. */
    private fun hardDaysClause(plan: MesocyclePlanArtifact, constraints: Constraints): String {
        val hardDays = constraints.oneOffs.count {
            it.strain == Strain.HARD && !it.date.isBefore(plan.startDate) && !it.date.isAfter(plan.endDate)
        }
        val longevity = plan.lane == Lane.LONGEVITY
        return when (hardDays) {
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
    }
}

/**
 * The plan as the proposal screen wants it. A view model over the artifact: `laneLabel`,
 * `weeksLabel`, `headline` and `coachNote` are all derived here and none of them are
 * persisted — the artifact stays the facts, this stays the presentation.
 */
fun MesocyclePlanArtifact.toProposal(constraints: Constraints): Proposal {
    val labels = PlanNarrative.weeksLabels(phases)
    return Proposal(
        startDate = startDate,
        endDate = endDate,
        sessionsPerWeek = sessionsPerWeek,
        lane = lane,
        laneLabel = PlanNarrative.laneLabel(lane),
        headline = PlanNarrative.headline(this),
        phases = phases.mapIndexed { i, p -> Phase(p.phaseType, p.name, labels[i], p.weeks) },
        coachNote = PlanNarrative.coachNote(this, constraints),
        focusThisCycle = focus,
        queuedForLater = queued,
    )
}

/** The deload phase types the timeline dashes, kept next to the rest of the presentation. */
val PhaseType.isDeload: Boolean get() = this == PhaseType.DELOAD
