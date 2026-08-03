package io.whozoss.agentos.auth

import mu.KLogging
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory registry of pending OAuth authorization flows.
 *
 * Each flow is keyed by the `state` parameter sent to the authorization server.
 * When the user completes the OAuth consent, the callback controller resolves the
 * future with the received authorization code, unblocking [OAuthFlowService].
 *
 * The [userId] of the initiating user is stored alongside the future. [resolve]
 * refuses the resolution if the calling user does not match, without consuming
 * or cancelling the pending future — this prevents credential confusion (Alice's
 * code injected into Bob's flow) and avoids a denial-of-service vector where a
 * rejected attempt would silently abort the legitimate flow.
 *
 * Timeout management is intentionally left to the caller: [OAuthFlowService] calls
 * `future.get(flowTimeoutMinutes, TimeUnit.MINUTES)` (configurable via
 * `agentos.oauth.flow-timeout-minutes`, default 2) and invokes [cancel] on
 * [TimeoutException] to release the entry and unblock any waiter. The registry
 * itself is stateless regarding time.
 *
 * **Single-instance and no-restart constraint.** This registry is purely in-memory,
 * and the PKCE `codeVerifier` — required to exchange the authorization code for tokens
 * — lives only in the stack frame of the suspended coroutine in [OAuthFlowService]. It
 * is not persisted anywhere. This means:
 * - In a multi-instance deployment, a callback arriving on a different instance than
 *   the one that initiated the flow finds no matching future and returns 400; the
 *   originating coroutine times out without receiving the code.
 * - A restart of the originating instance during an active flow has the same effect:
 *   the code, if it arrives after restart, cannot be used because `codeVerifier` is
 *   gone.
 * Making the callback serviceable by any instance would require persisting the full
 * intermediate flow state: `codeVerifier`, `tokenEndpoint`, `clientId`, `clientSecret`,
 * `redirectUri`, `userId`, `authSettingId` — two of which are secrets requiring
 * encryption at rest. Tracked in #1198.
 *
 * **Bounded capacity.** Each pending flow holds one thread from the Kotlin
 * `Dispatchers.IO` pool for the duration of the timeout window. The pool has
 * a fixed ceiling (64 threads by default), so the number of concurrently
 * serviceable interactive OAuth flows is implicitly bounded by that pool size.
 * Exceeding it stalls other IO operations (DB queries, LLM calls) until a
 * flow times out or completes. See #1198 for the planned non-blocking redesign.
 */
@Component
class OAuthPendingRegistry {

    private data class PendingFlow(
        val userId: UUID,
        val future: CompletableFuture<String>,
    )

    private val pending = ConcurrentHashMap<String, PendingFlow>()

    /**
     * Register a new pending OAuth flow for [state], bound to [userId].
     *
     * Returns a [CompletableFuture] that will be completed normally by [resolve]
     * (with the authorization code) or exceptionally by [cancel].
     *
     * @throws IllegalStateException if a pending flow for [state] already exists.
     */
    fun register(
        state: String,
        userId: UUID,
    ): CompletableFuture<String> {
        val future = CompletableFuture<String>()
        val existing = pending.putIfAbsent(state, PendingFlow(userId, future))
        check(existing == null) { "Duplicate OAuth state key: $state" }
        logger.debug { "Registered pending OAuth flow for state=$state userId=$userId" }
        return future
    }

    /**
     * Resolve a pending flow with the received authorization [code].
     *
     * Atomically verifies that [callerId] matches the user who initiated the flow,
     * then removes the entry and completes the future normally.
     *
     * If [callerId] does not match the registered user the entry is **not consumed**
     * (the legitimate flow remains pending), and the method returns `false` — the
     * same value returned for an unknown [state]. The mismatch is logged at WARN
     * level so operators can detect injection attempts without exposing information
     * to the caller.
     *
     * @return `true` if a pending flow was found, the caller matched, and the flow
     *         was resolved; `false` otherwise (unknown state or identity mismatch).
     */
    fun resolve(
        state: String,
        code: String,
        callerId: UUID,
    ): Boolean {
        val flow = pending[state] ?: return false
        if (flow.userId != callerId) {
            logger.warn {
                "OAuth callback identity mismatch: state=$state registered for userId=${flow.userId}, called by userId=$callerId"
            }
            return false
        }
        pending.remove(state)
        flow.future.complete(code)
        logger.debug { "Resolved pending OAuth flow for state=$state" }
        return true
    }

    /**
     * Cancel a pending flow, for example on timeout or user abort.
     *
     * Removes the entry and completes the future exceptionally with a
     * [CancellationException] so any thread blocking on [CompletableFuture.get] is
     * immediately unblocked.
     */
    fun cancel(state: String) {
        val flow = pending.remove(state) ?: return
        flow.future.completeExceptionally(CancellationException("OAuth flow cancelled for state=$state"))
        logger.debug { "Cancelled pending OAuth flow for state=$state" }
    }

    companion object : KLogging()
}
