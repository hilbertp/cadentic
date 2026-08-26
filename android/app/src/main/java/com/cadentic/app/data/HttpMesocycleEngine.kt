package com.cadentic.app.data

import com.cadentic.app.domain.EngineError
import com.cadentic.app.domain.MesocycleEngine
import com.cadentic.app.domain.MesocycleRequest
import com.cadentic.app.domain.MesocycleResult
import com.cadentic.app.domain.artifacts.MesoRequestPayload
import com.cadentic.app.domain.artifacts.MesocyclePlanArtifact
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * The Mesocycle Engine over HTTP (Epic 2 story 5) — the real call that replaced the local
 * `ProposalEngine` stub, behind the same domain boundary.
 *
 * **The app never talks to an LLM provider.** It posts the Epic 1 payload to the backend and
 * gets back a plan the backend has already validated. No prompt lives here, no Anthropic
 * credential lives here, and Mode A versus Mode B is invisible from this side: the request is
 * byte-identical either way.
 *
 * The body is the assembled payload **verbatim** — no envelope. The request id rides in a
 * header for exactly that reason.
 */
class HttpMesocycleEngine(
    private val baseUrl: String,
    private val sharedSecret: String,
    /**
     * Sits deliberately above the backend's own budget, so a slow generation ends as the
     * backend's named `timeout` rather than as a socket the app gave up on. The gap is the
     * round trip plus room for a busy dev machine.
     */
    callTimeout: Long = 6,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(callTimeout, TimeUnit.MINUTES)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(callTimeout, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.SECONDS)
        // One generation at a time; a retry is a new call, never a queued one.
        .retryOnConnectionFailure(false)
        .build(),
) : MesocycleEngine {

    private val json = Json { ignoreUnknownKeys = false; explicitNulls = true }
    private val payloadJson = Json { encodeDefaults = true; explicitNulls = true }

    override suspend fun generate(request: MesocycleRequest): MesocycleResult =
        withContext(Dispatchers.IO) {
            val url = "${baseUrl.trimEnd('/')}$PATH".toHttpUrlOrNull()
                ?: return@withContext failed(EngineError.BACKEND_UNREACHABLE)

            val body = payloadJson
                .encodeToString(MesoRequestPayload.serializer(), request.payload)
                .toRequestBody(JSON_MEDIA)

            val call = client.newCall(
                Request.Builder()
                    .url(url)
                    .post(body)
                    .header(SECRET_HEADER, sharedSecret)
                    .header(REQUEST_ID_HEADER, request.requestId)
                    .build(),
            )

            val received = try {
                call.awaitBody()
            } catch (e: IOException) {
                // Airplane mode, wrong host, dev machine asleep, connection dropped: the app
                // never got a whole answer out of the backend. Distinct from
                // provider-unreachable, which means the backend was fine and Claude was not.
                return@withContext failed(EngineError.BACKEND_UNREACHABLE)
            }

            if (!received.ok) return@withContext failed(errorFrom(received.body))

            try {
                MesocycleResult.Ok(
                    json.decodeFromString(MesocyclePlanArtifact.serializer(), received.body),
                )
            } catch (e: Exception) {
                // A 200 the app cannot decode means the two sides disagree about the
                // contract. ContractSchemaTest exists so that is a build failure, not this.
                failed(EngineError.UNEXPECTED)
            }
        }

    /** Named error in, named error out. An unparseable error body is still not a crash. */
    private fun errorFrom(text: String): EngineError = try {
        val code = json.parseToJsonElement(text).jsonObject["error"]
            ?.jsonObject?.get("code")?.jsonPrimitive?.content
        EngineError.fromWireCode(code)
    } catch (e: Exception) {
        EngineError.UNEXPECTED
    }

    private fun failed(error: EngineError) = MesocycleResult.Failed(error)

    private companion object {
        const val PATH = "/v1/mesocycle-proposal"
        const val SECRET_HEADER = "X-Cadentic-Secret"
        const val REQUEST_ID_HEADER = "X-Request-Id"
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}

/** Status and body together, so nothing downstream has to hold a live Response open. */
private class Received(val ok: Boolean, val body: String)

/**
 * Bridges OkHttp's callback API to a cancellable suspend. Cancelling the coroutine — which is
 * what `back()` during GENERATING does — cancels the call, which closes the socket, which the
 * backend reads as the athlete having gone away and aborts the generation. No orphaned plan
 * burning subscription usage for a screen nobody is looking at.
 *
 * **The body is read inside this suspend, not after it.** The obvious shape — suspend for the
 * response, then read the body — leaves the cancellable region as soon as the headers land,
 * and the body read is a *blocking* read: a coroutine cancelled during it cannot reach a
 * final state, so nothing fires to cancel the call and the request runs on until the socket
 * times out. Back would look instant and the generation would keep going. Reading here keeps
 * the whole call inside one `invokeOnCancellation`, where cancelling really does end it.
 */
private suspend fun Call.awaitBody(): Received = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            // On OkHttp's own dispatcher thread; blocking here is what it is for.
            val received = try {
                response.use { Received(it.isSuccessful, it.body?.string().orEmpty()) }
            } catch (e: IOException) {
                // A connection that dies mid-body is a transport failure, and belongs on the
                // same path as one that never connected.
                cont.resumeWithExceptionSafely(e)
                return
            }
            if (cont.isActive) cont.resume(received)
        }

        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithExceptionSafely(e)
        }
    })
    cont.invokeOnCancellation { cancel() }
}

private fun <T> CancellableContinuation<T>.resumeWithExceptionSafely(e: Throwable) {
    if (isActive) resumeWith(Result.failure(e))
}
