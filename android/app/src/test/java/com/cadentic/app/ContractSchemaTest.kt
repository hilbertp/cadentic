package com.cadentic.app

import com.cadentic.app.domain.Category
import com.cadentic.app.domain.EngineError
import com.cadentic.app.domain.Lane
import com.cadentic.app.domain.Rating
import com.cadentic.app.domain.Sex
import com.cadentic.app.domain.Strain
import com.cadentic.app.domain.artifacts.DayType
import com.cadentic.app.domain.artifacts.GenerationMode
import com.cadentic.app.domain.artifacts.MESOCYCLE_PLAN_SCHEMA_VERSION
import com.cadentic.app.domain.artifacts.MESO_REQUEST_SCHEMA_VERSION
import com.cadentic.app.domain.artifacts.MesocyclePlanArtifact
import com.cadentic.app.domain.artifacts.PhaseType
import com.cadentic.app.domain.artifacts.PlannedDay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **The contract, asserted rather than assumed** (Epic 2 story 0).
 *
 * `contracts/mesocycle-api.schema.json` is one file that the backend validates against at
 * runtime and that Gradle puts on this module's test classpath. These assertions are what
 * make "no hand-mirrored enums" true rather than aspirational: add a day type on one side and
 * this test fails, instead of an athlete's plan failing in the field.
 *
 * A JSON Schema validator is deliberately *not* shipped in the APK. The app decodes with
 * kotlinx.serialization, which already rejects unknown keys and bad enum values; what it
 * cannot catch is the two definitions drifting apart, and that is exactly what runs here.
 */
class ContractSchemaTest {

    private val contract: JsonObject = javaClass.classLoader!!
        .getResourceAsStream("mesocycle-api.schema.json")!!
        .use { Json.parseToJsonElement(it.reader().readText()).jsonObject }

    private val defs: JsonObject = contract["\$defs"]!!.jsonObject

    private fun schemaEnum(name: String): List<String> =
        defs[name]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }

    private fun required(name: String): List<String> =
        defs[name]!!.jsonObject["required"]!!.jsonArray.map { it.jsonPrimitive.content }

    private fun properties(name: String): Set<String> =
        defs[name]!!.jsonObject["properties"]!!.jsonObject.keys

    /** The serialized field names of a Kotlin @Serializable class, in declaration order. */
    private fun <T> fieldsOf(serializer: kotlinx.serialization.KSerializer<T>): List<String> =
        (0 until serializer.descriptor.elementsCount).map { serializer.descriptor.getElementName(it) }

    // --- Enums ----------------------------------------------------------------

    @Test
    fun `every shared enum matches the contract`() {
        assertEquals(schemaEnum("Category"), Category.entries.map { it.name })
        assertEquals(schemaEnum("Lane"), Lane.entries.map { it.name })
        assertEquals(schemaEnum("Rating"), Rating.entries.map { it.name })
        assertEquals(schemaEnum("Sex"), Sex.entries.map { it.name })
        assertEquals(schemaEnum("Strain"), Strain.entries.map { it.name })
        assertEquals(schemaEnum("DayType"), DayType.entries.map { it.name })
        assertEquals(schemaEnum("PhaseType"), PhaseType.entries.map { it.name })
    }

    @Test
    fun `intensity is the strain vocabulary on both sides`() {
        // Kotlin says so with a typealias; the contract says so with an identical enum. If
        // one of them ever stops being true, the product has two intensity languages.
        assertEquals(schemaEnum("Intensity"), Strain.entries.map { it.name })
    }

    @Test
    fun `generation modes match their wire names`() {
        val schema = defs["GeneratedBy"]!!.jsonObject["properties"]!!.jsonObject["mode"]!!
            .jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        val encoded = GenerationMode.entries.map {
            Json.encodeToString(GenerationMode.serializer(), it).trim('"')
        }
        assertEquals(schema, encoded)
    }

    @Test
    fun `every wire error code has an app-side meaning`() {
        val wire = schemaEnum("ErrorCode").toSet()
        val known = EngineError.entries.mapNotNull { it.wireCode }.toSet()
        assertEquals(
            "the app and the contract disagree about which errors exist",
            wire,
            known,
        )
        // Every code the backend can send maps to something other than the catch-all, so a
        // named failure is never flattened into "something went wrong".
        wire.forEach {
            assertTrue(it, EngineError.fromWireCode(it) != EngineError.UNEXPECTED)
        }
    }

    @Test
    fun `the client-only errors are deliberately absent from the wire`() {
        // backend-unreachable means the app never reached the backend, so it cannot arrive
        // *from* the backend. Putting it in the contract would be a category error.
        val wire = schemaEnum("ErrorCode")
        assertTrue(EngineError.BACKEND_UNREACHABLE.wireCode == null)
        assertTrue(EngineError.UNEXPECTED.wireCode == null)
        assertTrue(wire.none { it == "backend-unreachable" })
    }

    // --- Shapes ---------------------------------------------------------------

    @Test
    fun `the plan artifact carries exactly the contract's fields, plus its own updatedAt`() {
        val kotlin = fieldsOf(MesocyclePlanArtifact.serializer()).toSet()
        val schema = properties("MesocyclePlan")
        // updatedAt is a persistence stamp: the backend returns a plan, the app records when
        // it wrote one. It is the only field that is legitimately app-side.
        assertEquals(schema + "updatedAt", kotlin)
        assertTrue(schema.containsAll(required("MesocyclePlan")))
    }

    @Test
    fun `a planned day has no field an exercise could hide in`() {
        assertEquals(properties("PlannedDay"), fieldsOf(PlannedDay.serializer()).toSet())
        assertEquals(setOf("day", "type", "intensity"), properties("PlannedDay"))
    }

    @Test
    fun `the plan carries no headline or coach note on either side`() {
        // PRD §8: plan surfaces never render model free text. The only prose in the contract
        // is the progression pair, which the proposal screen does not show.
        val schema = properties("MesocyclePlan")
        assertTrue(schema.none { it == "headline" || it == "coachNote" })
        assertTrue(fieldsOf(MesocyclePlanArtifact.serializer()).none { it == "headline" || it == "coachNote" })
    }

    @Test
    fun `schema versions agree across the wire`() {
        assertEquals(
            defs["MesocyclePlan"]!!.jsonObject["properties"]!!.jsonObject["schemaVersion"]!!
                .jsonObject["const"]!!.jsonPrimitive.int,
            MESOCYCLE_PLAN_SCHEMA_VERSION,
        )
        assertEquals(
            defs["MesoRequestPayload"]!!.jsonObject["properties"]!!.jsonObject["schemaVersion"]!!
                .jsonObject["const"]!!.jsonPrimitive.int,
            MESO_REQUEST_SCHEMA_VERSION,
        )
    }

    @Test
    fun `the model is never asked for the fields the backend stamps itself`() {
        // The draft is what the model fills in; schemaVersion and generatedBy are the
        // backend's, and asking for them would invite a model-authored provenance record.
        val draft = properties("PlanDraft")
        assertTrue(draft.none { it == "schemaVersion" || it == "generatedBy" })
        // lane/focus/queued *are* asked for — that is what makes a contradiction detectable.
        assertTrue(draft.containsAll(listOf("lane", "focus", "queued")))
    }
}
