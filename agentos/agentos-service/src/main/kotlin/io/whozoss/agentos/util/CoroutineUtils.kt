package io.whozoss.agentos.util

import kotlinx.coroutines.CancellationException

/**
 * Coroutine-safe equivalent of [runCatching].
 *
 * Executes [block] and wraps the result in [Result], exactly like [runCatching], except
 * that [CancellationException] is always re-thrown instead of being captured. This
 * preserves Kotlin coroutine cooperative cancellation, which [runCatching] silently breaks
 * when used inside a `suspend` function.
 *
 * Usage mirrors [runCatching] and composes with the standard [Result] API:
 * ```kotlin
 * coRunCatching { membersLoader(groupId, namespaceId, userId) }
 *     .onFailure { e -> logger.warn(e) { "failed" } }
 *     .getOrElse { return ToolExecutionResult.error("...", errorType = "RETRIEVAL_ERROR") }
 * ```
 */
inline fun <T> coRunCatching(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
