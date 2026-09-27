package io.whozoss.factory.capability

import org.springframework.stereotype.Component

/**
 * Default no-op [AgentTurnCapability] for W8.2.
 *
 * The agent-turn transport (HTTP AgentOS, active case, quiescence,
 * step-result binding) is explicitly scheduled for W8.3; until then every
 * `agent` step resolves to `NOT_IMPLEMENTED_YET`, never a silent success.
 */
@Component
class NoOpAgentTurnCapability : AgentTurnCapability {
    override fun executeAgentTurn(request: AgentTurnRequest): AgentTurnResult =
        AgentTurnResult.NotImplementedYet("Agent turn execution is scheduled for W8.3")
}
