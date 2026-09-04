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
 * | `agentos.prompt.scheduler.windows` | `AGENTOS_PROMPT_SCHEDULER_WINDOWS` | null | Comma-separated open/close window pairs: `DAYOFWEEK HH:mm` in UTC. See [ExecutionWindowService]. |
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
    /**
     * Optional execution time windows. When set, the scheduler only dispatches new claims
     * during the specified UTC windows; outside windows tickClaim is a no-op and prompts
     * accumulate until the next window opens.
     *
     * Format: comma-separated `DAYOFWEEK HH:mm` pairs — alternating open, close boundaries.
     * Day names are case-insensitive [java.time.DayOfWeek] values. Times are UTC `HH:mm`.
     *
     * Example (nightly Mon–Thu + continuous Fri–Mon weekend):
     * `MONDAY 22:00,FRIDAY 05:00,FRIDAY 22:00,MONDAY 05:00`
     *
     * When null or blank, the scheduler runs continuously (existing behaviour).
     * On parse failure the scheduler also runs continuously (fail-open).
     *
     * State is not persisted — on restart the window is re-evaluated from config + current time.
     */
    val windows: String? = null,
)
