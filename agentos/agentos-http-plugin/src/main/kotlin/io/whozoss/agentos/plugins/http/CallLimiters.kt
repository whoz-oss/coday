package io.whozoss.agentos.plugins.http

import kotlinx.coroutines.sync.Semaphore
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Plugin-wide registry of the semaphores bounding the simultaneous calls of each integration config
 * (`maxConcurrentCalls`), so that the bound holds across every agent and run of the service rather than
 * per run. A config is identified by its namespace and name; the limit value is part of the identity so
 * that a changed limit takes effect immediately with a fresh semaphore. The map only grows with distinct
 * (namespace, name, limit) triples: a handful per service, never one per run.
 */
class CallLimiters {

    private val semaphores = ConcurrentHashMap<String, Semaphore>()

    fun limiterFor(namespaceId: UUID?, configName: String, maxConcurrentCalls: Int): Semaphore =
        semaphores.computeIfAbsent("${namespaceId ?: NO_NAMESPACE}/$configName/$maxConcurrentCalls") {
            Semaphore(maxConcurrentCalls)
        }

    companion object {
        private const val NO_NAMESPACE = "-"
    }
}
