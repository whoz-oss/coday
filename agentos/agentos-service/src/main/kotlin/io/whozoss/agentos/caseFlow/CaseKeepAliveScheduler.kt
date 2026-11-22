package io.whozoss.agentos.caseFlow

import io.whozoss.agentos.agent.LastLlmRequest
import io.whozoss.agentos.agent.LastLlmRequestRegistry
import io.whozoss.agentos.aiModel.AiModelService
import io.whozoss.agentos.chat.ChatModelFactory
import io.whozoss.agentos.chat.PromptCacheLifetime
import io.whozoss.agentos.chat.sentTo
import io.whozoss.agentos.config.UsageConfigProperties
import io.whozoss.agentos.sdk.aiProvider.ReasoningEffort
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.usage.LlmUsage
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
 * Every tick (`agentos.keep-alive.tick-interval-ms`, 20 s by default) it looks at the agent's last request
 * ([LastLlmRequestRegistry]) of the cases with keep-alive on ([CaseKeepAliveIds]: nothing is read while none is) and
 * has [CaseKeepAlivePinger] send it again when its cache is about to expire: the cache lifetime of its model
 * ([PromptCacheLifetime], read at every check) less [REFRESH_MARGIN] after the cache last saw the prefix
 * ([LastLlmRequest.lastUsedAt]: when the request, or the last ping, was sent). A cache read refreshes the cache
 * lifetime, so the next real message of the user still finds the conversation cached. A successful ping
 * [LastLlmRequestRegistry.touch]es the request, which keeps it in the registry while the keep-alive runs.
 *
 * A case is not pinged when:
 * - usage tracking is off (a ping must be accounted for);
 * - its keep-alive is off, it was removed, or it has no request in the registry (no agent turn since the start of
 *   the process, an ADVANCED agent, expired);
 * - it is not [CaseStatus.IDLE] (a running turn refreshes the cache by itself), or the cost guard refuses the ping
 *   (the case or an ancestor ended, was stopped, waits for a confirmation or is at its threshold): checked again at
 *   the next interval only;
 * - more than [CaseKeepAliveProperties.maxIdle] has passed since the agent's last real request was captured;
 * - its model has no cache TTL for it ([PromptCacheLifetime]), or one longer than the registry keeps the request
 *   ([LastLlmRequestRegistry.TTL]): logged once, and checked again at every tick (a TTL set later applies);
 * - the request was sent with a reasoning effort (a 1-token answer is invalid with thinking), its provider is not
 *   OpenAI-compatible ([CaseKeepAlivePinger.supports]), or its model or provider changed since: logged once, and left
 *   alone until the next agent turn;
 * - its last [MAX_CONSECUTIVE_FAILURES] pings failed (an error, or no cache read on a prompt long enough to be
 *   cached): left alone until the next agent turn.
 *
 * Every condition is checked again in the worker, right before the ping is sent, on a fresh read of the case and of
 * the registry (a run may have started meanwhile). Pings run on a small pool of their own, so that a slow provider
 * holds neither the scheduling thread nor the next ticks; a case is claimed while its ping is in flight.
 *
 * **Single instance**: the registry, the keep-alive ids and the state of the scheduler are in memory. With several
 * instances, each one only refreshes the cases whose agent turns it ran; nothing coordinates them (enough as long as
 * a case runs on one instance, which is the deployment AgentOS supports today).
 */
