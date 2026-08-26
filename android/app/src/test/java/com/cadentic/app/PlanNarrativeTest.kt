package com.cadentic.app

import com.cadentic.app.domain.Constraints
import com.cadentic.app.domain.Lane
import com.cadentic.app.domain.OneOffBlocker
import com.cadentic.app.domain.PlanNarrative
import com.cadentic.app.domain.Strain
import com.cadentic.app.domain.artifacts.MesocyclePhase
import com.cadentic.app.domain.artifacts.PhaseType
import com.cadentic.app.domain.toProposal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The proposal screen's words (Epic 2, implementor open point 5).
 *
 * PRD §8 says plan surfaces never render model free text, so every sentence here is composed
 * from the plan's structure. These tests are what make that a property rather than a claim:
 * the same plan always produces the same words, and the words track the facts.
 */
class PlanNarrativeTest {

    private val empty = Constraints(recurring = emptyList())

    private fun withHardDays(n: Int) = Constraints(
        recurring = emptyList(),
        oneOffs = (1..n).map {
            OneOffBlocker(it.toLong(), TODAY.plusDays(7L + it), "Round $it", Strain.HARD)
        },
    )

    // --- Determinism -----------------------------------------------------------

    @Test
    fun `the same plan always composes the same words`() {
        val plan = samplePlan()
        val once = plan.toProposal(withHardDays(3))
        val twice = plan.toProposal(withHardDays(3))
        assertEquals(once.headline, twice.headline)
        assertEquals(once.coachNote, twice.coachNote)
        assertEquals(once.phases, twice.phases)
    }

    @Test
    fun `nothing the model wrote reaches the proposal screen`() {
        // The progression prose is the only model text in the plan, and the proposal does not
        // show it — it is persisted for the daily layer, not read out as coaching.
        val plan = samplePlan()
        val proposal = plan.toProposal(empty)
        assertTrue(!proposal.coachNote.contains(plan.progression.intraWeek))
        assertTrue(!proposal.coachNote.contains(plan.progression.interWeek))
        assertTrue(!proposal.headline.contains(plan.progression.interWeek))
    }

    // --- Headline and lane -----------------------------------------------------

    @Test
    fun `the headline carries the plan's own duration, not a fixed twelve`() {
        assertEquals("6 weeks, engine first.", PlanNarrative.headline(samplePlan(durationWeeks = 6)))
        assertEquals("16 weeks, engine first.", PlanNarrative.headline(samplePlan(durationWeeks = 16)))
    }

    @Test
    fun `the headline follows the lane`() {
        assertTrue(PlanNarrative.headline(samplePlan(lane = Lane.LONGEVITY)).endsWith("engine first."))
        assertTrue(PlanNarrative.headline(samplePlan(lane = Lane.PERFORMANCE)).endsWith("output first."))
        assertEquals("longevity-first", PlanNarrative.laneLabel(Lane.LONGEVITY))
        assertEquals("performance-first", PlanNarrative.laneLabel(Lane.PERFORMANCE))
    }

    // --- Phase labels ----------------------------------------------------------

    @Test
    fun `phase labels run consecutively and name the unit once`() {
        val labels = PlanNarrative.weeksLabels(
            listOf(
                MesocyclePhase(PhaseType.BASE, "Base", 4),
                MesocyclePhase(PhaseType.BUILD, "Build", 5),
                MesocyclePhase(PhaseType.PEAK, "Peak", 2),
                MesocyclePhase(PhaseType.DELOAD, "Deload", 1),
            ),
        )
        assertEquals(listOf("wk 1–4", "5–9", "10–11", "12"), labels)
    }

    @Test
    fun `a single-week opening phase is still labelled with the unit`() {
        val labels = PlanNarrative.weeksLabels(
            listOf(
                MesocyclePhase(PhaseType.DELOAD, "Reset", 1),
                MesocyclePhase(PhaseType.BASE, "Base", 3),
            ),
        )
        assertEquals(listOf("wk 1", "2–4"), labels)
    }

