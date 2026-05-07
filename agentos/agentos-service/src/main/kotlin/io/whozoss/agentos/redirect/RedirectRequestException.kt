package io.whozoss.agentos.redirect

/**
 * Signal exception thrown by [RedirectTool] to interrupt the current Spring AI
 * tool-calling loop and hand control to a different agent.
 *
 * This is NOT an error condition. The tool emits a successful [ToolResponseEvent]
 * before throwing so the event traces are complete and no failure is recorded.
 * [io.whozoss.agentos.agent.AgentSimple] catches this exception before the generic
 * error handler and emits [io.whozoss.agentos.sdk.caseEvent.AgentSelectedEvent] +
 * [io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent] to trigger the next agent.
 *
 * @param targetAgentName The exact name of the agent to redirect to, as it appears
 *   in the [io.whozoss.agentos.agentConfig.AgentConfig] of the namespace.
 */
class RedirectRequestException(val targetAgentName: String) : Exception("Redirect to '$targetAgentName'")
