package io.whozoss.factory.proxy

/**
 * Thin HTTP client for AgentOS, used by the dashboard relay endpoints and the
 * W8.3 agent-turn capability.
 *
 * Port of `factory/dashboard/agentos-proxy.mjs` and
 * `factory/src/adapters/agentos/agentos-http-client.ts`. Every method relays the
 * `X-External-User-Id` trusted identity when supplied. The boundary is HTTP
 * only: nothing here imports an AgentOS Kotlin class.
 */
interface AgentOsProxyClient {

    /** List agent configs for a namespace. */
    fun fetchAgents(namespaceId: String, externalUserId: String?): Any?

    /** List AgentOS namespaces visible to the trusted external user. */
    fun fetchNamespaces(externalUserId: String?): Any?

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

    /**
     * Run one agent turn for a declarative session step, end to end:
     * create a case, post the `@persona` brief, wait for quiescence and derive
     * the step verdict from the case status.
     *
     * Never throws for an AgentOS failure: an unreachable AgentOS, a bad reply, a
     * timeout or a killed/errored case is returned as
     * [AgentTurnExecutionResult.Failed] so the caller applies the failure rule.
     */
    fun executeAgentTurn(
        namespaceId: String,
        persona: String,
        stepId: String,
        workflowId: String,
        brief: String? = null,
        externalUserId: String? = null,
        attemptId: String? = null,
        capabilityToken: String? = null,
        caseId: String? = null,
    ): AgentTurnExecutionResult
}

/** Outcome of a W8.3 agent turn; carries facts only, never LLM prose. */
sealed interface AgentTurnExecutionResult {

    /** The case reached `IDLE` quiescence and is considered a success. */
    data class Completed(
        val summary: String,
        val facts: Map<String, Any?> = emptyMap(),
    ) : AgentTurnExecutionResult

    /** The turn failed explicitly; [code] is a stable machine code. */
    data class Failed(
        val code: String,
        val message: String,
        val facts: Map<String, Any?> = emptyMap(),
    ) : AgentTurnExecutionResult
}

/** Raised when AgentOS is unreachable or answers non-404 — mapped to 502. */
class AgentOsUnavailableException(message: String, cause: Throwable? = null) :
    io.whozoss.factory.error.FactoryException(502, "AGENTOS_UNAVAILABLE", message, null, cause)
