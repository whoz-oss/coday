package io.whozoss.agentos.sdk.spi

import io.whozoss.agentos.sdk.tool.ToolContext
import org.pf4j.ExtensionPoint

/**
 * Outcome of a [ToolGrantPolicy.evaluateToolGrant] call for a single tool.
 */
sealed interface ToolGrantDecision {
    /**
     * The policy has no opinion: the runtime's default grant behavior for the tool is
     * left untouched. This is the neutral, pass-through decision.
     */
    data object Neutral : ToolGrantDecision

    /**
     * Only the listed tool names may be granted. Every tool not present in [toolNames]
     * is denied by this policy.
     */
    data class AllowOnly(val toolNames: Set<String>) : ToolGrantDecision

    /**
     * The listed tool names are denied. Every tool not present in [toolNames] is left
     * untouched by this policy.
     *
     * @param reason optional human-readable justification for observability.
     */
    data class Deny(val toolNames: Set<String>, val reason: String? = null) : ToolGrantDecision
}

/**
 * Generic SPI extension point that evaluates whether a tool may be granted to an agent
 * run.
 *
 * Policies are consulted once per resolved tool, with the full runtime [ToolContext]
 * available for contextual decisions. Several policies may be active at once; a tool is
 * granted only when no policy denies it (a tool explicitly allowed by one policy may
 * still be denied by another).
 *
 * ### Safe default
 *
 * [evaluateToolGrant] defaults to [ToolGrantDecision.Neutral], so the contract is a pure
 * pass-through when an implementation has no restriction to express, and existing
 * behavior is preserved when no policy is registered.
 *
 * ### Exception handling
 *
 * Unexpected exceptions thrown by a policy are caught and logged by the caller and
 * treated as [ToolGrantDecision.Neutral] (fail-open), so a faulty hook cannot silently
 * strip tools from an agent.
 */
interface ToolGrantPolicy : ExtensionPoint {
    /**
     * Evaluate the grant for a single tool.
     *
     * @param agentName the agent the tool is being resolved for, or null when unknown.
     * @param toolName the fully-qualified tool name being evaluated.
     * @param context the runtime tool context (namespace, user, case events, …).
     * @return the policy's decision for this tool; [ToolGrantDecision.Neutral] when the
     *   policy has no opinion.
     */
    fun evaluateToolGrant(
        agentName: String?,
        toolName: String,
        context: ToolContext,
    ): ToolGrantDecision = ToolGrantDecision.Neutral
}