@Component
class CaseKeepAliveScheduler(
    private val registry: LastLlmRequestRegistry,
    private val caseService: CaseService,
    private val pinger: CaseKeepAlivePinger,
    private val keepAliveCaseIds: CaseKeepAliveIds,
    private val chatModelFactory: ChatModelFactory,
    private val aiModelService: AiModelService,
    private val properties: CaseKeepAliveProperties,
    private val clock: Clock,
    private val usageConfig: UsageConfigProperties,
) {
    /** Where the pings run. A seam for the tests, which run them in place. */
    internal var executor: Executor = defaultExecutor()

    /** What the scheduler knows of the request of a case, reset when a new turn replaces the request. */
    private class Tracking(
        val capturedAt: Instant,
    ) {
        @Volatile var failures = 0

        /** Left alone until the next agent turn. */
        @Volatile var stopped = false

        /** Not checked again before then (not idle, refused by the cost guard). */
        @Volatile var deferredUntil: Instant? = null

        @Volatile var costRefusalLogged = false

        /** Why it has no refresh interval was logged, until it has one again. */
        @Volatile var noIntervalLogged = false
    }

    /** A request due now, with the interval it is due after. */
    private data class Due(
        val request: LastLlmRequest,
        val state: Tracking,
        val refreshInterval: Duration,
    )

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

    /** Starts the pings due now; returns the ids of the cases whose ping was started. */
    internal fun refreshDue(): List<UUID> {
        if (!usageConfig.enabled) return emptyList()
        val ids = keepAliveCaseIds.current()
        tracking.keys.retainAll(ids)
        if (ids.isEmpty()) return emptyList()
        val now = clock.instant()
        val due =
            ids.mapNotNull { id ->
                val request = registry.get(id) ?: return@mapNotNull null
                val state = trackingOf(request)
                dueInterval(request, state, now)?.let { Due(request, state, it) }
            }
        if (due.isEmpty()) return emptyList()
        val cases = caseService.findByIds(due.map { it.request.caseId }, withRemoved = false).associateBy { it.id }
        return due.mapNotNull { due ->
            val caseId = due.request.caseId
            if (!eligible(cases[caseId], due, now) || !inFlight.add(caseId)) {
                null
            } else {
                start(due)
                caseId
            }
        }
    }

    /** The refresh interval of [request] when it is due now, null otherwise. */
    private fun dueInterval(
        request: LastLlmRequest,
        state: Tracking,
        now: Instant,
    ): Duration? {
        val waiting =
            state.stopped ||
                state.failures >= MAX_CONSECUTIVE_FAILURES ||
                state.deferredUntil?.let(now::isBefore) == true ||
                request.caseId in inFlight ||
                Duration.between(request.capturedAt, now) > properties.maxIdle
        if (waiting) return null
        return refreshIntervalOf(request, state)?.takeUnless { now.isBefore(request.lastUsedAt.plus(it)) }
    }

    private fun trackingOf(request: LastLlmRequest): Tracking {
        val current = tracking[request.caseId]
        if (current != null && current.capturedAt == request.capturedAt) return current
        return Tracking(request.capturedAt).also { tracking[request.caseId] = it }
    }

    /** Whether [due] may be pinged for [case] now; records why not when that lasts. */
    private fun eligible(
        case: Case?,
        due: Due,
        now: Instant,
    ): Boolean {
        val (request, state) = due
        if (case == null || !case.keepAlive) return false
        if (case.status != CaseStatus.IDLE) {
            state.deferredUntil = now.plus(due.refreshInterval)
            return false
        }
        // A run started, or the case was killed or deleted, since the request was read.
        val current = registry.get(request.caseId)
        if (registry.generation(request.caseId) != request.generation || current?.capturedAt != request.capturedAt) return false
        val options = request.requestOptions
        val effort = options.reasoningEffort?.sentTo(options.apiType)
        if (effort != null && effort != ReasoningEffort.NONE) {
            return stop(request, state) { "model ${options.apiModelName} runs with reasoning effort $effort: a 1-token answer is invalid" }
        }
        if (!CaseKeepAlivePinger.supports(options.apiType)) {
            return stop(request, state) { "provider API type ${options.apiType} is not supported" }
        }
        return true
    }

    private fun start(due: Due) {
        val caseId = due.request.caseId
        try {
            executor.execute {
                try {
                    ping(due)
                } finally {
                    inFlight.remove(caseId)
                }
            }
        } catch (e: Exception) {
            inFlight.remove(caseId)
            logger.warn(e) { "[KeepAlive] could not start the refresh of case $caseId" }
        }
    }

    /** In the worker: everything is checked again on fresh reads, then the ping is sent. */
    private fun ping(due: Due) {
        val (request, state) = due
        try {
            val now = clock.instant()
            val case = caseService.findById(request.caseId, withRemoved = false)
            if (!eligible(case, due, now) || case == null) return
            when (val result = pinger.ping(case, request)) {
                is CaseKeepAlivePinger.Result.Sent -> {
                    if (isCacheMiss(result.usage)) {
                        failed(request, state, "no cache read on a prompt of ${result.usage.promptTokens()} tokens", null)
                    } else {
                        state.failures = 0
                        registry.touch(request.caseId, request.capturedAt, now)
                        logger.debug { "[KeepAlive] refreshed the prompt cache of case ${request.caseId}" }
                    }
                }

                is CaseKeepAlivePinger.Result.CostRefused -> {
                    state.deferredUntil = now.plus(due.refreshInterval)
                    if (!state.costRefusalLogged) {
                        state.costRefusalLogged = true
                        logger.info { "[KeepAlive] case ${request.caseId} not refreshed: run cost guard (${result.reason})" }
                    }
                }

                CaseKeepAlivePinger.Result.OptionsChanged -> {
                    stop(request, state) { "its model or provider changed since the agent's request" }
                }
            }
        } catch (e: Exception) {
            failed(request, state, "${e.javaClass.simpleName}: ${e.message}", e)
        }
    }

    private fun failed(
        request: LastLlmRequest,
        state: Tracking,
        why: String,
        error: Exception?,
    ) {
        state.failures++
        if (state.failures >= MAX_CONSECUTIVE_FAILURES) {
            logger.warn(error) {
                "[KeepAlive] refresh of case ${request.caseId} failed ${state.failures} times in a row ($why): stopped until its next agent turn"
            }
        } else {
            logger.warn { "[KeepAlive] refresh of case ${request.caseId} failed ($why), retried at the next tick" }
        }
    }

    private fun stop(
        request: LastLlmRequest,
        state: Tracking,
        reason: () -> String,
    ): Boolean {
        if (!state.stopped) {
            state.stopped = true
            logger.info { "[KeepAlive] case ${request.caseId} not refreshed until its next agent turn: ${reason()}" }
        }
        return false
    }

    /**
     * The cache lifetime [request] was actually sent with, less [REFRESH_MARGIN], read again at every check so that a
     * TTL set on the model since applies. Null, logged once, without a TTL, or with one longer than the registry keeps
     * the request ([LastLlmRequestRegistry.TTL]).
     */
    private fun refreshIntervalOf(
        request: LastLlmRequest,
        state: Tracking,
    ): Duration? {
        val options = request.requestOptions
        val oneHour = chatModelFactory.appliesOneHourCache(options.apiType, options.baseUrl, options.apiModelName, request.promptCacheTtl)
        val interval = PromptCacheLifetime.of(aiModelService.findById(request.modelId), oneHour)?.minus(REFRESH_MARGIN)
        val why =
            when {
                interval == null -> "model ${options.apiModelName} has no cache TTL for it"
                interval >= LastLlmRequestRegistry.TTL ->
                    "its cache TTL exceeds the ${LastLlmRequestRegistry.TTL.toMinutes()} minutes a request is kept"
                else -> {
                    state.noIntervalLogged = false
                    return interval
                }
            }
        if (!state.noIntervalLogged) {
            state.noIntervalLogged = true
            logger.info { "[KeepAlive] case ${request.caseId} not refreshed: $why" }
        }
        return null
    }

    @PreDestroy
    fun shutdown() {
        (executor as? ExecutorService)?.shutdownNow()
    }

    companion object : KLogging() {
        const val MAX_CONSECUTIVE_FAILURES = 2

        /** Below this many prompt tokens a provider may not cache at all (Anthropic's minimum is 1024). */
        const val MIN_CACHEABLE_PROMPT_TOKENS = 1024L
        private const val PING_THREADS = 4

        /**
         * A ping is due this long before the cache expires. A due ping waits for the next tick (up to 20 s), then for
         * a worker and the provider's answer (a few seconds): the margin covers both.
         */
        val REFRESH_MARGIN: Duration = Duration.ofSeconds(40)

        /** A prompt long enough to be cached that read nothing from the cache: the ping refreshed nothing. */
        internal fun isCacheMiss(usage: LlmUsage): Boolean = usage.cacheReadTokens == 0L && usage.promptTokens() >= MIN_CACHEABLE_PROMPT_TOKENS

        private fun LlmUsage.promptTokens(): Long = inputTokens + cacheReadTokens + cacheWriteTokens

        private fun defaultExecutor(): ExecutorService =
            Executors.newFixedThreadPool(PING_THREADS) { runnable ->
                Thread(runnable, "keep-alive-ping").apply { isDaemon = true }
            }
    }
}
