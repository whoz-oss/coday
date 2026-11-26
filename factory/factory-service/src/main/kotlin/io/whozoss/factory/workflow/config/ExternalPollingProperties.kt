package io.whozoss.factory.workflow.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Polling policy of a step parked in `waiting_external` (CI checks, agent IN_PROGRESS).
 *
 * Bound from `factory.polling.*`. Values are clamped at read time so a misconfiguration can neither
 * busy-loop the outbox (interval below [minIntervalSeconds]) nor poll without bound.
 */
@ConfigurationProperties(prefix = "factory.polling")
data class ExternalPollingProperties(
    /** Interval between two polls when the step does not ask for a specific one. */
    val defaultIntervalSeconds: Int = 60,
    val minIntervalSeconds: Int = 30,
    val maxIntervalSeconds: Int = 300,
    /** Maximum wait of one step execution (30 minutes). */
    val maxWaitSeconds: Int = 1800,
) {
    /** Effective interval in seconds for a requested one, kept within the configured bounds. */
    fun clampInterval(requestedSeconds: Int?): Int {
        val min = minIntervalSeconds.coerceAtLeast(1)
        val max = maxIntervalSeconds.coerceAtLeast(min)
        return (requestedSeconds ?: defaultIntervalSeconds).coerceIn(min, max)
    }

    /** Effective maximum wait in seconds (never below one interval). */
    val effectiveMaxWaitSeconds: Int get() = maxWaitSeconds.coerceAtLeast(minIntervalSeconds.coerceAtLeast(1))
}
