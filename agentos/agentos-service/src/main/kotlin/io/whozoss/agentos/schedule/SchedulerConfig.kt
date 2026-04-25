package io.whozoss.agentos.schedule

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration properties for the schedule executor.
 *
 * Bound from the `agentos.scheduler` prefix in `application.yml`.
 *
 * | Property | Default | Description |
 * |---|---|---|
 * | `enabled` | `true` | Set to `false` to disable the polling loop entirely |
 * | `poll-interval-seconds` | `60` | How often to poll for due schedules (seconds) |
 * | `catch-up-policy` | `LAST` | How to handle missed occurrences after downtime |
 */
@ConfigurationProperties(prefix = "agentos.scheduler")
data class SchedulerConfig(
    val enabled: Boolean = true,
    val pollIntervalSeconds: Long = 60,
    val catchUpPolicy: CatchUpPolicy = CatchUpPolicy.LAST,
)
