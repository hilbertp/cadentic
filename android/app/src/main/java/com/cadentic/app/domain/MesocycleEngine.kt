package com.cadentic.app.domain

import com.cadentic.app.domain.artifacts.MesoRequestPayload
import com.cadentic.app.domain.artifacts.MesocyclePlanArtifact

/**
 * The Mesocycle Engine, from the app's side (Epic 2 story 5).
 *
 * This is the same domain boundary the local `ProposalEngine` stub occupied: the ViewModel
 * hands over an assembled payload and gets back a plan or a named failure. What sits behind
 * it — a backend call today, something else later — is not the caller's business, which is
 * exactly why the stub could be swapped for a real generation without the UI noticing.
 */
interface MesocycleEngine {
    /**
     * Suspends for as long as generation takes; multi-minute waits are expected. Cancelling
     * the coroutine cancels the underlying request.
     */
    suspend fun generate(request: MesocycleRequest): MesocycleResult
}

data class MesocycleRequest(
    val payload: MesoRequestPayload,
    /**
     * Stable for one generation attempt, including its retries. The backend joins a duplicate
     * to the generation already running rather than starting a second one, so a double tap
     * costs one plan. A deliberate retry after a failure mints a new id.
     */
    val requestId: String,
)

sealed interface MesocycleResult {
    data class Ok(val plan: MesocyclePlanArtifact) : MesocycleResult
    data class Failed(val error: EngineError) : MesocycleResult
}

/**
 * Every way generation can fail, and the one sentence each is allowed to say to an athlete.
 *
 * The wire codes come from `contracts/mesocycle-api.schema.json`; [BACKEND_UNREACHABLE] is
 * the exception and is minted here. The distinction matters and is easy to lose: the app
 * could not reach the *backend* (airplane mode, wrong host, dev machine asleep) is a
 * different problem from the backend could not reach *Claude*, and collapsing them sends
 * people looking in the wrong place.
 */
enum class EngineError(val wireCode: String?, val message: String) {
    PAYLOAD_INVALID(
        "payload-invalid",
        "Something in your data didn't pass the engine's checks. Go back and take a look.",
    ),
    UNAUTHORIZED(
        "unauthorized",
        "This app build isn't allowed to talk to the engine.",
    ),
    PROVIDER_NOT_AVAILABLE(
        "provider-not-available",
        "The engine isn't set up to generate right now.",
    ),
    PROVIDER_UNREACHABLE(
        "provider-unreachable",
        "The engine couldn't reach Claude. Try again in a moment.",
    ),
    RATE_LIMITED(
        "rate-limited",
        "The engine has hit its usage limit. Try again a bit later.",
    ),
    TIMEOUT(
        "timeout",
        "Generating took too long and was stopped. Try again.",
    ),
    FORMAT_FAILED(
        "format-failed",
        "The plan that came back didn't hold up. Try again.",
    ),
    AUTH_FAILED(
        "auth-failed",
        "The engine's credentials were rejected. It needs re-authorising before this works.",
    ),

    /** Client-side only: the app never got as far as the backend. */
    BACKEND_UNREACHABLE(
        null,
        "Can't reach the engine. Check your connection and try again.",
    ),

    /** A response the app cannot make sense of — wrong shape, or a code it has never heard of. */
    UNEXPECTED(
        null,
        "Something went wrong generating your mesocycle. Try again.",
    );

    companion object {
        fun fromWireCode(code: String?): EngineError =
            entries.firstOrNull { it.wireCode != null && it.wireCode == code } ?: UNEXPECTED
    }
}
