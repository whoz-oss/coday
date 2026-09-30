package io.whozoss.agentos.git

import jakarta.annotation.PreDestroy
import org.springframework.stereotype.Component
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Runs Git background work: a sweep, a clone, a status poll. */
fun interface GitWorkRunner {
    fun execute(command: Runnable)
}

/**
 * Long Git work never runs on Spring's shared scheduler or on an HTTP thread.
 *
 * Deliberately not a `java.util.concurrent.Executor` bean: Spring Boot only creates its
 * `applicationTaskExecutor` when no `Executor` bean exists, so one here would silently replace it
 * for every instance.
 */
@Component
class GitWorkspaceExecutor : GitWorkRunner {
    private val threadNumber = AtomicInteger()
    private val pool = ThreadPoolExecutor(
        2, 2, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(2),
        { task -> Thread(task, "git-workspace-${threadNumber.incrementAndGet()}") },
        ThreadPoolExecutor.AbortPolicy(),
    )

    override fun execute(command: Runnable) = pool.execute(command)

    @PreDestroy
    fun shutdown() {
        pool.shutdown()
        try {
            if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
                pool.shutdownNow()
                pool.awaitTermination(5, TimeUnit.SECONDS)
            }
        } catch (_: InterruptedException) {
            pool.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }
}

/** A queued sweep counts as active too; saturation skips this tick instead of running inline. */
internal fun submitWorkspaceSweep(executor: GitWorkRunner, active: AtomicBoolean, action: () -> Unit) {
    if (!active.compareAndSet(false, true)) return
    try {
        executor.execute {
            try { action() } finally { active.set(false) }
        }
    } catch (_: RejectedExecutionException) {
        active.set(false)
    }
}
