package com.cadentic.app.data

import com.cadentic.app.domain.artifacts.ARTIFACT_SCHEMA_VERSION
import com.cadentic.app.domain.artifacts.ArtifactError
import com.cadentic.app.domain.artifacts.ArtifactId
import com.cadentic.app.domain.artifacts.ArtifactRepository
import com.cadentic.app.domain.artifacts.AthleteGoalsArtifact
import com.cadentic.app.domain.artifacts.AthleteProfileArtifact
import com.cadentic.app.domain.artifacts.AthleteStatusArtifact
import com.cadentic.app.domain.artifacts.BlockerCalendarArtifact
import com.cadentic.app.domain.artifacts.GoalsLock
import com.cadentic.app.domain.artifacts.ProgressionLogArtifact
import com.cadentic.app.domain.artifacts.raise
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.Instant

/**
 * **Storage decision (Epic 1 open point 1): one pretty-printed JSON document per artifact,
 * in a private on-device directory, behind [ArtifactRepository].** Chosen over Room and
 * DataStore because the artifacts are whole documents that are always read and written
 * whole, are tiny (a few kB), and are destined to become request/response bodies against a
 * server — a schema-per-table mapping would buy nothing here and a Proto schema would make
 * the wire format harder to read during development. The trade-off accepted: no querying
 * and no partial updates, neither of which any consumer needs.
 *
 * **Writes are synchronous.** Each is a single small file replaced atomically, and story 0
 * requires the approval to be durable *before* the UI confirms it; a background write would
 * add a way to lose an approval the athlete already saw. If a future artifact grows enough
 * to matter, move the call sites onto a background dispatcher — the interface already allows it.
 */
