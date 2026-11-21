package io.whozoss.agentos.persistence

import org.springframework.dao.TransientDataAccessException

/**
 * Runs [block], running it again when it fails with a [TransientDataAccessException].
 *
 * Neo4j reports a deadlock (`Neo.TransientError.Transaction.DeadlockDetected`) as a transient
 * error: the losing transaction is rolled back and the same work is expected to succeed when
 * submitted again. Spring Data Neo4j translates it to a [TransientDataAccessException].
 *
 * Only use it around a unit of work that is safe to replay and that is not enclosed in a wider
 * transaction: a deadlock terminates the whole enclosing transaction, so replaying one statement
 * inside it cannot succeed.
 *
 * Any other failure, and the last transient one, is rethrown unchanged.
 *
 * @param maxAttempts total number of executions, the first one included
 * @param backoffMillis wait before the next attempt, multiplied by the attempt number
 * @param onRetry called before each new attempt, for logging
 */
internal fun <T> retryOnTransientFailure(
    maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    backoffMillis: Long = DEFAULT_BACKOFF_MILLIS,
    onRetry: (attempt: Int, cause: TransientDataAccessException) -> Unit = { _, _ -> },
    block: () -> T,
): T = attempt(1, maxAttempts, backoffMillis, onRetry, block)

private fun <T> attempt(
    number: Int,
    maxAttempts: Int,
    backoffMillis: Long,
    onRetry: (Int, TransientDataAccessException) -> Unit,
    block: () -> T,
): T {
    val outcome = runCatching(block)
    val failure = outcome.exceptionOrNull()
    return when {
        failure == null -> outcome.getOrThrow()

        failure is TransientDataAccessException && number < maxAttempts -> {
            onRetry(number, failure)
            Thread.sleep(backoffMillis * number)
            attempt(number + 1, maxAttempts, backoffMillis, onRetry, block)
        }

        else -> throw failure
    }
}

private const val DEFAULT_MAX_ATTEMPTS = 4
private const val DEFAULT_BACKOFF_MILLIS = 50L
