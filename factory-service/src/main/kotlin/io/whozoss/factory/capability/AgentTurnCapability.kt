package io.whozoss.factory.capability

import java.nio.file.Path

/**
 * Request to run one agent turn for a declarative session step.
 *
 * [persona] is the resolved `responsibility.name` of an `agent` step. The
 * implementation (W8.3) starts an AgentOS case over its HTTP contract and runs
 * the turn to quiescence; this port carries only the facts.
 */
data class AgentTurnRequest(
    val stepId: String,
    val persona: String?,
    val repoRoot: Path,
    val namespaceId: String? = null,
    val workflowId: String? = null,
)

/** Facts returned by an agent-turn execution; never LLM prose. */
sealed interface AgentTurnResult {
    data class Completed(
        val status: String,
        val facts: Map<String, Any?> = emptyMap(),
    ) : AgentTurnResult

    data class NotImplementedYet(
        val reason: String = "NOT_IMPLEMENTED_YET",
    ) : AgentTurnResult
}

/**
 * Port for the "launch one agent turn" capability.
 *
 * W8.2 ships only the no-op default ([NoOpAgentTurnCapability]): the real
 * implementation is the HTTP AgentOS client wired in W8.3. Nothing in this port
 * imports AgentOS internals — the boundary stays HTTP.
 */
interface AgentTurnCapability {
    fun executeAgentTurn(request: AgentTurnRequest): AgentTurnResult
}
