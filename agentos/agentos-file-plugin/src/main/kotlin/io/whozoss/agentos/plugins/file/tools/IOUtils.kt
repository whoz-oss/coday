package io.whozoss.agentos.plugins.file.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

/**
 * Execute an IO operation with timeout on [Dispatchers.IO].
 *
 * Must be called from a coroutine context (e.g. a `suspend` function or inside `runBlocking`).
 *
 * @param timeoutSeconds Maximum duration before [kotlinx.coroutines.TimeoutCancellationException]
 * @param block The suspending block to execute
 * @return The result of [block]
 * @throws kotlinx.coroutines.TimeoutCancellationException if operation exceeds timeout
 * @throws Exception any exception from block is propagated unchanged
 */
suspend fun <T> runIOWithTimeout(timeoutSeconds: Long, block: suspend () -> T): T =
    withTimeout(timeoutSeconds.seconds) {
        withContext(Dispatchers.IO) { block() }
    }
