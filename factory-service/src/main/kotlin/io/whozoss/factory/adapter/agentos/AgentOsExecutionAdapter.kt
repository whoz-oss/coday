package io.whozoss.factory.adapter.agentos

/** Handle on an AgentOS execution (case) created or recovered at the Factory boundary. */
data class CaseHandle(
    val caseId: String,
    val namespaceId: String?,
    /** True when the execution already existed for this `attemptId` (no re-creation). */
    val recovered: Boolean,
)

/**
 * Explicit Factory → AgentOS execution boundary.
 *
 * All operations are HTTP-boundary only (no AgentOS type ever crosses it) and
 * idempotency is keyed by `attemptId`: a retried Factory step with the same
 * `attemptId` recovers the existing case instead of re-driving it.
 *
 * Since the final cutover this adapter is the **primary, mandatory** execution
 * driver of `agent` steps: the `CapabilityExecutionService` always depends on it
 * (the bean is created unconditionally). Setting
 * `factory.adapter.agentos.enabled=false` explicitly demotes execution to the
 * legacy polling turn driver (`HttpAgentOsProxyClient`), a troubleshooting
 * fallback only.
 */
interface AgentOsExecutionAdapter {

    /**
     * Create the AgentOS case for an attempt, or recover the existing one when
     * this `attemptId` was already bound (idempotent by `attemptId`).
     */
    fun createOrRecoverExecution(
        namespaceId: String,
        workflowId: String,
        stepId: String,
        externalUserId: String?,
        attemptId: String,
        capabilityToken: String?,
        caseId: String,
    ): CaseHandle

    /**
     * Start a turn on the case by posting `@persona brief`.
     *
     * @throws AgentOsCaseBusyException when the case is not quiescent (posting
     *   now would be silently abandoned, per the existing `AGENT_CASE_BUSY` rule).
     */
    fun startTurn(
        caseId: String,
        persona: String,
        brief: String,
        externalUserId: String?,
        attemptId: String,
        capabilityToken: String?,
    )

    /**
     * Observe the case over SSE (with REST reconciliation on every
     * reconnection) until a verdict is derivable or the budget elapses.
     * Never returns a verdict derived from silence.
     */
    fun observeTurn(caseId: String, attemptId: String, timeoutMs: Long): AgentOsExecutionVerdict

    /**
     * REST-only catch-up: pull the durable events and derive the verdict. A
     * case that is not yet quiescent yields [AgentOsExecutionVerdict.Indeterminate].
     */
    fun reconcile(caseId: String): AgentOsExecutionVerdict

    /**
     * Request an interruption (best-effort stop; the AgentOS contract exposes
     * only the kill route). The interruption intent is remembered so a
     * subsequent terminal `KILLED` derives to
     * [AgentOsExecutionVerdict.Interrupted] — never `Succeeded`.
     */
    fun interrupt(caseId: String, reason: String)

    /** Best-effort `POST /api/cases/{caseId}/kill`; never throws. */
    fun kill(caseId: String)
}
