package io.whozoss.agentos.caseFlow

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Configuration of the prompt cache keep-alive of the cases ([CaseKeepAliveScheduler]).
 *
 * Bound from the `agentos.keep-alive` prefix.
 *
 * ```yaml
 * agentos:
 *   keep-alive:
 *     max-idle: 2h   # AGENTOS_KEEP_ALIVE_MAX_IDLE
 * ```
 */
@ConfigurationProperties(prefix = "agentos.keep-alive")
data class CaseKeepAliveProperties(
    /**
     * How long after the agent's last real request of a case its cache may still be refreshed. Bounds what an
     * abandoned conversation can cost: past it, the case gets no refresh until its next agent turn.
     *
     * Defaults to 2 hours.
     */
    val maxIdle: Duration = Duration.ofHours(2),
    /** The interval between two checks (`tick-interval-ms`), also read by the `@Scheduled` of the scheduler. */
    val tickIntervalMs: Long = 20_000,
)
