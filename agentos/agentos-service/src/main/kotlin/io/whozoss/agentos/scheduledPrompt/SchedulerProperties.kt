package io.whozoss.agentos.scheduledPrompt

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration properties for the scheduled-prompt execution engine.
 *
 * Override via environment variables using Spring relaxed binding:
 *
 * | Property | Env var | Default | Description |
 * |---|---|---|---|
 * | `agentos.prompt.scheduler.enabled` | `AGENTOS_PROMPT_SCHEDULER_ENABLED` | false | Enable/disable the scheduler |
 * | `agentos.prompt.scheduler.tick-interval-ms` | `AGENTOS_PROMPT_SCHEDULER_TICK_INTERVAL_MS` | 30000 | Interval between claim ticks (ms) — read directly by `@Scheduled`, not via this class |
 * | `agentos.prompt.scheduler.consume-interval-ms` | `AGENTOS_PROMPT_SCHEDULER_CONSUME_INTERVAL_MS` | 10000 | Interval between consume ticks (ms) — read directly by `@Scheduled`, not via this class |
 * | `agentos.prompt.scheduler.batch-size` | `AGENTOS_PROMPT_SCHEDULER_BATCH_SIZE` | 5 | Max UserRuns claimed and executed in parallel per consume tick |
 * | `agentos.prompt.scheduler.launch-timeout-seconds` | `AGENTOS_PROMPT_SCHEDULER_LAUNCH_TIMEOUT_SECONDS` | 30 | Max seconds to wait for a Case to reach IDLE or terminal after launch |
 * | `agentos.prompt.scheduler.lease-minutes` | `AGENTOS_PROMPT_SCHEDULER_LEASE_MINUTES` | 30 | Lease duration for RUNNING UserRuns (minutes) |
 */
@ConfigurationProperties(prefix = "agentos.prompt.scheduler")
data class SchedulerProperties(
    /** Disabled by default; enable in production via env var AGENTOS_PROMPT_SCHEDULER_ENABLED=true. */
    val enabled: Boolean = false,
    /** Maximum number of UserRuns claimed and executed in parallel per consume tick. */
    val batchSize: Int = 5,
    /**
     * Max seconds to wait for a Case to reach IDLE or terminal after launch.
     * Beyond this the UserRun is closed as TIMEOUT (Case continues running independently).
     * Must be strictly less than [leaseMinutes] × 60 — enforced at startup by [SchedulerScanner].
     */
    val launchTimeoutSeconds: Long = 30L,
    /**
     * Lease duration for RUNNING UserRuns in minutes. Expired leases are reclaimed by the next
     * consume tick, creating a second Case for the same user (at-least-once delivery).
     * Must be strictly greater than [launchTimeoutSeconds] ÷ 60 — enforced at startup by [SchedulerScanner].
     */
    val leaseMinutes: Long = 30L,
)
