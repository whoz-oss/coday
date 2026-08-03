package io.whozoss.agentos.scheduledPrompt

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration properties for the scheduled-prompt execution engine.
 *
 * Override via environment variables using Spring relaxed binding:
 *
 * | Property | Env var | Default | Description |
 * |---|---|---|---|
 * | `scheduler.tick-interval-ms` | `SCHEDULER_TICK_INTERVAL_MS` | 60000 | Interval between scanner ticks (ms) |
 */
@ConfigurationProperties(prefix = "scheduler")
data class SchedulerProperties(
    /** Set to false to disable the scheduler (useful in tests). */
    val enabled: Boolean = true,
    /** Interval between scanner ticks in milliseconds. */
    val tickIntervalMs: Long = 60_000L,
)
