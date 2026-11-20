package io.whozoss.agentos.caseFlow

import io.whozoss.agentos.agent.LastLlmRequest
import io.whozoss.agentos.agent.LastLlmRequestRegistry
import io.whozoss.agentos.chat.sentTo
import io.whozoss.agentos.config.UsageConfigProperties
import io.whozoss.agentos.sdk.aiProvider.ReasoningEffort
import io.whozoss.agentos.sdk.api.user.PromptCacheTtl
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.usage.RunCostService
import jakarta.annotation.PreDestroy
import mu.KLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Keeps the provider's prompt cache of idle conversations warm, for the cases that turned [Case.keepAlive] on.
 *
 * Every tick (`agentos.keep-alive.tick-interval-ms`, 20 s by default) it looks at the agent's last request of each
 * case ([LastLlmRequestRegistry]) and has [CaseKeepAlivePinger] send it again when its cache is about to expire:
 * [refreshInterval] after the later of its capture and its last refresh, that is 4:40 for a 5-minute cache and
 * 59:30 for a 1-hour one ([LastLlmRequest.promptCacheTtl]). A cache read refreshes the cache lifetime, so the next
 * real message of the user still finds the conversation cached. A successful ping [LastLlmRequestRegistry.touch]es
 * the request, which keeps it from expiring while the keep-alive runs.
 *
 * A case is not pinged when:
 * - its keep-alive is off, it was removed, or it has no request in the registry (no agent turn since the start of
 *   the process, an ADVANCED agent, expired);
 * - it is not [CaseStatus.IDLE]: a running turn refreshes the cache by itself;
 * - it is paused by the run cost guard or at its run cost threshold;
 * - more than [CaseKeepAliveProperties.maxIdle] has passed since the agent's last real request;
 * - the request was sent with a reasoning effort: a 1-token answer is invalid with thinking (logged once);
 * - its provider is not OpenAI-compatible ([CaseKeepAlivePinger.supports], logged once);
 * - its last [MAX_CONSECUTIVE_FAILURES] pings failed: it is then left alone until the next agent turn.
 *
 * Pings run on a small pool of their own, so that a slow provider holds neither the scheduling thread nor the
 * next ticks; a case whose ping is still in flight is skipped.
 *
 * **Single instance**: the registry and the state of the scheduler are in memory. With several instances, each
 * one only refreshes the cases whose agent turns it ran; nothing coordinates them (enough as long as a case runs
 * on one instance, which is the deployment AgentOS supports today).
 */
