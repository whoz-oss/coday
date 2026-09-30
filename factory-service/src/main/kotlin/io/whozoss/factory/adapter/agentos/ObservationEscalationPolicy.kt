package io.whozoss.factory.adapter.agentos

/**
 * Deterministic escalation chain applied when a turn observation ends
 * [AgentOsExecutionVerdict.Indeterminate] (SSE observation timeout, dropped
 * stream with an exhausted reconnection budget, or an ambiguous transport
 * error).
 *
 * The policy NEVER produces a success by itself: [AgentOsExecutionVerdict.Succeeded]
 * can only ever be returned when AgentOS actually reports it through the REST
 * snapshot, a reconnection or the post-kill reconciliation. Kill never succeeds.
 *
 * The chain mirrors the frozen Lot H decision:
 *  1. REST snapshot query ([AgentOsExecutionAdapter.reconcile]);
 *  2. bounded SSE reconnection ([AgentOsExecutionAdapter.observeTurn]);
 *  3. explicit kill ([AgentOsExecutionAdapter.kill]) when the timeout policy
 *     allows terminating an unresponsive case;
 *  4. post-kill state check ([AgentOsExecutionAdapter.reconcile]);
 *  5. otherwise stay [AgentOsExecutionVerdict.Indeterminate] — never an implicit
 *     `SUCCEEDED`.
 */
class ObservationEscalationPolicy(
    /** Wall-clock budget handed to the single SSE reconnection attempt. */
    private val reconnectBudgetMs: Long = DEFAULT_RECONNECT_BUDGET_MS,
    /** Whether an unresponsive, non-quiescent case may be killed during escalation. */
    private val killOnTimeout: Boolean = true,
) {

    /** The escalated verdict plus the ordered escalation steps actually taken. */
    data class Escalation(
        val verdict: AgentOsExecutionVerdict,
        val steps: List<String>,
    )

    /**
     * Escalate an [AgentOsExecutionVerdict.Indeterminate] observation to a
     * provable verdict, or return an [AgentOsExecutionVerdict.Indeterminate]
     * carrying the escalation trail.
     */
    fun escalate(
        adapter: AgentOsExecutionAdapter,
        caseId: String,
        attemptId: String,
        initial: AgentOsExecutionVerdict.Indeterminate,
    ): Escalation {
        val steps = mutableListOf<String>()

        // (1) REST snapshot: the durable case state is authoritative and cheap.
        steps += STEP_SNAPSHOT
        runCatching { adapter.reconcile(caseId) }.getOrNull()?.terminalOrNull()?.let { return Escalation(it, steps) }

        // (2) Bounded SSE reconnection: may observe the terminal event the lost
        // stream missed, with checkpoint deduplication.
        steps += STEP_RECONNECT
        runCatching { adapter.observeTurn(caseId, attemptId, reconnectBudgetMs) }.getOrNull()
            ?.terminalOrNull()
            ?.let { return Escalation(it, steps) }

        // (3) Explicit kill, only when the timeout policy allows it.
        if (!killOnTimeout) {
            return Escalation(indeterminateAfter(initial, steps), steps)
        }
        steps += STEP_KILL
        runCatching { adapter.kill(caseId) }

        // (4) Post-kill reconciliation: only a proof derived from AgentOS state
        // can terminalize the attempt.
        steps += STEP_POST_KILL_RECONCILE
        runCatching { adapter.reconcile(caseId) }.getOrNull()?.terminalOrNull()?.let { return Escalation(it, steps) }

        // (5) Insufficient proof: stay indeterminate, never a silent success.
        return Escalation(indeterminateAfter(initial, steps), steps)
    }

    /** A verdict is "provable" when it is anything but another indeterminate. */
    private fun AgentOsExecutionVerdict.terminalOrNull(): AgentOsExecutionVerdict? =
        if (this is AgentOsExecutionVerdict.Indeterminate) null else this

    private fun indeterminateAfter(
        initial: AgentOsExecutionVerdict.Indeterminate,
        steps: List<String>,
    ): AgentOsExecutionVerdict.Indeterminate = AgentOsExecutionVerdict.Indeterminate(
        reason = initial.reason,
        evidence = initial.evidence + mapOf(
            "escalated" to true,
            "escalationSteps" to steps,
        ),
    )

    companion object {
        const val STEP_SNAPSHOT = "reconcile"
        const val STEP_RECONNECT = "sse-reconnect"
        const val STEP_KILL = "kill"
        const val STEP_POST_KILL_RECONCILE = "post-kill-reconcile"

        /** Default reconnection budget of the escalation chain. */
        const val DEFAULT_RECONNECT_BUDGET_MS = 30_000L
    }
}
