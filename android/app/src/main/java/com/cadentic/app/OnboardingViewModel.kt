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
import com.cadentic.app.domain.ProfileRules
import com.cadentic.app.domain.ProposalEngine
import com.cadentic.app.domain.Rating
import com.cadentic.app.domain.RecurringBlocker
import com.cadentic.app.domain.Seed
import com.cadentic.app.domain.SelfAssessment
import com.cadentic.app.domain.Sex
import com.cadentic.app.domain.Status
import com.cadentic.app.domain.Strain
import com.cadentic.app.domain.artifacts.ArtifactError
import com.cadentic.app.domain.artifacts.ArtifactException
import com.cadentic.app.domain.artifacts.ArtifactRepository
import com.cadentic.app.domain.artifacts.GoalsLock
import com.cadentic.app.domain.artifacts.hydrateDraft
import com.cadentic.app.domain.artifacts.reseedIds
import com.cadentic.app.domain.artifacts.toConstraints
import com.cadentic.app.domain.artifacts.toArtifact
import com.cadentic.app.domain.artifacts.toGoalsArtifact
import com.cadentic.app.domain.artifacts.toProfileArtifact
import com.cadentic.app.domain.artifacts.toStatusArtifact
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate

// Production generates server-side over ~20s; the local stub keeps the state visible but brief.
private const val GENERATION_MS = 4200L

