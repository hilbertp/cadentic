package com.cadentic.app

import com.cadentic.app.data.HttpMesocycleEngine
import com.cadentic.app.domain.EngineError
import com.cadentic.app.domain.MesocycleRequest
import com.cadentic.app.domain.OnboardingDraft
import com.cadentic.app.domain.Seed
import com.cadentic.app.domain.MesocycleResult
import com.cadentic.app.domain.artifacts.GenerationMode
import com.cadentic.app.domain.artifacts.MesoRequestAssembler
import com.cadentic.app.domain.artifacts.MesoRequestPayload
import com.cadentic.app.domain.artifacts.MesoRequestResult
import com.cadentic.app.domain.artifacts.MesocyclePlanArtifact
import com.cadentic.app.domain.artifacts.toArtifact
import com.cadentic.app.domain.artifacts.toGoalsArtifact
import com.cadentic.app.domain.artifacts.toProfileArtifact
import com.cadentic.app.domain.artifacts.toStatusArtifact
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The HTTP client (Epic 2 story 5) against a real socket: what goes on the wire, and what
 * every kind of answer becomes on this side.
 *
 * MockWebServer stands in for the backend, so nothing here reaches a provider — and the
 * assertions are about the *contract*, not about the plan's contents.
 */
class HttpMesocycleEngineTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val json = Json { encodeDefaults = true; explicitNulls = true }

    @Before fun start() {
        Restart.simulate()
        server = MockWebServer().apply { start() }
    }

    @After fun stop() = server.shutdown()

    private fun engine(secret: String = SECRET) =
        HttpMesocycleEngine(baseUrl = server.url("/").toString(), sharedSecret = secret)

    /**
     * A real payload, assembled from real artifacts. Written straight through the mapping
     * rather than by driving the ViewModel: this test has no Main dispatcher, and what it is
     * about is the wire, not the wizard.
     */
    private fun payload(): MesoRequestPayload {
        val repository = repositoryIn(File(tmp.root, "artifacts"))
        val draft = OnboardingDraft(constraints = Seed.constraints(TODAY))
        val now = FIXED_CLOCK.instant()
        repository.writeProfile(draft.toProfileArtifact(now))
        repository.writeStatus(draft.toStatusArtifact(now))
        repository.writeGoals(draft.toGoalsArtifact(now))
        repository.writeBlockerCalendar(draft.constraints.toArtifact(now))
        return (MesoRequestAssembler(repository).assemble(TODAY) as MesoRequestResult.Ok).payload
    }

    private fun request() = MesocycleRequest(payload(), "req-under-test")

    private fun planBody(): String =
        json.encodeToString(MesocyclePlanArtifact.serializer(), samplePlan().copy(updatedAt = null))

    private fun errorBody(code: String) = """{"error":{"code":"$code","message":"nope"}}"""

    private fun generate(secret: String = SECRET): MesocycleResult =
        runBlocking { engine(secret).generate(request()) }

    // --- What goes on the wire -------------------------------------------------

    @Test
    fun `the body is the meso-request payload verbatim, with the request id in a header`() {
        server.enqueue(MockResponse().setBody(planBody()))
        val sent = request()
        runBlocking { engine().generate(sent) }

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v1/mesocycle-proposal", recorded.path)
        assertEquals(SECRET, recorded.getHeader("X-Cadentic-Secret"))
        assertEquals("req-under-test", recorded.getHeader("X-Request-Id"))

        // No envelope: the bytes on the wire are the assembled payload and nothing else, so
        // the object the assembler produced is the object the backend validates.
        val text = recorded.body.readUtf8()
        assertEquals(json.encodeToString(MesoRequestPayload.serializer(), sent.payload), text)
        assertEquals(
            setOf("schemaVersion", "requestDate", "profile", "goals", "status", "blockerCalendar"),
            Json.parseToJsonElement(text).jsonObject.keys,
        )
    }

    @Test
    fun `no Anthropic credential appears anywhere in the request`() {
        server.enqueue(MockResponse().setBody(planBody()))
        runBlocking { engine().generate(request()) }

        // The plan token lives in the backend's environment and nowhere else — the app has
        // never held one and posts nothing that could carry one.
        val recorded = server.takeRequest()
        val whole = (recorded.headers.toString() + recorded.body.readUtf8()).lowercase()
        assertFalse(whole.contains("sk-ant"))
        assertFalse(whole.contains("anthropic"))
        assertNull(recorded.getHeader("Authorization"))
        assertNull(recorded.getHeader("x-api-key"))
    }

    // --- What comes back -------------------------------------------------------

    @Test
    fun `a plan is decoded into the artifact`() {
        server.enqueue(MockResponse().setBody(planBody()))
        val result = generate()

        val plan = (result as MesocycleResult.Ok).plan
        assertEquals(8, plan.durationWeeks)
        assertEquals(GenerationMode.MAX_PLAN_OAUTH, plan.generatedBy.mode)
        // The wire carries no updatedAt; the repository stamps it at write time.
        assertNull(plan.updatedAt)
    }

    @Test
    fun `every typed backend error maps to its app-side meaning`() {
        val expected = mapOf(
            400 to ("payload-invalid" to EngineError.PAYLOAD_INVALID),
            401 to ("unauthorized" to EngineError.UNAUTHORIZED),
            429 to ("rate-limited" to EngineError.RATE_LIMITED),
            501 to ("provider-not-available" to EngineError.PROVIDER_NOT_AVAILABLE),
            502 to ("provider-unreachable" to EngineError.PROVIDER_UNREACHABLE),
            504 to ("timeout" to EngineError.TIMEOUT),
        )
        expected.forEach { (status, pair) ->
            val (code, error) = pair
            server.enqueue(MockResponse().setResponseCode(status).setBody(errorBody(code)))
            assertEquals(code, error, (generate() as MesocycleResult.Failed).error)
        }
    }

    @Test
    fun `format-failed and auth-failed share a status but not a meaning`() {
        server.enqueue(MockResponse().setResponseCode(502).setBody(errorBody("format-failed")))
        assertEquals(EngineError.FORMAT_FAILED, (generate() as MesocycleResult.Failed).error)

        server.enqueue(MockResponse().setResponseCode(502).setBody(errorBody("auth-failed")))
        assertEquals(EngineError.AUTH_FAILED, (generate() as MesocycleResult.Failed).error)
    }

    @Test
    fun `an error code the app has never heard of is not a crash`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody(errorBody("something-new")))
        assertEquals(EngineError.UNEXPECTED, (generate() as MesocycleResult.Failed).error)
    }

    @Test
    fun `an error body that is not JSON is still a named failure`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("<html>gateway error</html>"))
        assertEquals(EngineError.UNEXPECTED, (generate() as MesocycleResult.Failed).error)
    }

    @Test
    fun `a 200 the app cannot decode is reported, never half-read`() {
        // The two sides disagreeing about the contract must not produce a partial plan.
        // ContractSchemaTest exists so this is caught at build time, not here.
        server.enqueue(MockResponse().setBody("""{"schemaVersion":1,"surprise":true}"""))
        assertEquals(EngineError.UNEXPECTED, (generate() as MesocycleResult.Failed).error)
    }

    @Test
    fun `an unreachable backend is backend-unreachable, not a provider problem`() {
        server.shutdown()
        assertEquals(EngineError.BACKEND_UNREACHABLE, (generate() as MesocycleResult.Failed).error)
    }

    @Test
    fun `a connection dropped mid-plan is a transport failure, not a contract mismatch`() {
        // The backend starts sending a perfectly good plan and the connection dies halfway.
        // Reading the body is part of the call, so this must not be mistaken for a plan the
        // app failed to understand — the fix is a working network, not a schema change.
        server.enqueue(
            MockResponse()
                .setBody(planBody())
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )
        assertEquals(EngineError.BACKEND_UNREACHABLE, (generate() as MesocycleResult.Failed).error)
    }

    // --- Cancellation ----------------------------------------------------------

    @Test
    fun `cancelling the coroutine cancels the call, headers or body`() = runBlocking {
        // Two ways a generation can be slow, and `back()` has to work in both. The delay
        // before headers is the realistic one — the backend holds the request open while
        // Claude thinks — and the body delay is the case a naive implementation misses,
        // because the blocking read has already left the cancellable suspend behind.
        // SLOW_MS is a few seconds rather than a few minutes only so MockWebServer can still
        // shut down at teardown; it is an order of magnitude above the assertion, which is
        // what makes the assertion mean something.
        listOf(
            MockResponse().setHeadersDelay(SLOW_MS, TimeUnit.MILLISECONDS).setBody(planBody()),
            MockResponse().setBody(planBody()).setBodyDelay(SLOW_MS, TimeUnit.MILLISECONDS),
        ).forEach { slow ->
            server.enqueue(slow)
            val started = System.nanoTime()
            val job = launch { engine().generate(request()) }
            delay(200)
            job.cancelAndJoin()
            val elapsedMs = (System.nanoTime() - started) / 1_000_000

            // Without cancellation reaching the socket, this waits out the whole response.
            assertTrue("took ${elapsedMs}ms to abandon the call", elapsedMs < SLOW_MS / 2)
        }
    }

    private companion object {
        const val SECRET = "dev-secret"
        const val SLOW_MS = 3_000L
    }
}
