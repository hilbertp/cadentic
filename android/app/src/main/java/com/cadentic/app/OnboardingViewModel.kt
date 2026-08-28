package com.cadentic.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cadentic.app.domain.Category
import com.cadentic.app.domain.EngineError
import com.cadentic.app.domain.MAX_FOCUS_COUNT
import com.cadentic.app.domain.Ids
import com.cadentic.app.domain.Lane
import com.cadentic.app.domain.MesocycleEngine
import com.cadentic.app.domain.MesocycleRequest
import com.cadentic.app.domain.MesocycleResult
import com.cadentic.app.domain.OnboardingDraft
import com.cadentic.app.domain.OneOffBlocker
import com.cadentic.app.domain.ProfileRules
import com.cadentic.app.domain.Rating
import com.cadentic.app.domain.RecurringBlocker
import com.cadentic.app.domain.Seed
import com.cadentic.app.domain.SelfAssessment
import com.cadentic.app.domain.Sex
import com.cadentic.app.domain.Status
import com.cadentic.app.domain.Strain
import com.cadentic.app.domain.artifacts.ArtifactError
import com.cadentic.app.domain.artifacts.ArtifactException
import com.cadentic.app.domain.artifacts.ArtifactId
import com.cadentic.app.domain.artifacts.ArtifactRepository
import com.cadentic.app.domain.artifacts.GoalsLock
import com.cadentic.app.domain.artifacts.MesoRequestAssembler
import com.cadentic.app.domain.artifacts.MesoRequestResult
import com.cadentic.app.domain.artifacts.hydrateDraft
import com.cadentic.app.domain.artifacts.raise
import com.cadentic.app.domain.artifacts.reseedIds
import com.cadentic.app.domain.artifacts.toConstraints
import com.cadentic.app.domain.artifacts.toArtifact
import com.cadentic.app.domain.artifacts.toGoalsArtifact
import com.cadentic.app.domain.artifacts.toProfileArtifact
import com.cadentic.app.domain.artifacts.toStatusArtifact
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.UUID

