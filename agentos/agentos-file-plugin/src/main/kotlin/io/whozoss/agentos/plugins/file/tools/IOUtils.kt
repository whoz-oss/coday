package io.whozoss.agentos.plugins.file.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

/**
 * Execute an IO operation with timeout on [Dispatchers.IO].
 *
 * @param timeoutSeconds Maximum duration before [kotlinx.coroutines.TimeoutCancellationException]
 * @param block The suspending block to execute
 * @return The result of [block]
 * @throws kotlinx.coroutines.TimeoutCancellationException if operation exceeds timeout
 * @throws Exception any exception from block is propagated unchanged
 */
fun <T> runIOWithTimeout(timeoutSeconds: Long, block: suspend () -> T): T =
    runBlocking {
        withTimeout(timeoutSeconds.seconds) {
            withContext(Dispatchers.IO) { block() }
        }
    }
