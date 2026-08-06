package io.whozoss.agentos.scheduledPrompt

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration properties for the scheduled-prompt execution engine.
 *
 * Override via environment variables using Spring relaxed binding:
 *
 * | Property | Env var | Default | Description |
 * |---|---|---|---|
 * | `scheduler.enabled` | `SCHEDULER_ENABLED` | false | Enable/disable the scheduler |
 * | `scheduler.tick-interval-ms` | `SCHEDULER_TICK_INTERVAL_MS` | 60000 | Interval between scanner ticks (ms) |
 * | `scheduler.batch-size` | `SCHEDULER_BATCH_SIZE` | 20 | Max UserRuns claimed per batch |
 * | `scheduler.lease-minutes` | `SCHEDULER_LEASE_MINUTES` | 30 | Lease duration for RUNNING UserRuns (minutes) |
 */
@ConfigurationProperties(prefix = "scheduler")
data class SchedulerProperties(
    /** Disabled by default; enable in production via env var SCHEDULER_ENABLED=true. */
    val enabled: Boolean = false,
    /** Interval between scanner ticks in milliseconds. */
    val tickIntervalMs: Long = 60_000L,
    /** Maximum number of UserRuns claimed per batch. */
    val batchSize: Int = 20,
    /** Lease duration for RUNNING UserRuns in minutes. Expired leases are reclaimed by the next tick. */
    val leaseMinutes: Long = 30L,
    /** Interval between consume ticks in milliseconds. */
    val consumeIntervalMs: Long = 10_000L,
)
