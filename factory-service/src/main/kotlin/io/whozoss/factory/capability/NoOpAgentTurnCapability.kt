package io.whozoss.factory.capability

/**
 * Default no-op [AgentTurnCapability].
 *
 * Kept as the fallback default of [CapabilityResolver] so pure unit tests can
 * construct a resolver without a transport. It is deliberately NOT a Spring
 * bean: the wired implementation is [AgentOsAgentTurnCapability]. Every `agent`
 * step resolved through this no-op returns `NOT_IMPLEMENTED_YET`, never a silent
 * success.
 */
class NoOpAgentTurnCapability : AgentTurnCapability {
    override fun executeAgentTurn(request: AgentTurnRequest): AgentTurnResult =
        AgentTurnResult.NotImplementedYet("Agent turn execution is scheduled for W8.3")
}
