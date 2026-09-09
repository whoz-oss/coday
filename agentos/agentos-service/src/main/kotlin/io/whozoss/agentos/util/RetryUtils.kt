package io.whozoss.agentos.util

import mu.KLogging

sealed interface AttemptResult<out F, out R>

data class AttemptSuccess<R>(val value: R) : AttemptResult<Nothing, R>

data class AttemptFailure<F>(val value: F) : AttemptResult<F, Nothing>

/**
 * Retries [execute] up to [maxAttempts] times, feeding the previous failure back into
 * each subsequent call so the attempt can adapt (e.g. inject failed output into the next
 * prompt). Returns the first successful result, or the result of [fallback] once all
 * attempts are exhausted.
 *
 * [execute] receives `null` on the first call, then the [AttemptFailure.value] of the
 * previous attempt on each retry. Exceptions thrown by [execute] propagate immediately
 * and are the caller's responsibility -- catch and return [AttemptFailure] to retry on
 * a known error condition.
 *
 * @param maxAttempts total number of attempts (must be >= 1)
 * @param execute function that performs one attempt and signals success or failure
 * @param fallback called when all attempts are exhausted; receives the last failure value
 */
fun <F, R> retryWithFallback(
    maxAttempts: Int,
    fallback: (lastFailure: F) -> R,
    execute: (previousFailure: F?) -> AttemptResult<F, R>,
): R {
    require(maxAttempts >= 1) { "maxAttempts must be >= 1, was $maxAttempts" }

    var previousFailure: F? = null

    for (attempt in 1..maxAttempts) {
        when (val result = execute(previousFailure)) {
            is AttemptSuccess -> return result.value
            is AttemptFailure -> {
                previousFailure = result.value
                if (attempt < maxAttempts) {
                    logger.warn { "Attempt $attempt/$maxAttempts failed, retrying..." }
                }
            }
        }
    }

    return fallback(previousFailure!!)
}

private object RetryLogger : KLogging()

private val logger = RetryLogger.logger
