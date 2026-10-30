package io.whozoss.agentos.plugins.http

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Last configuration or document failure per integration config, so that the namespace description can
 * tell the agent why an integration exposes no tools. Messages never carry credential material.
 *
 * Entries are scoped by namespace: the same integration name (`ZENDESK`, `GITHUB`...) legitimately exists
 * in several namespaces, and a failure of one must never surface in, nor be cleared by, another. A run
 * without a tool context (the SDK signature allows it) uses its own scope.
 */
class LastFailureRegistry {

    private val failures = ConcurrentHashMap<String, String>()

    fun record(namespaceId: UUID?, configName: String, message: String) {
        failures[key(namespaceId, configName)] = message
    }

    fun clear(namespaceId: UUID?, configName: String) {
        failures.remove(key(namespaceId, configName))
    }

    fun get(namespaceId: UUID?, configName: String): String? = failures[key(namespaceId, configName)]

    private fun key(namespaceId: UUID?, configName: String): String = "${namespaceId ?: NO_NAMESPACE}/$configName"

    companion object {
        private const val NO_NAMESPACE = "-"
    }
}
