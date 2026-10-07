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
 * Use [isGranted] to evaluate policies: a policy that throws denies the tool under
 * evaluation.
 *
 * This is deliberate. A policy exists only to *restrict* what an agent may do, so a
 * policy that cannot answer must not be read as consent: treating its failure as
 * [ToolGrantDecision.Neutral] would hand the agent exactly the tools the policy was
 * installed to withhold, and would do so silently. Denying one tool degrades a run;
 * granting one that a policy meant to refuse breaks the boundary the policy defines.
 *
 * A policy must therefore never throw to express a refusal — it must return
 * [ToolGrantDecision.Deny]. Throwing is reserved for genuine faults, and is treated as
 * the safe answer rather than the convenient one.
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

    companion object {
        /**
         * Evaluate every policy for one tool and return whether it may be granted.
         *
         * Lives in the SDK so the fail-closed rule is applied once, here, rather than
         * re-implemented at each call site — where a `runCatching { … }.getOrElse { … }`
         * could quietly pick the permissive branch.
         *
         * A tool is granted only when **no** policy denies it:
         *
         * - [ToolGrantDecision.Neutral] — no opinion, keep evaluating;
         * - [ToolGrantDecision.AllowOnly] — denies the tool when absent from the set;
         * - [ToolGrantDecision.Deny] — denies the tool when present in the set;
         * - a policy that **throws** — denies the tool.
         *
         * Evaluation short-circuits on the first denial: the verdict can no longer
         * change, and a policy already known to be faulty should not be consulted again.
         *
         * @param onDenied notified with a human-readable reason whenever the tool is
         *   denied, including the faulty-policy case; must never throw.
         */
        fun isGranted(
            policies: Iterable<ToolGrantPolicy>,
            agentName: String?,
            toolName: String,
            context: ToolContext,
            onDenied: (reason: String, policy: ToolGrantPolicy, cause: Throwable?) -> Unit = { _, _, _ -> },
        ): Boolean {
            policies.forEach { policy ->
                val decision =
                    try {
                        policy.evaluateToolGrant(agentName, toolName, context)
                    } catch (e: Exception) {
                        onDenied("policy threw while evaluating '$toolName'; denying (fail-closed)", policy, e)
                        return false
                    }
                when (decision) {
                    is ToolGrantDecision.Neutral -> Unit

                    is ToolGrantDecision.AllowOnly ->
                        if (toolName !in decision.toolNames) {
                            onDenied("'$toolName' is not in the policy's allow-list", policy, null)
                            return false
                        }

                    is ToolGrantDecision.Deny ->
                        if (toolName in decision.toolNames) {
                            onDenied(decision.reason ?: "'$toolName' is denied by policy", policy, null)
                            return false
                        }
                }
            }
            return true
        }
    }
}
