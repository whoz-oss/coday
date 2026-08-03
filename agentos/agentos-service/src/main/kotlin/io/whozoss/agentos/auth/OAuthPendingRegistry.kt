package io.whozoss.agentos.auth

import mu.KLogging
import org.springframework.stereotype.Component
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
 * Timeout management is intentionally left to the caller: [OAuthFlowService] calls
 * `future.get(flowTimeoutMinutes, TimeUnit.MINUTES)` (configurable via
 * `agentos.oauth.flow-timeout-minutes`, default 2) and invokes [cancel] on
 * [TimeoutException] to release the entry and unblock any waiter. The registry
 * itself is stateless regarding time.
 *
 * **Single-instance constraint.** This registry is purely in-memory. In a
 * multi-instance deployment (horizontal scaling, rolling restart), the OAuth
 * callback arriving at a different instance than the one that registered the
 * `state` will find no matching future and return 400, causing the flow to
 * time out on the originating instance. Solving this requires an external
 * shared store (Redis, DB) for the pending-state map — tracked in #1198.
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

    private val pending = ConcurrentHashMap<String, CompletableFuture<String>>()

    /**
     * Register a new pending OAuth flow for [state].
     *
     * Returns a [CompletableFuture] that will be completed normally by [resolve]
     * (with the authorization code) or exceptionally by [cancel].
     *
     * @throws IllegalStateException if a pending flow for [state] already exists.
     */
    fun register(state: String): CompletableFuture<String> {
        val future = CompletableFuture<String>()
        val existing = pending.putIfAbsent(state, future)
        check(existing == null) { "Duplicate OAuth state key: $state" }
        logger.debug { "Registered pending OAuth flow for state=$state" }
        return future
    }

    /**
     * Resolve a pending flow with the received authorization [code].
     *
     * Atomically removes the entry and completes the future normally.
     *
     * @return `true` if a pending flow was found and resolved; `false` if [state] is unknown.
     */
    fun resolve(state: String, code: String): Boolean {
        val future = pending.remove(state) ?: return false
        future.complete(code)
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
        val future = pending.remove(state) ?: return
        future.completeExceptionally(CancellationException("OAuth flow cancelled for state=$state"))
        logger.debug { "Cancelled pending OAuth flow for state=$state" }
    }

    companion object : KLogging()
}
