package io.whozoss.agentos.git

import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
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

    private val namespaces = ConcurrentHashMap<UUID, ReentrantLock>()
    private val locks = ConcurrentHashMap<UUID, RootLock>()

    private fun root(id: UUID) = locks.computeIfAbsent(id) { RootLock() }

    /**
     * Serialize a settings save with the allocation that freezes those settings. These sections
     * only inspect configuration and record intent; clone, fetch and setup run elsewhere.
     * Case creation is transactional, so a binding must be committed (or rolled back) before a
     * competing settings update can inspect it. Spring's imperative transaction completes on
     * this same thread; its completion callback releases the lock after persistence is visible.
     */
    fun <T> withNamespace(
        namespaceId: UUID,
        action: () -> T,
    ): T {
        val lock = namespaces.computeIfAbsent(namespaceId) { ReentrantLock() }
        lock.lock()
        var transactionReleasesLock = false
        try {
            if (TransactionSynchronizationManager.isActualTransactionActive() &&
                TransactionSynchronizationManager.isSynchronizationActive()
            ) {
                TransactionSynchronizationManager.registerSynchronization(
                    object : TransactionSynchronization {
                        override fun afterCompletion(status: Int) {
                            lock.unlock()
                        }
                    },
                )
                transactionReleasesLock = true
            }
            return action()
        } finally {
            if (!transactionReleasesLock) lock.unlock()
        }
    }

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
