package io.whozoss.agentos.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Controls LLM usage accounting and its associated run-cost guard.
 *
 * The shipped `application.yml` enables it (`agentos.usage.enabled: ${AGENTOS_USAGE_ENABLED:true}`), so a running
 * service tracks usage unless `AGENTOS_USAGE_ENABLED=false`. The code default stays `false` on purpose: a class
 * built by hand, such as a service in a unit test, is inert unless it is given `enabled = true`. This is the same
 * split as [LimitsConfigProperties.runCostThreshold], null in code and 2 in the yml.
 */
@ConfigurationProperties(prefix = "agentos.usage")
data class UsageConfigProperties(
    val enabled: Boolean = false,
)
