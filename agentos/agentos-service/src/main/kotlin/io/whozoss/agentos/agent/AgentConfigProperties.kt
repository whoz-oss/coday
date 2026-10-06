package io.whozoss.agentos.agent

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Platform-level agent configuration properties.
 *
 * Bound from the `agentos.defaults` prefix in application.yml.
 *
 * [agentName] is the environment-level fallback agent name. It is consulted
 * when a namespace has no [io.whozoss.agentos.namespace.Namespace.defaultAgentName]
 * configured, and is resolved in the same way — by name against the [AgentConfig]
 * entries of the case's namespace. This means the named agent must exist in each
 * namespace that wants to benefit from the fallback.
 *
 * Fallback resolution order when routing a conversation:
 * 1. Namespace default agent ([Namespace.defaultAgentName]), if configured
 * 2. Environment default agent ([agentName]), if configured
 * 3. Silent fail (WarnEvent emitted, no agent selected)
 *
 * [imageCharCost] and [maxAttachedImages] bound how tool-response images are replayed
 * into an agent's LLM context (see [AgentAdvancedContext]).
 * [delegationTimeoutMinutes] bounds how long a delegation may run before its sub-case is killed.
 *
 * Override with environment variables (Spring Boot relaxed binding):
 * - AGENTOS_DEFAULTS_AGENT_NAME
 * - AGENTOS_DEFAULTS_IMAGE_CHAR_COST
 * - AGENTOS_DEFAULTS_MAX_ATTACHED_IMAGES
 * - AGENTOS_DEFAULTS_DELEGATION_TIMEOUT_MINUTES
 *
 * Example (application.yml):
 * ```yaml
 * agentos:
 *   defaults:
 *     agent-name: copilot
 *     image-char-cost: 6000
 *     max-attached-images: 20
 *     delegation-timeout-minutes: 15
 * ```
 */
@ConfigurationProperties(prefix = "agentos.defaults")
data class AgentConfigProperties(
    val agentName: String? = null,
    /**
     * Char-equivalent cost of one attached image against the detailed-tool budget.
     * Derived from the legacy Coday estimate: (width * height) / 750 tokens at
     * ~3.5 chars per token, ~4 900 chars for a full-size 1024x1024 image,
     * rounded up to 6 000.
     */
    val imageCharCost: Int = 6_000,
    /** Maximum images attached as Media across the whole prompt, newest first. */
    val maxAttachedImages: Int = 20,
    /** Maximum wall-clock time for each outgoing delegation, in minutes. */
    val delegationTimeoutMinutes: Long = 15,
) {
    init {
        require(delegationTimeoutMinutes > 0) { "agentos.defaults.delegation-timeout-minutes must be positive" }
    }
}
