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
 *
 * The pool is created on first use and created again after [shutdown]: PF4J keeps the classloader (hence this
 * object) when it stops then starts the plugin again, and the tools built before the stop keep working.
 */
internal object KtlintWorkers {
    private const val MIN_THREADS = 2
    private const val MAX_THREADS = 4
    private const val KEEP_ALIVE_SECONDS = 60L

    private val lock = Any()

    private var current: ExecutorService? = null

    /**
     * Half of the processors, between [MIN_THREADS] and [MAX_THREADS]: ktlint must not take the machine of the
     * service, and one stuck file must not stop every other run (hence at least two workers).
     */
    val size: Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(MIN_THREADS, MAX_THREADS)

    /** The pool of workers: the one in use, or a new one when none was created yet or the last one was shut down. */
    val executor: ExecutorService
        get() = synchronized(lock) { current?.takeUnless { it.isShutdown } ?: createPool().also { current = it } }

    /** Stops the threads; called when the plugin stops. The next [executor] is a new pool. */
    fun shutdown() {
        synchronized(lock) {
            current?.shutdownNow()
            current = null
        }
    }

    private fun createPool(): ExecutorService =
        ThreadPoolExecutor(size, size, KEEP_ALIVE_SECONDS, TimeUnit.SECONDS, LinkedBlockingQueue(), threadFactory()).apply {
            allowCoreThreadTimeOut(true)
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
