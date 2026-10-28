package io.whozoss.factory.verification.ports

/**
 * Ports for what later waves (W8.2+) will wire up.
 *
 * This module defines the INTERFACES only: no HTTP client, no network code, no
 * AgentOS implementation lives here. That keeps `factory-verification-core`
 * autonomous by construction — it compiles and runs even when AgentOS is
 * unavailable. Implementations arrive in W8.2 (AgentOS client) and W8.3 (gates).
 */

/** Status of a single agent turn, observed until quiescence. */
enum class AgentTurnStatus { FINISHED, FAILED, KILLED }

/** Facts returned by an agent turn (never LLM prose). */
data class AgentTurnResult(
    val status: AgentTurnStatus,
    val caseStatus: String? = null,
    val agentTurns: Int = 0,
    val toolCallCount: Int = 0,
    val failedToolCalls: Int = 0,
)

/** Request to run a single agent turn inside a dedicated case. */
data class AgentTurnRequest(
    val caseId: String,
    val agent: String,
    val brief: String,
    val startTimeoutMs: Long,
    val workTimeoutMs: Long,
)

/**
 * Runs an agent turn to quiescence. Implemented in W8.2 over the AgentOS HTTP
 * contract; never implemented in this library.
 */
interface AgentTurnRunner {
    fun runTurn(request: AgentTurnRequest): AgentTurnResult
}

/** Type of human gate, with its allow-listed decisions. */
enum class GateType {
    ADVERSARIAL_REVIEW,
    ORACLE,
}

/** A single-use human decision. */
enum class GateDecision {
    RETRY,
    IGNORE,
    FAIL,
    CONTINUE,
}

/** An opened gate, identified by a run id and a safe instance token. */
data class GateOpen(
    val runId: String,
    val gateInstanceId: String,
    val type: GateType,
    val facts: Map<String, Any?> = emptyMap(),
)

/**
 * Human review-gate transport. Implemented in W8.3 (single-use, secret-guarded,
 * no LLM prose, rejection on shutdown); never implemented in this library.
 */
interface ReviewGateClient {
    /** Registers an open gate and returns its single-use instance id. */
    fun openGate(gate: GateOpen): String

    /** Blocks until the human decision for [gateInstanceId] is available. */
    fun waitForHumanDecision(runId: String, gateInstanceId: String, type: GateType): GateDecision

    /** Rejects every pending gate (called on shutdown). Returns how many were rejected. */
    fun rejectAllPendingGates(): Int
}
