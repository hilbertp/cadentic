package com.cadentic.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cadentic.app.domain.Category
import com.cadentic.app.domain.MAX_FOCUS_COUNT
import com.cadentic.app.domain.Ids
import com.cadentic.app.domain.Lane
import com.cadentic.app.domain.OnboardingDraft
import com.cadentic.app.domain.OneOffBlocker
import com.cadentic.app.domain.ProposalEngine
import com.cadentic.app.domain.Rating
import com.cadentic.app.domain.RecurringBlocker
import com.cadentic.app.domain.Seed
import com.cadentic.app.domain.SelfAssessment
import com.cadentic.app.domain.Sex
import com.cadentic.app.domain.Status
import com.cadentic.app.domain.Strain
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate

// Production generates server-side over ~20s; the local stub keeps the state visible but brief.
private const val GENERATION_MS = 4200L

class OnboardingViewModel : ViewModel() {

    // One clock read: the grid anchor and the seeded fixtures must agree even across midnight.
    val today: LocalDate = LocalDate.now()

    var draft by mutableStateOf(OnboardingDraft(constraints = Seed.constraints(today)))
        private set

    // 1..4 = steps; generation is status GENERATING while on step 3→4 transition.
    var step by mutableStateOf(1)
        private set

    // Conflated: mid-drag boundary oscillation shows one brief snackbar, not a backlog.
    private val snackbars = Channel<String>(Channel.CONFLATED)
    val snackbarFlow = snackbars.receiveAsFlow()

    private var generationJob: Job? = null

    // --- Step 1: baseline -------------------------------------------------

    fun setAge(v: String) = update { copy(profile = profile.copy(age = v.filter(Char::isDigit).take(2))) }
    fun setSex(v: Sex) = update { copy(profile = profile.copy(sex = v)) }
    fun setHeight(v: String) = update { copy(profile = profile.copy(heightCm = v.filter(Char::isDigit).take(3))) }
    fun setWeight(v: String) = update { copy(profile = profile.copy(weightKg = v.filter(Char::isDigit).take(3))) }
    fun setExperience(v: String) = update { copy(profile = profile.copy(experience = v)) }

    fun setRating(category: Category, rating: Rating) = update {
        copy(profile = profile.copy(assessment = profile.assessment + (category to SelfAssessment(rating))))
    }

    /** "Don't care" drops the category as a goal and removes it from step 2's ranking live. */
    fun toggleDontCare(category: Category) = update {
        val current = profile.assessment[category] ?: SelfAssessment()
        val nowDontCare = !current.dontCare
        copy(
            profile = profile.copy(assessment = profile.assessment + (category to current.copy(dontCare = nowDontCare))),
            priorities = if (nowDontCare) priorities - category
            else if (category in priorities) priorities else priorities + category,
        )
    }

    // --- Step 2: priorities & guardrails ----------------------------------

    fun movePriority(from: Int, to: Int) {
        val list = draft.priorities.toMutableList()
        if (from !in list.indices || to !in list.indices || from == to) return
        val moved = list[from]
        val fc = draft.effectiveFocusCount
        val previousFocus = list.take(fc).toSet()
        list.add(to, list.removeAt(from))
        update { copy(priorities = list) }
        // Snackbar only when a *different* row gets pushed below the line — dragging
        // a focus row below yourself is deliberate, not a rule the user needs explained.
        val displaced = previousFocus - list.take(fc).toSet()
        displaced.firstOrNull { it != moved }?.let {
            val limit = if (fc == 1) "One focus this cycle" else "Two per cycle"
            toast("$limit — ${it.priorityTitle} waits below the focus line.")
        }
    }

    /** Narrow the cycle to a single focus, or widen it back to two. */
    fun setFocusCount(n: Int) = update { copy(focusCount = n.coerceIn(1, MAX_FOCUS_COUNT)) }

    fun setLane(lane: Lane) = update { copy(lane = lane) }
    fun addInjury(text: String) {
        val t = text.trim()
        if (t.isNotEmpty()) update { copy(injuries = injuries + t) }
    }
    fun removeInjury(text: String) = update { copy(injuries = injuries - text) }

    // --- Step 3: blockers -------------------------------------------------

    // Every mutation below matches on the stable id. Matching on value would let two
    // look-alike blockers (same day, same label, same strain) be edited or deleted as one.