@Component
class CaseKeepAliveScheduler(
    private val registry: LastLlmRequestRegistry,
    private val caseService: CaseService,
    private val pinger: CaseKeepAlivePinger,
    private val properties: CaseKeepAliveProperties,
    private val clock: Clock,
    private val usageConfig: UsageConfigProperties,
    private val runCostService: RunCostService? = null,
) {
    /** Where the pings run. A seam for the tests, which run them in place. */
    internal var executor: Executor = defaultExecutor()

    /** What the scheduler knows of the request of a case, reset when a new turn replaces the request. */
    private class Tracking(
        val capturedAt: Instant,
    ) {
        @Volatile var failures = 0

        @Volatile var skipLogged = false
    }

    private val tracking = ConcurrentHashMap<UUID, Tracking>()
    private val inFlight = ConcurrentHashMap.newKeySet<UUID>()

    @Scheduled(
        fixedDelayString = "\${agentos.keep-alive.tick-interval-ms:20000}",
        initialDelayString = "\${agentos.keep-alive.tick-interval-ms:20000}",
    )
    fun tick() {
        try {
            refreshDue()
        } catch (e: Exception) {
            logger.warn(e) { "[KeepAlive] tick failed" }
        }
    }

    /** Starts the pings due now; returns the ids of the cases pinged. */
    internal fun refreshDue(): List<UUID> {
        val now = clock.instant()
        val requests = registry.all()
        tracking.keys.retainAll(requests.mapTo(HashSet()) { it.caseId })
        val due =
            requests
                .map { it to trackingOf(it) }
                .filter { (request, state) ->
                    state.failures < MAX_CONSECUTIVE_FAILURES &&
                        request.caseId !in inFlight &&
                        Duration.between(request.capturedAt, now) <= properties.maxIdle &&
                        !now.isBefore(request.lastUsedAt.plus(refreshInterval(request.promptCacheTtl)))
                }
        if (due.isEmpty()) return emptyList()
        val cases = caseService.findByIds(due.map { it.first.caseId }, withRemoved = false).associateBy { it.id }
        return due.mapNotNull { (request, state) ->
            val case = cases[request.caseId]
            if (case == null || !shouldPing(case, request, state)) {
                null
            } else {
                start(case, request, state, now)
                case.id
            }
        }
    }

    private fun trackingOf(request: LastLlmRequest): Tracking {
        val current = tracking[request.caseId]
        if (current != null && current.capturedAt == request.capturedAt) return current
        return Tracking(request.capturedAt).also { tracking[request.caseId] = it }
    }

    private fun shouldPing(
        case: Case,
        request: LastLlmRequest,
        state: Tracking,
    ): Boolean {
        if (!case.keepAlive || case.status != CaseStatus.IDLE) return false
        val options = request.requestOptions
        val effort = options.reasoningEffort?.sentTo(options.apiType)
        if (effort != null && effort != ReasoningEffort.NONE) {
            logSkipOnce(request, state) { "model ${options.apiModelName} runs with reasoning effort $effort: a 1-token answer is invalid" }
            return false
        }
        if (!CaseKeepAlivePinger.supports(options.apiType)) {
            logSkipOnce(request, state) { "provider API type ${options.apiType} is not supported" }
            return false
        }
        return !isCostBlocked(case.id)
    }

    private fun isCostBlocked(caseId: UUID): Boolean {
        val runCost = runCostService ?: return false
        if (!usageConfig.enabled) return false
        return try {
            val state = runCost.state(caseId)
            val threshold = state.runCostThreshold
            state.paused || (threshold != null && state.cost >= threshold)
        } catch (e: Exception) {
            // Without a run cost, the threshold cannot be checked: no ping, and a new try at the next tick.
            logger.warn(e) { "[KeepAlive] run cost of case $caseId unavailable, not refreshed" }
            true
        }
    }

    private fun start(
        case: Case,
        request: LastLlmRequest,
        state: Tracking,
        now: Instant,
    ) {
        inFlight.add(case.id)
        try {
            executor.execute {
                try {
                    ping(case, request, state, now)
                } finally {
                    inFlight.remove(case.id)
                }
            }
        } catch (e: Exception) {
            inFlight.remove(case.id)
            logger.warn(e) { "[KeepAlive] could not start the refresh of case ${case.id}" }
        }
    }

    private fun ping(
        case: Case,
        request: LastLlmRequest,
        state: Tracking,
        now: Instant,
    ) {
        try {
            pinger.ping(case, request)
            state.failures = 0
            registry.touch(request.caseId, request.capturedAt)
            logger.debug { "[KeepAlive] refreshed the prompt cache of case ${case.id}" }
        } catch (e: Exception) {
            state.failures++
            if (state.failures >= MAX_CONSECUTIVE_FAILURES) {
                logger.warn(e) { "[KeepAlive] refresh of case ${case.id} failed ${state.failures} times in a row: stopped until its next agent turn" }
            } else {
                logger.info { "[KeepAlive] refresh of case ${case.id} failed (${e.javaClass.simpleName}), retried at the next tick" }
            }
        }
    }

    private fun logSkipOnce(
        request: LastLlmRequest,
        state: Tracking,
        reason: () -> String,
    ) {
        if (state.skipLogged) return
        state.skipLogged = true
        logger.info { "[KeepAlive] case ${request.caseId} not refreshed: ${reason()}" }
    }

    @PreDestroy
    fun shutdown() {
        (executor as? ExecutorService)?.shutdownNow()
    }

    companion object : KLogging() {
        const val MAX_CONSECUTIVE_FAILURES = 2
        private const val PING_THREADS = 4

        /** 20 s before a 5-minute cache expires: a tick (20 s) cannot miss the window. */
        val FIVE_MINUTE_INTERVAL: Duration = Duration.ofMinutes(4).plusSeconds(40)

        /** 30 s before a 1-hour cache expires. */
        val ONE_HOUR_INTERVAL: Duration = Duration.ofMinutes(59).plusSeconds(30)

        /** How long after its last use the cache of a request sent with [ttl] is refreshed. */
        fun refreshInterval(ttl: PromptCacheTtl): Duration =
            when (ttl) {
                PromptCacheTtl.FIVE_MINUTES -> FIVE_MINUTE_INTERVAL
                PromptCacheTtl.ONE_HOUR -> ONE_HOUR_INTERVAL
            }

        private fun defaultExecutor(): ExecutorService =
            Executors.newFixedThreadPool(PING_THREADS) { runnable ->
                Thread(runnable, "keep-alive-ping").apply { isDaemon = true }
            }
    }
}