class JsonArtifactRepository(
    private val dir: File,
    private val clock: Clock = Clock.systemUTC(),
) : ArtifactRepository {

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        // schemaVersion carries a default and must still be written on every document.
        encodeDefaults = true
        // A skipped rating is stored as an explicit null, never omitted (story 2).
        explicitNulls = true
    }

    private fun now(): Instant = clock.instant()

    private fun file(id: ArtifactId) = File(dir, id.fileName)

    // --- Profile ----------------------------------------------------------

    override fun readProfile(): AthleteProfileArtifact? =
        read(ArtifactId.ATHLETE_PROFILE, AthleteProfileArtifact.serializer())

    override fun writeProfile(profile: AthleteProfileArtifact) =
        write(ArtifactId.ATHLETE_PROFILE, AthleteProfileArtifact.serializer(), profile.copy(updatedAt = now()))

    // --- Status -----------------------------------------------------------

    override fun readStatus(): AthleteStatusArtifact? =
        read(ArtifactId.ATHLETE_STATUS, AthleteStatusArtifact.serializer())

    override fun writeStatus(status: AthleteStatusArtifact) =
        write(ArtifactId.ATHLETE_STATUS, AthleteStatusArtifact.serializer(), status.copy(updatedAt = now()))

    // --- Goals (lock enforced here) ---------------------------------------

    override fun readGoals(): AthleteGoalsArtifact? =
        read(ArtifactId.ATHLETE_GOALS, AthleteGoalsArtifact.serializer())

    /**
     * **Lock enforcement lives in the repository (Epic 1 open point 3)**, not in the
     * ViewModel or a domain service: it is the single write mechanism, so a caller that
     * has not heard of the lock — a later engine, a migration, a background sync — still
     * cannot slip a change into an approved cycle.
     */
    override fun writeGoals(goals: AthleteGoalsArtifact) {
        readGoals()?.lockedForCycle?.let { ArtifactError.GoalsLocked(it).raise() }
        write(
            ArtifactId.ATHLETE_GOALS,
            AthleteGoalsArtifact.serializer(),
            goals.copy(updatedAt = now()),
        )
    }

    override fun lockGoals(lock: GoalsLock): AthleteGoalsArtifact {
        val current = readGoals() ?: ArtifactError.Missing(ArtifactId.ATHLETE_GOALS).raise()
        current.lockedForCycle?.let { ArtifactError.GoalsLocked(it).raise() }
        val locked = current.copy(updatedAt = now(), lockedForCycle = lock)
        write(ArtifactId.ATHLETE_GOALS, AthleteGoalsArtifact.serializer(), locked)
        return locked
    }

    // --- Blocker calendar -------------------------------------------------

    override fun readBlockerCalendar(): BlockerCalendarArtifact? =
        read(ArtifactId.BLOCKER_CALENDAR, BlockerCalendarArtifact.serializer())

    override fun writeBlockerCalendar(calendar: BlockerCalendarArtifact) =
        write(ArtifactId.BLOCKER_CALENDAR, BlockerCalendarArtifact.serializer(), calendar.copy(updatedAt = now()))

    // --- Progression log --------------------------------------------------

    override fun readProgressionLog(): ProgressionLogArtifact? =
        read(ArtifactId.PROGRESSION_LOG, ProgressionLogArtifact.serializer())

    override fun writeProgressionLog(log: ProgressionLogArtifact) =
        write(ArtifactId.PROGRESSION_LOG, ProgressionLogArtifact.serializer(), log.copy(updatedAt = now()))

    override fun initializeProgressionLogIfAbsent(): ProgressionLogArtifact {
        readProgressionLog()?.let { return it }
        val empty = ProgressionLogArtifact(updatedAt = now(), entries = emptyList())
        write(ArtifactId.PROGRESSION_LOG, ProgressionLogArtifact.serializer(), empty)
        return empty
    }

    // --- Document I/O -----------------------------------------------------

    private fun <T> read(id: ArtifactId, serializer: KSerializer<T>): T? {
        val f = file(id)
        if (!f.exists()) return null
        val text = try {
            f.readText()
        } catch (e: IOException) {
            ArtifactError.Corrupt(id, e.message ?: "read failed").raise()
        }
        val root = try {
            json.parseToJsonElement(text) as? JsonObject
                ?: ArtifactError.Corrupt(id, "top level is not a JSON object").raise()
        } catch (e: Exception) {
            ArtifactError.Corrupt(id, e.message ?: "malformed JSON").raise()
        }
        // Version gate runs before decoding: a newer document may have fields whose meaning
        // this build would misread, so it is refused rather than partially understood.
        val version = (root["schemaVersion"]?.jsonPrimitive?.intOrNullSafe())
            ?: ArtifactError.MissingField(id, "schemaVersion").raise()
        if (version > ARTIFACT_SCHEMA_VERSION) {
            ArtifactError.UnsupportedSchemaVersion(id, version, ARTIFACT_SCHEMA_VERSION).raise()
        }
        return try {
            json.decodeFromJsonElement(serializer, root)
        } catch (e: Exception) {
            ArtifactError.Corrupt(id, e.message ?: "does not match schema v$version").raise()
        }
    }

    /**
     * Atomic: the document is written to a sibling temp file, flushed to disk, then moved
     * over the target with `ATOMIC_MOVE`. A reader — or a crash — sees either the previous
     * document or the new one, never a half-written file.
     */
    private fun <T> write(id: ArtifactId, serializer: KSerializer<T>, value: T) {
        try {
            if (!dir.exists() && !dir.mkdirs()) {
                ArtifactError.WriteFailed(id, "cannot create ${dir.path}").raise()
            }
            val target = file(id)
            val tmp = File(dir, "${id.fileName}.tmp")
            tmp.outputStream().use { out ->
                out.write(json.encodeToString(serializer, value).toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (e: IOException) {
            ArtifactError.WriteFailed(id, e.message ?: "I/O error").raise()
        }
    }
}

private fun kotlinx.serialization.json.JsonPrimitive.intOrNullSafe(): Int? =
    runCatching { int }.getOrNull()