class OnboardingViewModel(
    private val repository: ArtifactRepository,
    /**
     * The Mesocycle Engine (Epic 2 story 5). An interface, not the HTTP client: the ViewModel
     * has never known where a proposal comes from, and that is what let the local stub be
     * swapped for a real generation without touching the screens.
     */
    private val engine: MesocycleEngine,
    // One clock read: the grid anchor and the seeded game days must agree even across midnight.
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

    /**
     * When the in-flight generation started, or null when nothing is generating. The
     * GeneratingScreen counts up from this — it lives here rather than in the composable so
     * that backgrounding the app and coming back shows the true elapsed time instead of
     * restarting the clock at zero.
     */
    var generationStartedAt by mutableStateOf<Instant?>(null)
        private set

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
     * launch would push a fresh set of game days into a calendar the athlete has already
     * edited. A half-finished onboarding restores what was completed and leaves the rest at
     * its defaults; the athlete resumes at step 1 with those steps prefilled (no step
     * pointer is persisted — nothing downstream needs one).
     *
     * **The goals lock decides approval; the plan decides how much the approved screen can
     * say** (Epic 2 story 4). The write order is plan-then-lock, so a lock always had a plan
     * under it — and the lock is the commit point, so its presence alone means approved. The
     * reverse half-state is the one the order can actually produce: a plan with no lock is an
     * abandoned generation, so it is not loaded, the athlete lands back in the proposal flow,
     * and the next generate deletes it.
     *
     * A generation cut short by process death leaves nothing behind at all — GENERATING is
     * never persisted — so the athlete comes back to their data with nothing half-approved.
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
            if (lock != null) {
                hydrated = hydrated.copy(
                    status = Status.APPROVED,
                    plan = repository.readMesocyclePlan(),
                )
            }

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

    /** Seeded game days are one-offs too, so this is how "I'm not playing this one" works. */
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
        val persisted = persistArtifacts()
        when (step) {
            1, 2 -> step += 1
            // Generation reads artifacts, so it must not start until step 3's writes have
            // landed — otherwise the payload would miss the blockers just entered. The writes
            // above are synchronous and awaited; a failed write stops the generation rather
            // than sending a stale athlete to the engine.
            3 -> if (persisted) generate() else toast("Couldn't save your blockers, so there's nothing to generate from.")
        }
    }

    /**
     * The real generation (Epic 2 story 5). The local stub used to fill four seconds with a
     * delay; this suspends for as long as the engine takes — multi-minute waits are expected
     * and the screen says so.
     *
     * Every attempt mints a fresh request id. The backend joins duplicates of the *same* id
     * to one generation, so a double tap costs one plan, while a deliberate retry after a
     * failure is a new request rather than a replay of the one that failed.
     */
    private fun generate() {
        if (draft.status != Status.DRAFT && draft.status != Status.FAILED) return

        // A plan on disk with no goals lock is an unapproved leftover from an abandoned
        // attempt. It goes now, so a generation that fails cannot leave something behind that
        // looks like a current plan.
        if (goalsLock == null && !artifactsBlocked) {
            runArtifacts { repository.deleteMesocyclePlan() }
        }

        update { copy(status = Status.GENERATING, plan = null, generationError = null) }
        generationStartedAt = clock.instant()

        generationJob = viewModelScope.launch {
            when (val outcome = requestPlan()) {
                is MesocycleResult.Ok -> {
                    update { copy(plan = outcome.plan, status = Status.PROPOSED) }
                    step = 4
                }
                is MesocycleResult.Failed -> {
                    update { copy(status = Status.FAILED, generationError = outcome.error) }
                    step = 4
                }
            }
            generationStartedAt = null
        }
    }

    /** Assembles from artifacts alone (Epic 1 story 6), then asks the engine. */
    private suspend fun requestPlan(): MesocycleResult =
        when (val assembled = MesoRequestAssembler(repository).assemble(today)) {
            is MesoRequestResult.Invalid -> {
                // The assembler names the artifact and the field; the screen shows one
                // sentence, and the detail goes where it is actually useful.
                toast("Can't generate — ${assembled.message}")
                MesocycleResult.Failed(EngineError.PAYLOAD_INVALID)
            }
            is MesoRequestResult.Ok -> engine.generate(
                MesocycleRequest(payload = assembled.payload, requestId = UUID.randomUUID().toString()),
            )
        }

    /** From the failure screen. A new attempt, with a new request id. */
    fun retryGeneration() {
        if (draft.status != Status.FAILED) return
        generate()
    }

    fun approve() {
        // Locks the mesocycle: fixed once approved (PRD §5.1) — priorities only change between cycles.
        if (draft.status != Status.PROPOSED) return
        val plan = draft.plan ?: return
        if (artifactsBlocked) {
            // Refusing beats confirming a lock that was never written.
            toast("Can't approve — saved data is unreadable and must not be overwritten.")
            return
        }

        // **The write order is fixed (Epic 2 story 4): plan first, lock second.**
        //
        // The lock is the commit point the UI waits on, and it is minted from the plan as it
        // was *read back off disk* — not from the copy in memory. If the plan write silently
        // produced something different, the lock would inherit the difference rather than
        // paper over it, and the dates the athlete is held to are the dates that survive a
        // restart. (This amends Epic 1 story 3, where the lock came from the in-memory
        // Proposal; Epic 1 carries the matching note.)
        val committed = runArtifacts {
            persistArtifacts(force = true)
            repository.writeMesocyclePlan(plan)
            val persisted = repository.readMesocyclePlan()
                ?: ArtifactError.Missing(ArtifactId.MESOCYCLE_PLAN).raise()
            goalsLock = repository.lockGoals(
                GoalsLock(
                    approvedAt = clock.instant(),
                    startDate = persisted.startDate,
                    endDate = persisted.endDate,
                ),
            ).lockedForCycle
            // The daily-tracking epic needs somewhere to write from day one (Epic 1 story 5).
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
            // Cancels the coroutine, which cancels the HTTP call, which closes the socket —
            // and the backend, seeing nobody left waiting, aborts the generation. Walking
            // away does not leave a plan being written for a screen no one is looking at.
            generationJob?.cancel()
            generationStartedAt = null
            update { copy(status = Status.DRAFT) }
            return true
        }
        if (draft.status == Status.APPROVED) return false
        if (step > 1) {
            if (step == 4) update { copy(status = Status.DRAFT, generationError = null) }
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
    private fun persistArtifacts(force: Boolean = false): Boolean {
        if (artifactsBlocked) return false
        val now = clock.instant()
        val write = {
            repository.writeProfile(draft.toProfileArtifact(now))
            repository.writeStatus(draft.toStatusArtifact(now))
            // Once locked, goals are refused by the repository too — this just avoids
            // walking into an error the athlete can do nothing about.
            if (goalsLock == null) repository.writeGoals(draft.toGoalsArtifact(now))
            repository.writeBlockerCalendar(draft.constraints.toArtifact(now))
        }
        if (force) {
            write()
            return true
        }
        return runArtifacts(write)
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
     * What the approved screen shows. From the persisted Mesocycle Plan once Epic 2 landed —
     * a restart now restores the real cycle, not just its dates. The goals-lock fallback
     * stays for the one state that can still reach here without a plan: a cycle approved by
     * a build that predates the plan artifact.
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
