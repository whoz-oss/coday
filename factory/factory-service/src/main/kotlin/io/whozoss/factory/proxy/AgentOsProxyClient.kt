package io.whozoss.factory.proxy

/**
 * Thin HTTP client for AgentOS, used by the Cockpit V2 relay endpoints and the
 * W8.3 agent-turn capability. Every method relays the
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

    /**
     * Read the namespace Git association (`GET /api/namespaces/{id}/git`,
     * `NamespaceGitResource`). Returns null when the endpoint is unavailable —
     * AgentOS answers 404 when the GIT plugin is not loaded or the namespace is
     * unknown.
     *
     * Defaulted to `null` so existing implementations/doubles stay
     * source-compatible; [HttpAgentOsProxyClient] implements the real read.
     */
    fun fetchNamespaceGit(namespaceId: String, externalUserId: String?): Map<String, Any?>? = null

    /** Fetch all events for a case. */
    fun fetchCaseEvents(caseId: String, externalUserId: String?): Any?

    /**
     * Read the aggregated run cost of a case tree
     * (`GET /api/cases/{caseId}/run-cost`). AgentOS already aggregates the
     * whole descendant tree (delegations included), so the caller must pass
     * ROOT case ids only. Returns null when the case is unknown (404) or
     * AgentOS is unreachable / errors — the caller degrades gracefully and
     * never fails.
     */
    fun getRunCost(caseId: String, externalUserId: String? = null): RunCostDto?

    /**
     * Confirm a paused run's next cost window (`POST /api/cases/{caseId}/run-cost/continue`).
     *
     * [expectedThreshold] is the optimistic precondition: AgentOS rejects the
     * command when the run's current threshold diverged. When null the body is
     * omitted and AgentOS answers with its own validation error.
     *
     * Never returns a failure silently: an unreachable AgentOS or a disabled
     * usage-tracking surface throws [UsageTrackingUnavailableException] (503) so
     * the caller degrades cleanly instead of surfacing a 500.
     */
    fun continueRunCost(caseId: String, expectedThreshold: Double?, externalUserId: String? = null): Boolean

    /**
     * Stop a run's live execution (`POST /api/cases/{caseId}/run-cost/stop`).
     *
     * Never returns a failure silently: an unreachable AgentOS or a disabled
     * usage-tracking surface throws [UsageTrackingUnavailableException] (503).
     */
    fun stopRunCost(caseId: String, externalUserId: String? = null): Boolean

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

/**
 * Raised when AgentOS usage tracking is disabled or its cost-control surface is
 * unreachable — mapped to a clean 503 (`SERVICE_UNAVAILABLE`) instead of an
 * unhandled 500. The message defaults to the AgentOS wording the Cockpit and
 * clients already recognise.
 */
class UsageTrackingUnavailableException(
    message: String = "Usage tracking is disabled",
    cause: Throwable? = null,
) : io.whozoss.factory.error.FactoryException(503, "SERVICE_UNAVAILABLE", message, null, cause)
