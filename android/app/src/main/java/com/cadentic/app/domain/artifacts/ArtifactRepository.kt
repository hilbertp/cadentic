package com.cadentic.app.domain.artifacts

/**
 * The one way in and out of artifact storage. Callers never touch files or a database, so
 * the on-device store can be swapped for a server without them noticing — the same
 * swap-for-server philosophy as the `ProposalEngine` stub.
 *
 * Reads return `null` for "not written yet" and throw [ArtifactException] for "there is
 * something there but it cannot be trusted" (unknown newer `schemaVersion`, corrupt file).
 * Writes stamp `updatedAt` themselves so no caller can backdate an artifact.
 */
interface ArtifactRepository {

    fun readProfile(): AthleteProfileArtifact?
    fun writeProfile(profile: AthleteProfileArtifact)

    fun readStatus(): AthleteStatusArtifact?
    fun writeStatus(status: AthleteStatusArtifact)

    fun readGoals(): AthleteGoalsArtifact?

    /**
     * Rejected with [ArtifactError.GoalsLocked] once a cycle is approved. The lock lives in
     * the artifact itself, so it survives process death — a restart does not reopen it.
     */
    fun writeGoals(goals: AthleteGoalsArtifact)

    /**
     * Freezes the current goals for the approved cycle. Returns once the lock is durable —
     * the approval the athlete sees confirmed cannot be lost to process death.
     */
    fun lockGoals(lock: GoalsLock): AthleteGoalsArtifact

    fun readBlockerCalendar(): BlockerCalendarArtifact?
    fun writeBlockerCalendar(calendar: BlockerCalendarArtifact)

    fun readProgressionLog(): ProgressionLogArtifact?
    fun writeProgressionLog(log: ProgressionLogArtifact)

    /**
     * Creates the empty log if it does not exist yet, and leaves an existing one untouched —
     * a second cycle's onboarding must never erase logged training.
     */
    fun initializeProgressionLogIfAbsent(): ProgressionLogArtifact

    // --- Mesocycle Plan (Epic 2 story 4) ---------------------------------

    fun readMesocyclePlan(): MesocyclePlanArtifact?

    /**
     * Written **before** the goals lock at approval. The lock is the commit point: a plan on
     * disk without one is an unapproved plan, and the next generation overwrites it.
     */
    fun writeMesocyclePlan(plan: MesocyclePlanArtifact)

    /** Clears an unapproved plan. Never called while a lock exists — see [lockGoals]. */
    fun deleteMesocyclePlan()
}