class OnboardingViewModel(
    private val repository: ArtifactRepository,
    // One clock read: the grid anchor and the seeded fixtures must agree even across midnight.
    val today: LocalDate = LocalDate.now(),
    private val clock: Clock = Clock.systemUTC(),
) : ViewModel() {

    private val bootstrap: Bootstrap = loadFromArtifacts()

    var draft by mutableStateOf(bootstrap.draft)
        private set

    // 1..4 = steps; generation is status GENERATING while on step 3→4 transition.
    var step by mutableStateOf(bootstrap.step)
        private set

    /** Present once a cycle is approved: goals are frozen until the next cycle (PRD §5.2). */
    var goalsLock by mutableStateOf(bootstrap.lock)
        private set

    /**
     * Set when the store holds something this build must not touch — an artifact written by
     * a newer version, or a corrupt one. Onboarding still runs in memory, but nothing is
     * written: overwriting would destroy the very data the error is about.
     */
    private var artifactsBlocked = bootstrap.error != null

    // Conflated: mid-drag boundary oscillation shows one brief snackbar, not a backlog.
    private val snackbars = Channel<String>(Channel.CONFLATED)
    val snackbarFlow = snackbars.receiveAsFlow()

    private var generationJob: Job? = null

    init {
        bootstrap.error?.let { toast("Saved data can't be read — ${it.message}") }
    }

    // --- Launch: hydrate from artifacts (story 7) -------------------------

    private class Bootstrap(
        val draft: OnboardingDraft,
        val step: Int,
        val lock: GoalsLock?,
        val error: ArtifactError?,
    )

    /**
     * Artifacts win over the seeded persona draft, field-group by field-group. The persona
     * seed is only built when **no blocker-calendar artifact exists** — otherwise every
     * launch would push a fresh set of fixtures into a calendar the athlete has already
     * edited. A half-finished onboarding restores what was completed and leaves the rest at
     * its defaults; the athlete resumes at step 1 with those steps prefilled (no step
     * pointer is persisted — nothing downstream needs one).
     */
    private fun loadFromArtifacts(): Bootstrap {
        val seeded = { OnboardingDraft(constraints = Seed.constraints(today)) }
        return try {
            val profile = repository.readProfile()
            val status = repository.readStatus()
            val goals = repository.readGoals()
            val calendar = repository.readBlockerCalendar()

            // Blocker ids must not restart at 0 alongside the process (story 4).
            calendar?.reseedIds()

            val fallback = calendar
                ?.let { OnboardingDraft(constraints = it.toConstraints()) }
                ?: seeded()
            var hydrated = hydrateDraft(fallback, profile, status, goals, calendar)

            val lock = goals?.lockedForCycle
            if (lock != null) hydrated = hydrated.copy(status = Status.APPROVED)

            Bootstrap(
                draft = hydrated,
                // An approved cycle skips onboarding entirely — the athlete lands where they
                // left off, not back on step 1 with a locked cycle behind them.
                step = if (lock != null) 4 else 1,
                lock = lock,
                error = null,
            )
        } catch (e: ArtifactException) {
            Bootstrap(draft = seeded(), step = 1, lock = null, error = e.error)
        }
    }

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
    // Ids survive process death: they are persisted and the counter is re-seeded on launch.

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
        1 -> ProfileRules.baseDataValid(draft.profile) &&
            // At least one category must remain a goal.
            draft.profile.assessment.values.any { !it.dontCare }
        2 -> draft.priorities.isNotEmpty()
        else -> true
    }

    /** [fromStep] is the calling screen's own step — a double-tap racing the screen
     *  transition (or a stale CTA on the outgoing screen) becomes a no-op. */
    fun continueFromStep(fromStep: Int) {
        if (fromStep != step || !stepValid()) return
        persistArtifacts()
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
        val proposal = draft.proposal ?: return
        if (artifactsBlocked) {
            // Refusing beats confirming a lock that was never written.
            toast("Can't approve — saved data is unreadable and must not be overwritten.")
            return
        }

        // The lock must be durable *before* the athlete sees the confirmation, so this write
        // is awaited and a failure leaves the proposal un-approved rather than confirming
        // something process death could erase.
        val committed = runArtifacts {
            persistArtifacts(force = true)
            goalsLock = repository.lockGoals(
                GoalsLock(approvedAt = clock.instant(), startDate = proposal.startDate, endDate = proposal.endDate),
            ).lockedForCycle
            // The daily-tracking epic needs somewhere to write from day one (story 5).
            // Never clobbers an existing log — a second cycle keeps its history.
            repository.initializeProgressionLogIfAbsent()
        }
        if (!committed) return

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

    // --- Artifact writes (stories 1–5) ------------------------------------

    /**
     * The write rule: on every forward step transition and at approval, rewrite every
     * artifact whose source fields could have changed. They are a few kB each, so all of
     * them are rewritten rather than tracking dirty fields — which is also what makes
     * back-then-forward edits correct: toggling a don't-care on step 1 after step 2 was
     * completed reorders priorities, and the goals artifact reflects it on the way forward.
     *
     * A failed write on a step transition is surfaced and the athlete keeps moving; only
     * approval refuses to proceed, because that is the one moment where a confirmation the
     * athlete has seen must be backed by something on disk.
     */
    private fun persistArtifacts(force: Boolean = false) {
        if (artifactsBlocked) return
        val now = clock.instant()
        val write = {
            repository.writeProfile(draft.toProfileArtifact(now))
            repository.writeStatus(draft.toStatusArtifact(now))
            // Once locked, goals are refused by the repository too — this just avoids
            // walking into an error the athlete can do nothing about.
            if (goalsLock == null) repository.writeGoals(draft.toGoalsArtifact(now))
            repository.writeBlockerCalendar(draft.constraints.toArtifact(now))
        }
        if (force) write() else runArtifacts(write)
    }

    /** Runs artifact work, turning a named failure into a snackbar. True when it committed. */
    private fun runArtifacts(block: () -> Unit): Boolean = try {
        block()
        true
    } catch (e: ArtifactException) {
        toast("Couldn't save — ${e.error.message}")
        false
    }

    // --- Post-approval summary --------------------------------------------

    /**
     * What the approved screen shows. Live from the proposal in the session that approved
     * it; after a restart from the goals artifact alone — the Mesocycle Plan itself is the
     * next epic's artifact, so nothing here invents plan detail that was not persisted.
     */
    data class ApprovedSummary(
        val startDate: LocalDate,
        val focusThisCycle: List<Category>,
        val queuedForLater: List<Category>,
    )

    val approvedSummary: ApprovedSummary?
        get() = draft.proposal?.let {
            ApprovedSummary(it.startDate, it.focusThisCycle, it.queuedForLater)
        } ?: goalsLock?.let {
            ApprovedSummary(it.startDate, draft.focus, draft.queued)
        }

    private fun update(block: OnboardingDraft.() -> OnboardingDraft) {
        draft = draft.block()
    }

    private fun toast(msg: String) {
        viewModelScope.launch { snackbars.send(msg) }
    }
}
