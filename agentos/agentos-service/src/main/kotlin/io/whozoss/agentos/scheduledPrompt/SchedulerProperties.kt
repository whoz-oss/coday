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
 * | `scheduler.tick-interval-ms` | `SCHEDULER_TICK_INTERVAL_MS` | 30000 | Interval between claim ticks (ms) — read directly by `@Scheduled`, not via this class |
 * | `scheduler.consume-interval-ms` | `SCHEDULER_CONSUME_INTERVAL_MS` | 10000 | Interval between consume ticks (ms) — read directly by `@Scheduled`, not via this class |
 * | `scheduler.batch-size` | `SCHEDULER_BATCH_SIZE` | 20 | Max UserRuns claimed and executed in parallel per consume tick |
 * | `scheduler.launch-stagger-ms` | `SCHEDULER_LAUNCH_STAGGER_MS` | 50 | Delay (ms) between consecutive Case launches within a batch |
 * | `scheduler.launch-timeout-seconds` | `SCHEDULER_LAUNCH_TIMEOUT_SECONDS` | 30 | Max seconds to wait for a Case to reach IDLE or terminal after launch |
 * | `scheduler.lease-minutes` | `SCHEDULER_LEASE_MINUTES` | 30 | Lease duration for RUNNING UserRuns (minutes) |
 */
@ConfigurationProperties(prefix = "scheduler")
data class SchedulerProperties(
    /** Disabled by default; enable in production via env var SCHEDULER_ENABLED=true. */
    val enabled: Boolean = false,
    /** Maximum number of UserRuns claimed and executed in parallel per consume tick. */
    val batchSize: Int = 20,
    /** Delay (ms) between consecutive Case launches within a batch. Spreads the initial burst to reduce peak memory pressure. */
    val launchStaggerMs: Long = 50L,
    /** Max seconds to wait for a Case to reach IDLE or terminal after launch. Beyond this the UserRun is closed as DONE (Case continues running independently). */
    val launchTimeoutSeconds: Long = 30L,
    /** Lease duration for RUNNING UserRuns in minutes. Expired leases are reclaimed by the next tick. */
    val leaseMinutes: Long = 30L,
)
