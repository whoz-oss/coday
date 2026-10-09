package io.whozoss.factory.adapter.agentos

/**
 * Typed result vocabulary of the AgentOS execution adapter.
 *
 * This sealed type is **standalone**: it deliberately does not depend on
 * `io.whozoss.factory.proxy.AgentTurnExecutionResult` (the polling client's
 * result type) nor on any attempt/run model. It is the explicit boundary
 * contract of the adapter: every observation or reconciliation of an AgentOS
 * case resolves to exactly one of these verdicts.
 *
 * Core invariants enforced by [VerdictDeriver] and [AgentOsSseClient]:
 * - `RUNNING` only proves the execution started; it never maps to a verdict.
 * - A terminal `KILLED`, `ERROR` or a timeout never maps to [Succeeded].
 * - No verdict is ever derived from silence: an exhausted reconnection budget
 *   or observation timeout maps to [Indeterminate], never to [Succeeded].
 */
sealed interface AgentOsExecutionVerdict {

    /** Terminal success: the turn produced a structured result. */
    data class Succeeded(
        val outputs: Map<String, Any?>,
        val evidence: Map<String, Any?> = emptyMap(),
    ) : AgentOsExecutionVerdict

    /** The case is IDLE waiting for a human answer to a pending question. */
    data class WaitingHuman(
        val questionRef: String,
        val questionText: String? = null,
        val evidence: Map<String, Any?> = emptyMap(),
    ) : AgentOsExecutionVerdict

    /** The case reached a terminal error state (`ERROR`, `KILLED`, qualified timeout). */
    data class Failed(
        val code: String,
        val message: String,
        val evidence: Map<String, Any?> = emptyMap(),
    ) : AgentOsExecutionVerdict

    /** The execution was interrupted (caller-initiated stop/kill). */
    data class Interrupted(
        val reason: String,
        val evidence: Map<String, Any?> = emptyMap(),
    ) : AgentOsExecutionVerdict

    /**
     * No verdict can be honestly derived: quiescence without structured output,
     * observation timeout, or exhausted reconnection budget. This is the
     * "never a verdict by silence" bucket.
     */
    data class Indeterminate(
        val reason: String,
        val evidence: Map<String, Any?> = emptyMap(),
    ) : AgentOsExecutionVerdict
}
