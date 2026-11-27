package io.whozoss.agentos.plugins.kotlin

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException

/**
 * Waits for the answer of a request to the language server.
 *
 * Interruptible: when the worker running the call is interrupted (timeout or cancellation of the case), the request
 * is cancelled, which tells the server to stop working on it, and the interruption goes on. A failure of the request
 * is rethrown as its cause.
 */
internal fun <T> CompletableFuture<T>.awaitResult(): T =
    try {
        get()
    } catch (e: InterruptedException) {
        cancel(true)
        Thread.currentThread().interrupt()
        throw e
    } catch (e: ExecutionException) {
        throw e.cause ?: e
    }
