package io.whozoss.agentos.redirect

import io.whozoss.agentos.agent.AgentInterrupt
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext

/**
 * Internal tool that redirects the current case run to another agent.
 *
 * The LLM selects a target agent by name from the enum declared in [inputSchema].
 * The schema is built at construction time from the list of [eligibleAgents] resolved
 * for the current namespace and whitelist patterns — the LLM can only name agents
 * that actually exist and are accessible.
 *
 * ## Execution contract
 *
 * [execute] returns a human-readable error string when the requested agent is unknown
 * or the input is null. The LLM receives this string and can surface it as a plain
 * message to the user (e.g. "This agent does not exist.").
 *
 * On the happy path, [execute] throws [AgentInterrupt.Redirect] — a control-flow signal:
 * - The [io.whozoss.agentos.agent.AgentSimple] wrapper emits [ToolRequestEvent] and
 *   [ToolResponseEvent] (success=true) **before** calling [execute], so traces are
 *   always complete regardless of the exception.
 * - [io.whozoss.agentos.agent.AgentSimple.run] catches [AgentInterrupt]
 *   before the generic error handler and emits [AgentSelectedEvent] + [AgentFinishedEvent].
 *
 * @param configName The [IntegrationConfig.name] used as tool-name prefix.
 * @param eligibleAgents Agents available for redirection in this namespace, already
 *   filtered by the whitelist patterns from the integration config.
 */
class RedirectTool(
    configName: String?,
    val eligibleAgents: List<EligibleAgent>,
) : StandardTool<RedirectTool.Input> {
    data class Input(val agentName: String)

    /**
     * A resolved agent that the LLM may redirect to.
     *
     * @param name Exact agent name as stored in [AgentConfig.name].
     * @param description Human-readable description injected into the tool schema
     *   so the LLM can reason about which agent to choose without a separate list call.
     */
    data class EligibleAgent(val name: String, val description: String?)

    override val name: String = configName?.let { "${it}__redirect" } ?: "redirect"

    override val description: String = buildString {
        appendLine("Route the current request to another agent. Use this when the request is better handled by a specialised agent.")
        appendLine("Available agents:")
        eligibleAgents.forEach { agent ->
            agent.description?.takeIf { it.isNotBlank() }
                ?.also { desc -> appendLine("  - ${agent.name}: $desc") }
                ?: run { appendLine("  - ${agent.name}") }
        }
    }.trimEnd()

    override val inputSchema: String = buildString {
        val names = eligibleAgents.joinToString(", ") { "\"${it.name}\"" }
        append("""
            {
              "type": "object",
              "properties": {
                "agentName": {
                  "type": "string",
                  "description": "Name of the agent to redirect to.",
                  "enum": [$names]
                }
              },
              "required": ["agentName"]
            }
        """.trimIndent())
    }

    override val version: String = "1.0.0"
    override val paramType: Class<Input> = Input::class.java

    /**
     * Validates the requested agent and throws [AgentInterrupt.Redirect] to hand off.
     *
     * Returns a human-readable error string when [input] is null or when the requested
     * agent name is not in [eligibleAgents]. The LLM receives this string as the
     * [io.whozoss.agentos.sdk.caseEvent.ToolResponseEvent] content and can surface it
     * as a plain-language message to the user (e.g. "This agent does not exist.").
     *
     * Throws [AgentInterrupt.Redirect] on the happy path — this is a control-flow signal,
     * not an error. The caller ([io.whozoss.agentos.agent.AgentSimple]'s tool callback
     * wrapper) emits [io.whozoss.agentos.sdk.caseEvent.ToolRequestEvent] and
     * [io.whozoss.agentos.sdk.caseEvent.ToolResponseEvent] before invoking [executeWithJson],
     * so traces are complete before the exception propagates.
     */
    override suspend fun execute(input: Input?, context: ToolContext): String {
        val target = input?.agentName
        return when {
            target == null -> "Agent name is required."
            eligibleAgents.none { it.name == target } -> "Agent does not exist."
            else -> throw AgentInterrupt.Redirect(target)
        }
    }
}
