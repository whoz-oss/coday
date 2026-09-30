package io.whozoss.agentos.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** Controls LLM usage accounting and its associated run-cost guard. Disabled until explicitly enabled. */
@ConfigurationProperties(prefix = "agentos.usage")
data class UsageConfigProperties(
    val enabled: Boolean = false,
)
