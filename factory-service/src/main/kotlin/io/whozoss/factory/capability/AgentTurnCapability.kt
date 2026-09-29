package io.whozoss.factory.capability

import java.nio.file.Path

/**
 * Request to run one agent turn for a declarative session step.
 *
 * [persona] is the resolved `responsibility.name` of an `agent` step (the
 * AgentOS agent type). The implementation (W8.3) starts an AgentOS case over its
 * HTTP contract and runs the turn to quiescence; this port carries only the
 * facts.
 */
data class AgentTurnRequest(
    val stepId: String,
    val persona: String?,
    val repoRoot: Path,
    val namespaceId: String? = null,
    val workflowId: String? = null,
    val brief: String? = null,
    val externalUserId: String? = null,
    /** Durable attempt id the Factory issued the submission capability for. */
    val attemptId: String? = null,
    /** Single-use bearer token the AgentOS case must present to submit its result. */
    val capabilityToken: String? = null,
    /** Factory-chosen AgentOS case id, so the capability is bound to the real case. */
    val caseId: String? = null,
)

/** Facts returned by an agent-turn execution; never LLM prose. */
sealed interface AgentTurnResult {
    /** The turn ran to quiescence and succeeded. [status] is the verdict label. */
    data class Completed(
        val status: String,
        val facts: Map<String, Any?> = emptyMap(),
    ) : AgentTurnResult

    data class NotImplementedYet(
        val reason: String = "NOT_IMPLEMENTED_YET",
    ) : AgentTurnResult

    /**
     * The turn could not succeed: AgentOS unreachable, missing configuration,
     * timeout, killed case or case error. The failure is explicit — never a
     * silent success — so the sequencer's failure rule can apply.
     */
    data class Failed(
        val code: String,
        val message: String,
        val facts: Map<String, Any?> = emptyMap(),
    ) : AgentTurnResult
}

/**
 * Port for the "launch one agent turn" capability.
 *
 * W8.3 ships the real HTTP AgentOS implementation
 * ([io.whozoss.factory.capability.AgentOsAgentTurnCapability]); the no-op default
 * ([NoOpAgentTurnCapability]) is kept for pure unit tests. Nothing in this port
 * imports AgentOS internals — the boundary stays HTTP.
 */
interface AgentTurnCapability {
    fun executeAgentTurn(request: AgentTurnRequest): AgentTurnResult
}