    @Test
    fun `the display name is carried through untouched`() {
        // The engine may call a phase anything; the UI colours by phaseType and prints this.
        val plan = samplePlan().copy(
            phases = listOf(MesocyclePhase(PhaseType.BASE, "Foundation", 8)),
        )
        val phase = plan.toProposal(empty).phases.single()
        assertEquals("Foundation", phase.name)
        assertEquals(PhaseType.BASE, phase.phaseType)
    }

    // --- Coach's note ----------------------------------------------------------

    @Test
    fun `the note names the phase arc and the weekly load`() {
        val note = samplePlan().toProposal(empty).coachNote
        assertTrue(note, note.startsWith("8 weeks at 5 sessions a week: base, then build, then deload, then peak."))
    }

    @Test
    fun `the note says when the deload lands`() {
        // Base 3 + Build 3 puts the deload in week 7.
        assertTrue(samplePlan().toProposal(empty).coachNote.contains("deload lands in week 7"))
    }

    @Test
    fun `a plan without a deload claims nothing about one`() {
        val plan = samplePlan().copy(
            phases = listOf(
                MesocyclePhase(PhaseType.BASE, "Base", 4),
                MesocyclePhase(PhaseType.BUILD, "Build", 4),
            ),
        )
        val note = plan.toProposal(empty).coachNote
        assertTrue(note, !note.contains("deload") && !note.contains("Deload"))
    }

    @Test
    fun `two deload weeks are named as two`() {
        val plan = samplePlan().copy(
            phases = listOf(
                MesocyclePhase(PhaseType.BASE, "Base", 3),
                MesocyclePhase(PhaseType.DELOAD, "Deload", 2),
                MesocyclePhase(PhaseType.BUILD, "Build", 3),
            ),
        )
        assertTrue(plan.toProposal(empty).coachNote.contains("Deload weeks 4 and 5"))
    }

    @Test
    fun `the hard-day clause reads correctly at zero, one and many`() {
        // Every count is reachable, because every blocker is deletable.
        val plan = samplePlan()
        assertTrue(plan.toProposal(withHardDays(0)).coachNote.contains("Nothing hard is booked"))
        assertTrue(plan.toProposal(withHardDays(1)).coachNote.contains("One hard day sits inside"))
        assertTrue(plan.toProposal(withHardDays(4)).coachNote.contains("4 hard days sit inside"))
    }

    @Test
    fun `hard days outside the cycle are not counted`() {
        val plan = samplePlan(startDate = TODAY.plusDays(6), durationWeeks = 2)
        val outside = Constraints(
            recurring = emptyList(),
            oneOffs = listOf(
                OneOffBlocker(1, TODAY, "Before the cycle", Strain.HARD),
                OneOffBlocker(2, plan.endDate.plusDays(1), "After the cycle", Strain.HARD),
            ),
        )
        assertTrue(plan.toProposal(outside).coachNote.contains("Nothing hard is booked"))
    }

    @Test
    fun `only hard blockers count as hard days`() {
        val soft = Constraints(
            recurring = emptyList(),
            oneOffs = listOf(
                OneOffBlocker(1, TODAY.plusDays(10), "Travel", Strain.LIGHT),
                OneOffBlocker(2, TODAY.plusDays(11), "Practice", Strain.MEDIUM),
            ),
        )
        assertTrue(samplePlan().toProposal(soft).coachNote.contains("Nothing hard is booked"))
    }

    @Test
    fun `the lane changes how the hard days are described`() {
        val longevity = samplePlan(lane = Lane.LONGEVITY).toProposal(withHardDays(2)).coachNote
        val performance = samplePlan(lane = Lane.PERFORMANCE).toProposal(withHardDays(2)).coachNote
        assertTrue(longevity.contains("bend around them, never through them"))
        assertTrue(performance.contains("stacked tight between them"))
    }
}
