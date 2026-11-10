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
 * - AGENTOS_CASE_GLOBAL_EVENTS_POLL_INTERVAL_MS
 * - AGENTOS_CASE_SUMMARY_ENABLED
 * - AGENTOS_CASE_ADMIN_LIST_ALL_CASES_DEFAULT
 *
 * Example (application.yml):
 * ```yaml
 * agentos:
 *   case:
 *     idle-eviction-grace-ms: 300000        # 5 min (default)
 *     sse-heartbeat-interval-ms: 30000      # 30 s (default)
 *     global-events-poll-interval-ms: 5000  # 5 s (default)
 *     summary-enabled: true            # case summaries feeding the case search (default)
 *     admin-list-all-cases-default: false   # no "All cases" drawer switch for namespace admins (default)
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

    /**
     * Interval between re-scans of the active-case set for the global "my events" SSE
     * stream (`GET /api/cases/events/mine`).
     *
     * There is no push-based case-created/case-removed event to react to, so the global
     * stream polls [CaseService.getAllActiveCases] intersected with
     * [CaseService.findConcerningUser] on this cadence to start/stop per-case collectors.
     *
     * Defaults to 5 s (5 000 ms).
     */
    val globalEventsPollIntervalMs: Long = 5_000L,

    /**
     * Whether a short summary is generated and kept up to date for each top-level case (it feeds the
     * case search). Each refresh is one small utility LLM call on the namespace default model, see
     * [CaseSummaryService]. Set to false to switch the feature off; existing summaries are kept.
     *
     * Defaults to true.
     */
    val summaryEnabled: Boolean = true,

    /**
     * Platform default of the namespace setting [io.whozoss.agentos.namespace.Namespace.adminCanListAllCases]:
     * whether the administrators of a namespace get a "Mine / All cases" switch in the case drawer.
     * A namespace that sets the option itself keeps its own value; `null` there inherits this one.
     *
     * It only drives the drawer. It does not change who may read which case, nor the behaviour of
     * `GET /api/cases/by-parentId/{parentId}`, whose namespace-ADMIN listing is always unfiltered.
     *
     * Defaults to false.
     */
    val adminListAllCasesDefault: Boolean = false,
)
