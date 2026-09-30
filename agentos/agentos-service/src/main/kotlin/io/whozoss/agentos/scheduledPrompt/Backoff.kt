package io.whozoss.agentos.scheduledPrompt

/**
 * Returns the delay in milliseconds for a given attempt number using
 * truncated exponential backoff.
 *
 * Delay = min([baseMs] * 2^(attempt-1), [maxMs])
 *
 * Examples with defaults:
 * - attempt 1 ->  2 000 ms
 * - attempt 2 ->  4 000 ms
 * - attempt 3 ->  8 000 ms
 * - attempt 7 -> 60 000 ms (capped)
 *
 * @param attempt 1-based attempt counter (values <= 0 are treated as 1)
 * @param baseMs  base delay in milliseconds (default 2 000)
 * @param maxMs   maximum delay in milliseconds (default 60 000)
 */
fun exponentialBackoffMs(
    attempt: Int,
    baseMs: Long = 2_000L,
    maxMs: Long = 60_000L,
): Long {
    val exp = (attempt - 1).coerceIn(0, 29)
    return minOf(baseMs * (1L shl exp), maxMs)
}
