package io.whozoss.agentos.git

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/** Coordinates work on a family's workspace and the admission of its runs on this single AgentOS instance. */
object WorkspaceLifecycleLocks {
    private class RootLock {
        val lock = ReentrantLock()
        val waiting = ConcurrentHashMap<UUID, () -> Unit>()

        fun notifyAvailable() {
            if (!lock.isLocked) {
                waiting.entries.toList().forEach { (id, callback) ->
                    if (waiting.remove(id, callback)) callback()
                }
            }
        }
    }

    private val locks = ConcurrentHashMap<UUID, RootLock>()

    private fun root(id: UUID) = locks.computeIfAbsent(id) { RootLock() }

    fun <T> withRoot(
        rootId: UUID,
        action: () -> T,
    ): T {
        val root = root(rootId)
        root.lock.lock()
        return try {
            action()
        } finally {
            root.lock.unlock()
            root.notifyAvailable()
        }
    }

    /** Request threads never wait for preparation, cleanup or another file mutation. */
    fun <T> tryWithRoot(
        rootId: UUID,
        onBusy: () -> T,
        action: () -> T,
    ): T {
        val root = root(rootId)
        if (!root.lock.tryLock()) return onBusy()
        return try {
            action()
        } finally {
            root.lock.unlock()
            root.notifyAvailable()
        }
    }

    /**
     * Call [callback] once the root's lock is free, including a short status publication.
     * Registering after an unlock is handled too. The callback only schedules a fresh admission
     * attempt, which rechecks the waiting turn; nothing is replayed after a restart.
     */
    fun whenAvailable(
        rootId: UUID,
        caseId: UUID,
        callback: () -> Unit,
    ) {
        val root = root(rootId)
        root.waiting[caseId] = callback
        root.notifyAvailable()
    }
}
