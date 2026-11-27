package io.whozoss.agentos.plugins.kotlin

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * The threads that run the Kotlin LSP calls, shared by every tool of the plugin.
 *
 * A call mostly waits for the answer of the server, so the pool is not bounded by the processors: it is bounded by
 * the calls the agents make. Running the call on its own thread is what lets [KotlinLspRun] bound it by the timeout
 * of the agent, server start included, and interrupt it when that timeout expires.
 */
internal object KotlinLspWorkers {
    val executor: ExecutorService = Executors.newCachedThreadPool(threadFactory())

    /** Stops the threads; called when the plugin stops. */
    fun shutdown() {
        executor.shutdownNow()
    }

    private fun threadFactory(): ThreadFactory {
        val count = AtomicInteger()
        return ThreadFactory { task ->
            Thread(task, "kotlin-lsp-worker-${count.incrementAndGet()}").apply {
                isDaemon = true
                // lsp4j runs with the classes of the plugin, whichever thread of the service made the first call
                contextClassLoader = KotlinLspWorkers::class.java.classLoader
            }
        }
    }
}
