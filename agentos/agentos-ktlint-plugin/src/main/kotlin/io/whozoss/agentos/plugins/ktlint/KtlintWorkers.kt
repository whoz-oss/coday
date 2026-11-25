package io.whozoss.agentos.plugins.ktlint

import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The threads that run ktlint for every tool call of the plugin, shared by all of them.
 *
 * Parsing is CPU-bound and the engine does not look at interruption while it parses, so it does not belong on
 * virtual threads: they are not preempted, and a long parse would hold one of the few carrier threads that the
 * whole service shares. A small pool of platform threads bounds both the CPU taken and the number of runs in
 * flight; the other runs wait their turn in the queue, inside their own time box (see [BoundedKtlintRun]).
 *
 * A timed-out run still keeps its thread until its file ends, nothing can kill it: the bound limits that to [size]
 * files at a time. A run that gives up before a worker took it stays in the queue as a cancelled task, which the
 * worker skips when it reaches it.
 */
internal object KtlintWorkers {
    private const val MAX_THREADS = 4
    private const val KEEP_ALIVE_SECONDS = 60L

    /** Half of the processors, between 1 and [MAX_THREADS]: ktlint must not take the machine of the service. */
    val size: Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, MAX_THREADS)

    val executor: ExecutorService =
        ThreadPoolExecutor(size, size, KEEP_ALIVE_SECONDS, TimeUnit.SECONDS, LinkedBlockingQueue(), threadFactory()).apply {
            allowCoreThreadTimeOut(true)
        }

    /** Stops the threads; called when the plugin stops. */
    fun shutdown() {
        executor.shutdownNow()
    }

    private fun threadFactory(): ThreadFactory {
        val count = AtomicInteger()
        return ThreadFactory { task ->
            Thread(task, "ktlint-worker-${count.incrementAndGet()}").apply {
                isDaemon = true
                // the engine runs with the classes of the plugin, whichever thread of the service made the first call
                contextClassLoader = KtlintWorkers::class.java.classLoader
            }
        }
    }
}
