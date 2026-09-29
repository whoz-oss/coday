package io.whozoss.agentos.git

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/** Coordinates work on a family's workspace on this single AgentOS instance. */
object WorkspaceLifecycleLocks {
    private val locks = ConcurrentHashMap<UUID, ReentrantLock>()

    private fun root(id: UUID) = locks.computeIfAbsent(id) { ReentrantLock() }

    fun <T> withRoot(
        rootId: UUID,
        action: () -> T,
    ): T {
        val lock = root(rootId)
        lock.lock()
        return try {
            action()
        } finally {
            lock.unlock()
        }
    }

    /** Request threads never wait for preparation, cleanup or another file mutation. */
    fun <T> tryWithRoot(
        rootId: UUID,
        onBusy: () -> T,
        action: () -> T,
    ): T {
        val lock = root(rootId)
        if (!lock.tryLock()) return onBusy()
        return try {
            action()
        } finally {
            lock.unlock()
        }
    }
}
