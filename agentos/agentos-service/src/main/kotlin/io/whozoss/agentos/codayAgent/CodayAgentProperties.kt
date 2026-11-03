package io.whozoss.agentos.codayAgent

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Bound from `agentos.defaults.coday-agent` (env `AGENTOS_DEFAULTS_CODAY_AGENT_ENABLED`).
 *
 * [enabled] declares that each personal namespace should own a namespace-scoped agent named `Coday`, created
 * when the namespace is provisioned; see [CodayAgentSeeder]. The property keeps its `coday-agent` name: it is a
 * public setting, renaming it would break existing configurations.
 */
@ConfigurationProperties(prefix = "agentos.defaults.coday-agent")
data class CodayAgentProperties(
    val enabled: Boolean = true,
)
