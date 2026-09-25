package io.whozoss.agentos.git

import jakarta.annotation.PreDestroy
import org.springframework.stereotype.Component
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Long Git work never runs on Spring's shared scheduler or on an HTTP thread. */
@Component
class GitWorkspaceExecutor : Executor {
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
internal fun submitWorkspaceSweep(executor: Executor, active: AtomicBoolean, action: () -> Unit) {
    if (!active.compareAndSet(false, true)) return
    try {
        executor.execute {
            try { action() } finally { active.set(false) }
        }
    } catch (_: RejectedExecutionException) {
        active.set(false)
    }
}
