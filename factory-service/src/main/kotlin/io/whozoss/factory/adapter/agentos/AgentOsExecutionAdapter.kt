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
 *
 * This interface refines the runtime-agnostic [AgentRuntimeAdapter] lifecycle
 * contract with the historical argument-list signatures its long-standing
 * consumers (`CapabilityExecutionService`, `BridgeRecoveryWorker`,
 * `BridgeCancellationService`, `WorkflowService`) and test doubles were
 * written against:
 * - The historical signatures stay abstract — existing implementations that
 *   override only them remain source-compatible, and the
 *   [AgentRuntimeAdapter] binding/token operations are provided here as
 *   default methods that unwrap the [TrustedCaseBinding]/[TurnToken] and
 *   delegate to the historical signatures.
 * - The production [DefaultAgentOsExecutionAdapter] does the converse: it
 *   implements the binding/token operations (capturing the per-turn baseline
 *   and tracking every case in the [ActiveCaseRegistry]) and adapts the
 *   historical signatures onto them.
 */
interface AgentOsExecutionAdapter : AgentRuntimeAdapter {

    // ---- Historical signatures (kept abstract: existing adapters override them) ----

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
    fun observeTurn(
        caseId: String,
        attemptId: String,
        timeoutMs: Long,
    ): AgentOsExecutionVerdict

    /**
     * Observe with intermediate human-wait notifications. The default delegates
     * to the historical three-argument contract so existing adapters remain
     * source-compatible; the production AgentOS adapter overrides this overload
     * to keep the logical observation alive across WAITING_HUMAN.
     */
    fun observeTurn(
        caseId: String,
        attemptId: String,
        timeoutMs: Long,
        onIntermediateVerdict: (AgentOsExecutionVerdict.WaitingHuman) -> Unit,
    ): AgentOsExecutionVerdict = observeTurn(caseId, attemptId, timeoutMs)

    /**
     * REST-only catch-up: pull the durable events and derive the verdict. A
     * case that is not yet quiescent yields [AgentOsExecutionVerdict.Indeterminate];
     * a runtime that cannot be reached at all yields an indeterminate
     * [VerdictDeriver.RUNTIME_UNREACHABLE] verdict — never `Succeeded`.
     */
    fun reconcile(caseId: String): AgentOsExecutionVerdict

    // ---- AgentRuntimeAdapter contract, defaulted over the historical signatures ----

    override fun createOrRecoverExecution(binding: TrustedCaseBinding, workflowId: String, stepId: String): CaseHandle =
        createOrRecoverExecution(
            namespaceId = binding.namespaceId ?: "",
            workflowId = workflowId,
            stepId = stepId,
            externalUserId = binding.externalUserId,
            attemptId = binding.attemptId,
            capabilityToken = binding.capabilityToken,
            caseId = binding.caseId,
        )

    /**
     * Default turn start: delegates to the historical signature and returns an
     * unbaselined token. Implementations capturing a real per-turn baseline
     * (the production adapter) override this to return the captured baseline.
     */
    override fun startTurn(binding: TrustedCaseBinding, persona: String, brief: String): TurnToken {
        startTurn(binding.caseId, persona, brief, binding.externalUserId, binding.attemptId, binding.capabilityToken)
        return TurnToken.unbaselined(binding.caseId, binding.attemptId)
    }

    override fun observeTurn(
        turn: TurnToken,
        timeoutMs: Long,
        onIntermediateVerdict: (AgentOsExecutionVerdict.WaitingHuman) -> Unit,
    ): AgentOsExecutionVerdict = observeTurn(turn.caseId, turn.attemptId, timeoutMs, onIntermediateVerdict)

    override fun reconcile(turn: TurnToken): AgentOsExecutionVerdict = reconcile(turn.caseId)

    /**
     * Forward a bounded human answer to AgentOS. This command only requests the
     * answer; callers must confirm it from the persisted correlated AnswerEvent.
     */
    fun answerQuestion(caseId: String, questionEventId: String, answer: String, attemptId: String): Unit {
        throw UnsupportedOperationException("AgentOS answer forwarding requires an authenticated answering identity")
    }

    fun answerQuestion(
        caseId: String,
        questionEventId: String,
        answer: String,
        attemptId: String,
        answeringUserId: String,
    ): Unit {
        require(answeringUserId.isNotBlank()) { "answeringUserId must not be blank" }
        throw UnsupportedOperationException("AgentOS answer forwarding with identity is not supported by this adapter")
    }
}
