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
 * ### Exception handling — fail-closed
 *
 * A policy exists only to *restrict* what an agent may do, so a policy that cannot
 * answer must not be read as consent: treating its failure as [ToolGrantDecision.Neutral]
 * would hand the agent exactly the tools the policy was installed to withhold.
 *
 * A policy must therefore never throw to express a refusal — it must return
 * [ToolGrantDecision.Deny]. Throwing is reserved for genuine faults.
 *
 * The evaluation rule (fail-closed, cancellation-safe) lives in the service layer
 * (`SpiEvaluatorService`), not here, so improvements to it never require a new SDK
 * version or a rebuild of deployed plugins.
 *
 * ### Suspension
 *
 * [evaluateToolGrant] is a suspending function. Tool resolution happens on the agent run
 * path, so a policy that consults a remote authority must be able to suspend rather than
 * block the dispatcher, and must remain cancellable — a long evaluation should cooperate
 * via `yield()` / `ensureActive()`.
 */
interface ToolGrantPolicy : ExtensionPoint {
    /**
     * Evaluate the grant for a single tool.
     *
     * The agent identity is available via [ToolContext.agentName], which is the single
     * authoritative source for the agent the tool is being resolved for.
     *
     * @param toolName the fully-qualified tool name being evaluated.
     * @param context the runtime tool context (namespace, user, agent name, case events, …).
     * @return the policy's decision for this tool; [ToolGrantDecision.Neutral] when the
     *   policy has no opinion.
     */
    suspend fun evaluateToolGrant(
        toolName: String,
        context: ToolContext,
    ): ToolGrantDecision = ToolGrantDecision.Neutral
}
