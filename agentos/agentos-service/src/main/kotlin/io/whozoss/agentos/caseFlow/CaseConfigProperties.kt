package io.whozoss.agentos.caseFlow

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Runtime configuration for the case execution engine.
 *
 * Bound from the `agentos.case` prefix in application.yml.
 *
 * Override with environment variables (Spring Boot relaxed binding):
 * - AGENTOS_CASE_IDLE_EVICTION_GRACE_MS
 * - AGENTOS_CASE_SSE_HEARTBEAT_INTERVAL_MS
 *
 * Example (application.yml):
 * ```yaml
 * agentos:
 *   case:
 *     idle-eviction-grace-ms: 300000   # 5 min (default)
 *     sse-heartbeat-interval-ms: 30000 # 30 s (default)
 * ```
 */
@ConfigurationProperties(prefix = "agentos.case")
data class CaseConfigProperties(
    /**
     * Grace period after the last SSE subscriber disconnects from an idle case before
     * the runtime is evicted from memory.
     *
     * Aligned with typical LLM prompt-cache TTLs (~5 min): keeping the runtime alive
     * beyond that offers no cache benefit. A user who takes more than 5 min to reply
     * is unlikely to continue the conversation immediately.
     *
     * Defaults to 5 min (300 000 ms).
     */
    val idleEvictionGraceMs: Long = 5 * 60 * 1_000L,

    /**
     * Interval between SSE keep-alive comment frames sent to connected clients.
     *
     * When a case is IDLE the live flow emits nothing. Tomcat cannot detect a client
     * disconnect until the next write attempt. Without periodic writes, the collector
     * coroutine stays suspended indefinitely, keeping the SharedFlow subscriber alive
     * and preventing the eviction watcher from seeing `subscriptionCount == 0`.
     *
     * A comment frame is invisible to the browser EventSource API but forces a socket
     * write that reveals the disconnect on the next heartbeat after the client closes.
     *
     * Defaults to 30 s (30 000 ms).
     */
    val sseHeartbeatIntervalMs: Long = 30_000L,
)