    /** Correct how much a league game costs this athlete — the import stays authoritative on dates. */
    fun setFixtureStrain(id: Long, strain: Strain) = update {
        copy(constraints = constraints.copy(
            fixtures = constraints.fixtures.map { if (it.id == id) it.copy(strain = strain) else it }
        ))
    }

    /** The athlete isn't playing this fixture — drop it from the plan. */
    fun removeFixture(id: Long) = update {
        copy(constraints = constraints.copy(fixtures = constraints.fixtures.filterNot { it.id == id }))
    }

    fun updateOneOff(id: Long, label: String, strain: Strain) = update {
        copy(constraints = constraints.copy(
            oneOffs = constraints.oneOffs.map {
                if (it.id == id) it.copy(label = label, strain = strain) else it
            }
        ))
    }

    fun addOneOff(date: LocalDate, label: String, strain: Strain) = update {
        copy(constraints = constraints.copy(
            oneOffs = constraints.oneOffs + OneOffBlocker(Ids.next(), date, label, strain)
        ))
    }

    fun removeOneOff(id: Long) = update {
        copy(constraints = constraints.copy(oneOffs = constraints.oneOffs.filterNot { it.id == id }))
    }

    fun updateRecurring(id: Long, label: String, days: Set<DayOfWeek>, timeRange: String, strain: Strain) = update {
        copy(constraints = constraints.copy(
            recurring = constraints.recurring.map {
                if (it.id == id) it.copy(label = label, days = days, timeRange = timeRange, strain = strain) else it
            }
        ))
    }

    fun addRecurring(label: String, days: Set<DayOfWeek>, timeRange: String, strain: Strain) = update {
        copy(constraints = constraints.copy(
            recurring = constraints.recurring + RecurringBlocker(Ids.next(), label, days, timeRange, strain)
        ))
    }

    fun removeRecurring(id: Long) = update {
        copy(constraints = constraints.copy(recurring = constraints.recurring.filterNot { it.id == id }))
    }

    // --- Navigation & generation ------------------------------------------

    fun stepValid(): Boolean = when (step) {
        1 -> {
            val p = draft.profile
            p.age.toIntOrNull() in 14..90 && p.heightCm.toIntOrNull() in 120..230 &&
                p.weightKg.toIntOrNull() in 35..250 &&
                // At least one category must remain a goal.
                p.assessment.values.any { !it.dontCare }
        }
        2 -> draft.priorities.isNotEmpty()
        else -> true
    }

    /** [fromStep] is the calling screen's own step — a double-tap racing the screen
     *  transition (or a stale CTA on the outgoing screen) becomes a no-op. */
    fun continueFromStep(fromStep: Int) {
        if (fromStep != step || !stepValid()) return
        when (step) {
            1, 2 -> step += 1
            3 -> generate()
        }
    }

    private fun generate() {
        if (draft.status != Status.DRAFT) return
        update { copy(status = Status.GENERATING) }
        generationJob = viewModelScope.launch {
            delay(GENERATION_MS)
            update { copy(proposal = ProposalEngine.generate(draft, today), status = Status.PROPOSED) }
            step = 4
        }
    }

    fun approve() {
        // Locks the mesocycle: fixed once approved (PRD §5.1) — priorities only change between cycles.
        if (draft.status != Status.PROPOSED) return
        update { copy(status = Status.APPROVED) }
    }

    fun askForChanges() {
        // Negotiation UX is undesigned (open in PRD) — be honest, offer the working path.
        toast("Negotiation isn't built yet — go back, adjust, and regenerate.")
    }

    /** System back. Returns false when onboarding should let the system handle it (exit). */
    fun back(): Boolean {
        if (draft.status == Status.GENERATING) {
            generationJob?.cancel()
            update { copy(status = Status.DRAFT) }
            return true
        }
        if (draft.status == Status.APPROVED) return false
        if (step > 1) {
            if (step == 4) update { copy(status = Status.DRAFT) }
            step -= 1
            return true
        }
        return false
    }

    private fun update(block: OnboardingDraft.() -> OnboardingDraft) {
        draft = draft.block()
    }

    private fun toast(msg: String) {
        viewModelScope.launch { snackbars.send(msg) }
    }
}
