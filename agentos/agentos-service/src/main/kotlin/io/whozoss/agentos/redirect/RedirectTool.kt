package io.whozoss.agentos.redirect

import io.whozoss.agentos.agent.AgentInterrupt
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.AgentSelectedEvent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult

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
    override suspend fun execute(input: Input?, context: ToolContext): ToolExecutionResult {
        val target = input?.agentName
        return when {
            target == null -> ToolExecutionResult.error("Agent name is required.", errorType = "MISSING_INPUT")
            eligibleAgents.none { it.name == target } -> ToolExecutionResult.error("Agent does not exist.", errorType = "NOT_FOUND")
            detectRedirectLoop(target, context) -> ToolExecutionResult.error(
                "Redirect loop detected: '$target' has already been invoked during this turn. " +
                    "Stop redirecting and answer the user directly.",
                errorType = "REDIRECT_LOOP",
            )
            else -> throw AgentInterrupt.Redirect(target)
        }
    }

    /**
     * Returns `true` when a redirect loop is detected.
     *
     * A loop is detected when the same agent appears at least [REDIRECT_LOOP_THRESHOLD] times
     * within the last [REDIRECT_LOOP_WINDOW] [AgentSelectedEvent]s since the last user
     * [MessageEvent] (exclusive). The window cap prevents false positives on long but
     * legitimate linear chains while still catching ping-pong and triangular cycles.
     */
    internal fun detectRedirectLoop(target: String, context: ToolContext): Boolean {
        val events = context.caseEvents
        val lastUserMessageIndex = events.indexOfLast { it is MessageEvent && it.actor.role == ActorRole.USER }
        val eventsThisTurn = if (lastUserMessageIndex >= 0) events.drop(lastUserMessageIndex + 1) else events
        val window = eventsThisTurn.filterIsInstance<AgentSelectedEvent>().takeLast(REDIRECT_LOOP_WINDOW)
        return window.count { it.agentName == target } >= REDIRECT_LOOP_THRESHOLD
    }

    companion object {
        /** How many recent agent selections to inspect for a redirect loop. */
        internal const val REDIRECT_LOOP_WINDOW = 10

        /** How many times the same agent must appear in the window to trigger loop detection. */
        internal const val REDIRECT_LOOP_THRESHOLD = 2
    }
}
