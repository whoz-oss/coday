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
 * | `agentos.prompt.scheduler.tick-interval-ms` | `AGENTOS_PROMPT_SCHEDULER_TICK_INTERVAL_MS` | 60000 | Interval between claim ticks (ms) — read directly by `@Scheduled`, not via this class. Also drives the Run parent completion scan (`recoverOrphanedRunningRuns`) and the consumer-loop watchdog (`tickWatchdog`) |
 * | `agentos.prompt.scheduler.batch-size` | `AGENTOS_PROMPT_SCHEDULER_BATCH_SIZE` | 25 | Max UserRuns claimed per producer iteration |
 * | `agentos.prompt.scheduler.worker-count` | `AGENTOS_PROMPT_SCHEDULER_WORKER_COUNT` | 5 | Number of parallel consumer workers |
 * | `agentos.prompt.scheduler.channel-capacity` | `AGENTOS_PROMPT_SCHEDULER_CHANNEL_CAPACITY` | 50 | Channel buffer size (2 × batchSize — double-buffering) |
 * | `agentos.prompt.scheduler.empty-poll-delay-ms` | `AGENTOS_PROMPT_SCHEDULER_EMPTY_POLL_DELAY_MS` | 5000 | Delay (ms) when claimBatch returns empty — avoids busy-looping |
 * | `agentos.prompt.scheduler.paused-poll-delay-ms` | `AGENTOS_PROMPT_SCHEDULER_PAUSED_POLL_DELAY_MS` | 10000 | Delay (ms) between producer iterations when consume is paused |
 * | `agentos.prompt.scheduler.launch-timeout-seconds` | `AGENTOS_PROMPT_SCHEDULER_LAUNCH_TIMEOUT_SECONDS` | 30 | Max seconds to wait for a Case to reach IDLE or terminal after launch |
 * | `agentos.prompt.scheduler.lease-minutes` | `AGENTOS_PROMPT_SCHEDULER_LEASE_MINUTES` | 30 | Lease duration for RUNNING UserRuns (minutes) |
 */
@ConfigurationProperties(prefix = "agentos.prompt.scheduler")
data class SchedulerProperties(
    /** Disabled by default; enable in production via env var AGENTOS_PROMPT_SCHEDULER_ENABLED=true. */
    val enabled: Boolean = false,
    /**
     * Maximum number of UserRuns claimed per producer iteration.
     * A larger batch reduces DB round-trips; the channel capacity should be at least
     * `batchSize` so the producer never suspends mid-batch.
     */
    val batchSize: Int = 25,
    /**
     * Number of coroutines consuming [ScheduledPromptUserRun]s from the channel in parallel.
     * A higher value increases the throughput of Case creation at the cost of more
     * concurrent database and downstream API calls.
     */
    val workerCount: Int = 5,
    /**
     * Capacity of the channel between producer and workers.
     * `2 × batchSize` (double-buffering): the producer can fill a second batch into the
     * channel while workers drain the first, keeping all workers continuously fed without
     * pre-claiming an excessive number of leased UserRuns.
     */
    val channelCapacity: Int = 50,
    /**
     * Delay in milliseconds when [claimBatch][io.whozoss.agentos.scheduledPrompt.ScheduledPromptUserRunRepository.claimBatch]
     * returns an empty list. Prevents the producer from busy-looping against the database
     * when no UserRuns are pending.
     */
    val emptyPollDelayMs: Long = 5_000L,
    /**
     * Delay in milliseconds between producer iterations when consume is paused.
     * Keeps the producer responsive to resume without busy-looping.
     */
    val pausedPollDelayMs: Long = 10_000L,
    /**
     * Max seconds to wait for a Case to reach IDLE or terminal after launch.
     * Beyond this the UserRun is closed as TIMEOUT (Case continues running independently).
     * Must be strictly less than [leaseMinutes] × 60 — enforced at startup by [SchedulerScanner].
     */
    val launchTimeoutSeconds: Long = 30L,
    /**
     * Lease duration for RUNNING UserRuns in minutes. Expired leases are reclaimed by the
     * producer loop, creating a second Case for the same user (at-least-once delivery).
     * Must be strictly greater than [launchTimeoutSeconds] ÷ 60 — enforced at startup by [SchedulerScanner].
     */
    val leaseMinutes: Long = 30L,
)
