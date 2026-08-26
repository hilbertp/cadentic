package com.cadentic.app.domain.artifacts

/**
 * Every artifact failure names the artifact, and where it applies the field. Nothing here
 * degrades silently: a partial payload never reaches the Mesocycle Engine, and an artifact
 * written by a newer app version is refused rather than half-read.
 */
sealed class ArtifactError {
    abstract val artifact: ArtifactId
    abstract val message: String

    /** Written by a newer build than this one understands — refuse, never guess. */
    data class UnsupportedSchemaVersion(
        override val artifact: ArtifactId,
        val found: Int,
        val supported: Int,
    ) : ArtifactError() {
        override val message =
            "${artifact.fileName}: schemaVersion $found is newer than the supported $supported"
    }

    /** Present but unreadable — truncated, hand-edited, or a bad enum/date value. */
    data class Corrupt(
        override val artifact: ArtifactId,
        val reason: String,
    ) : ArtifactError() {
        override val message = "${artifact.fileName}: unreadable ($reason)"
    }

    data class Missing(override val artifact: ArtifactId) : ArtifactError() {
        override val message = "${artifact.fileName}: not written yet"
    }

    data class MissingField(
        override val artifact: ArtifactId,
        val field: String,
    ) : ArtifactError() {
        override val message = "${artifact.fileName}: required field '$field' is missing"
    }

    data class InvalidField(
        override val artifact: ArtifactId,
        val field: String,
        val reason: String,
    ) : ArtifactError() {
        override val message = "${artifact.fileName}: field '$field' is invalid ($reason)"
    }

    /** Priorities provably never change within a cycle (PRD §5.2) — the write is refused. */
    data class GoalsLocked(val lock: GoalsLock) : ArtifactError() {
        override val artifact = ArtifactId.ATHLETE_GOALS
        override val message =
            "${artifact.fileName}: locked for the cycle approved at ${lock.approvedAt} " +
                "(${lock.startDate} → ${lock.endDate}); goals change only between cycles"
    }

    data class WriteFailed(
        override val artifact: ArtifactId,
        val reason: String,
    ) : ArtifactError() {
        override val message = "${artifact.fileName}: write failed ($reason)"
    }
}

class ArtifactException(val error: ArtifactError) : RuntimeException(error.message)

fun ArtifactError.raise(): Nothing = throw ArtifactException(this)
