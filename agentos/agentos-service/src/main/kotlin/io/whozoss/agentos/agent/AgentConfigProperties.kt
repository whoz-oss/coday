package io.whozoss.agentos.agent

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Platform-level agent configuration properties.
 *
 * Bound from the `agentos.agent` prefix in application.yml.
 *
 * [defaultAgentName] is the environment-level fallback agent name. It is consulted
 * when a namespace has no [io.whozoss.agentos.namespace.Namespace.defaultAgentName]
 * configured, and is resolved in the same way — by name against the [AgentConfig]
 * entries of the case's namespace. This means the named agent must exist in each
 * namespace that wants to benefit from the fallback.
 *
 * Fallback resolution order when routing a conversation:
 * 1. Namespace default agent ([Namespace.defaultAgentName]), if configured
 * 2. Environment default agent ([defaultAgentName]), if configured
 * 3. Silent fail (WarnEvent emitted, no agent selected)
 *
 * Override with environment variable (Spring Boot relaxed binding):
 * - AGENTOS_DEFAULTS_AGENT_NAME
 *
 * Example (application.yml):
 * ```yaml
 * agentos:
 *   defaults:
 *     agent-name: copilot
 * ```
 */
@ConfigurationProperties(prefix = "agentos.defaults")
data class AgentConfigProperties(
    val agentName: String? = null,
)
