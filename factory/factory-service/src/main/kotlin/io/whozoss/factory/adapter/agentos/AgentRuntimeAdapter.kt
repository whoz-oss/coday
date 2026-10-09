package io.whozoss.factory.adapter.agentos

/**
 * Token of one started turn on a case.
 *
 * The [baseline] is the durable high-water mark captured **before** the turn
 * was posted: every pre-existing event of the case (an old `IDLE`, an old
 * `AgentFinishedEvent`, any leftover of a reused case) sorts at or before it
 * and must never close this turn. Multi-turn safe by construction: each turn
 * carries its own baseline.
 */
data class TurnToken(
    val caseId: String,
    val attemptId: String,
    /** Baseline captured before the turn started; fences out every older event. */
    val baseline: HighWaterMark,
) {
    companion object {
        /** A token with no baseline information — observation falls back to a full replay. */
        fun unbaselined(caseId: String, attemptId: String): TurnToken =
            TurnToken(caseId, attemptId, HighWaterMark(null, null))
    }
}

/**
 * Explicit, runtime-agnostic contract driving an agent execution (case)
 * through its full lifecycle.
 *
 * Authority invariants (non-negotiable):
 * - The Factory is the sole authority: every identity comes from a
 *   [TrustedCaseBinding], never from LLM-supplied arguments.
 * - No verdict is ever derived from silence, from missing events, or from
 *   agent prose: an unreachable runtime, an exhausted observation budget or a
 *   non-quiescent case all yield [AgentOsExecutionVerdict.Indeterminate].
 * - A turn starts only after its event baseline was captured
 *   ([startTurn]), so an old turn of a reused case can never close the new
 *   one prematurely.
 */
interface AgentRuntimeAdapter {

    /**
     * 1. Create the case of an attempt, or recover the existing one when this
     *    `attemptId` was already bound (idempotent by attemptId).
     */
    fun createOrRecoverExecution(binding: TrustedCaseBinding, workflowId: String, stepId: String): CaseHandle

    /**
     * 2. Capture the event baseline of the case **before** the turn starts,
     *    then post the turn. Returns the [TurnToken] whose baseline fences
     *    out every pre-existing (old-turn / reused-case) event. Multi-turn
     *    safe: each call captures a fresh baseline for its
     *    `(caseId, attemptId)` turn.
     *
     * @throws AgentOsCaseBusyException when the case is not quiescent.
     */
    fun startTurn(binding: TrustedCaseBinding, persona: String, brief: String): TurnToken

    /**
     * 3. Observe the explicit lifecycle (SSE replay + live stream, with REST
     *    catch-up on every reconnection) until a verdict is derivable or a
     *    budget elapses. Events at or before the turn baseline are ignored.
     *    Never returns a verdict derived from silence.
     */
    fun observeTurn(
        turn: TurnToken,
        timeoutMs: Long,
        onIntermediateVerdict: (AgentOsExecutionVerdict.WaitingHuman) -> Unit = {},
        onAnswerObserved: (CaseEventView) -> Unit = {},
    ): AgentOsExecutionVerdict

    /**
     * 4. Durable-history reconciliation (REST catch-up), filtered by the turn
     *    baseline when one is known: a case that is not yet quiescent for
     *    this turn yields [AgentOsExecutionVerdict.Indeterminate] — the
     *    observation resumes, it never concludes from silence. A runtime
     *    that cannot be reached at all yields an indeterminate
     *    [VerdictDeriver.RUNTIME_UNREACHABLE] verdict, never a success.
     */
    fun reconcile(turn: TurnToken): AgentOsExecutionVerdict

    /** Durable, non-transient case history used for strict human-answer reconciliation. */
    fun persistedEvents(caseId: String): List<CaseEventView> = emptyList()

    /**
     * 5. Controlled interruption with an explicit reason. The intent is
     *    remembered so a subsequent terminal `KILLED` derives to
     *    [AgentOsExecutionVerdict.Interrupted] — never `Succeeded`.
     */
    fun interrupt(caseId: String, reason: String)

    /** 6. Forced, best-effort kill (last resort); never throws. */
    fun kill(caseId: String)

    /**
     * 7. Close / seal the case when the runtime supports it. The AgentOS HTTP
     *    contract exposes no seal route today, so the default is a no-op;
     *    implementations may release Factory-local bookkeeping.
     */
    fun close(caseId: String) {}
}
