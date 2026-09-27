package io.whozoss.factory.proxy

/**
 * Thin HTTP client for AgentOS, used by the dashboard relay endpoints.
 *
 * Port of `factory/dashboard/agentos-proxy.mjs`. Every method relays the
 * `X-External-User-Id` trusted identity when supplied.
 */
interface AgentOsProxyClient {

    /** List agent configs for a namespace. */
    fun fetchAgents(namespaceId: String, externalUserId: String?): Any?

    /** Fetch a namespace record. Returns null when AgentOS answers 404. */
    fun fetchNamespace(namespaceId: String, externalUserId: String?): Map<String, Any?>?

    /** Fetch all events for a case. */
    fun fetchCaseEvents(caseId: String, externalUserId: String?): Any?

    /**
     * Resolve the repo root from the namespace configPath. Returns null when the
     * namespace is not found or has no configPath.
     */
    fun resolveRepoRoot(namespaceId: String, externalUserId: String?): String?

    /**
     * Resolve the Forge run store root from the namespace configPath
     * (`dirname(configPath)/forge/factory-runs`). Returns null when the namespace
     * is not found or has no configPath.
     */
    fun resolveRunStoreRoot(namespaceId: String, externalUserId: String?): String?
}

/** Raised when AgentOS is unreachable or answers non-404 — mapped to 502. */
class AgentOsUnavailableException(message: String, cause: Throwable? = null) :
    io.whozoss.factory.error.FactoryException(502, "AGENTOS_UNAVAILABLE", message, null, cause)
