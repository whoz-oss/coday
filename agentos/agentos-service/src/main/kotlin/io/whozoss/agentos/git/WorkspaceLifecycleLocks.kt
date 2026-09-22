package io.whozoss.agentos.git

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Coordinates lifecycle transitions and admission of runs on this single AgentOS instance. */
object WorkspaceLifecycleLocks {
    private val locks = ConcurrentHashMap<UUID, Any>()
    fun <T> withRoot(rootId: UUID, action: () -> T): T = synchronized(locks.computeIfAbsent(rootId) { Any() }) { action() }
}
