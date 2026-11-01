package io.whozoss.agentos.codayAgent

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Bound from `agentos.defaults.coday-agent` (env `AGENTOS_DEFAULTS_CODAY_AGENT_ENABLED`).
 *
 * [enabled] declares that a platform agent named `Coday` should exist; see [CodayAgentSeeder].
 */
@ConfigurationProperties(prefix = "agentos.defaults.coday-agent")
data class CodayAgentProperties(
    val enabled: Boolean = true,
)
