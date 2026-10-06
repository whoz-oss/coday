package io.whozoss.factory.adapter.agentos

import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Robust SSE observer of a single AgentOS case.
 *
 * Implements the observation protocol frozen in `app_docs/agentos-sse-contract.md`:
 * - Opens `GET /api/cases/{caseId}/events?includePreviousEvents=true` with the
 *   trusted `X-External-User-Id` header (and the `X-External-Context-*` capability
 *   headers when supplied), streaming over the JDK [HttpClient] (servlet
 *   stack — no WebFlux).
 * - Every (re)connection replays the full durable history (the server honours
 *   no cursor), so deduplication is client-side by `eventId` through an
 *   [EventCheckpoint] `(timestamp, lastEventId)` high-water mark.
 * - Strict caseId filtering: any frame whose decoded `caseId` differs from
 *   the observed case is ignored (defensive — the endpoint is per-case but
 *   the adapter does not trust it).
 * - Transient events (`ThinkingEvent`, `TextChunkEvent`, `CaseUpdatedEvent`)
 *   are display-only: dropped before any verdict/checkpoint logic.
 * - Reconnection uses bounded exponential backoff; on **every** reconnection
 *   a REST catch-up (`reconcile` callback) runs first so no durable event is
 *   missed while the stream was down.
 * - Heartbeat-stall detection: a stream that delivers no frame and no
 *   `:keep-alive` within [stallTimeoutMs] is treated as dropped.
 * - A verdict is **never** derived from silence: when the reconnection budget
 *   is exhausted the result is
 *   `Indeterminate("SSE reconnection budget exhausted")`, and when the
 *   active-execution budget elapses it is
 *   `Indeterminate("SSE observation timeout")`. Waiting for a human pauses
 *   that budget and uses the separate bounded [humanWaitTimeoutMs].
 */
class AgentOsSseClient(
    private val baseUrl: String,
    private val httpClient: HttpClient = HttpClient.newBuilder().build(),
    private val objectMapper: ObjectMapper = ObjectMapper(),
    private val backoffBaseMs: Long = DEFAULT_BACKOFF_BASE_MS,
    private val backoffMaxMs: Long = DEFAULT_BACKOFF_MAX_MS,
    private val maxReconnects: Int = DEFAULT_MAX_RECONNECTS,
    private val stallTimeoutMs: Long = DEFAULT_STALL_TIMEOUT_MS,
    private val humanWaitTimeoutMs: Long = DEFAULT_HUMAN_WAIT_TIMEOUT_MS,
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {

    /** Result of a REST catch-up: the durable events seen, plus the verdict they derive (null = not terminal). */
    data class ReconcileResult(
        val events: List<CaseEventView>,
        val verdict: AgentOsExecutionVerdict?,
    )

    /**
     * Observe [caseId] until a verdict is derivable or a budget elapses.
     *
     * @param timeoutMs active-execution budget; paused while a human answer is pending.
     * @param checkpoint per-`(caseId, attemptId)` dedup/high-water state; a
     *   fresh instance means "full replay + eventId dedup".
     * @param reconcile REST catch-up invoked on every reconnection (before
     *   re-opening the stream). Default: no reconciler.
     * @param onEvent sink invoked exactly once per processed (non-duplicate,
     *   non-transient, case-matching) event — useful for tests and auditing.
     */
    fun observe(
        caseId: String,
        timeoutMs: Long,
        externalUserId: String? = null,
        attemptId: String? = null,
        capabilityToken: String? = null,
        checkpoint: EventCheckpoint = EventCheckpoint(),
        context: VerdictDeriver.DerivationContext = VerdictDeriver.DerivationContext(caseId),
        reconcile: (String) -> ReconcileResult? = { null },
        onEvent: (CaseEventView) -> Unit = {},
        onIntermediateVerdict: (AgentOsExecutionVerdict.WaitingHuman) -> Unit = {},
        onAnswerObserved: (CaseEventView) -> Unit = {},
    ): AgentOsExecutionVerdict {
        var activeBudgetMs = timeoutMs
        var deadline = now() + activeBudgetMs
        var phase: HumanWaitPhase = HumanWaitPhase.Active
        fun handleIntermediate(verdict: AgentOsExecutionVerdict.WaitingHuman) {
            val current = phase
            if (current is HumanWaitPhase.WaitingHuman && current.questionRef == verdict.questionRef) return
            val observedAt = now()
            if (current is HumanWaitPhase.Active) {
                activeBudgetMs = (deadline - observedAt).coerceAtLeast(0)
            }
            phase = HumanWaitPhase.WaitingHuman(verdict.questionRef, observedAt + humanWaitTimeoutMs)
            onIntermediateVerdict(verdict)
        }
        fun applyLifecycleEvent(event: CaseEventView) {
            when (val current = phase) {
                is HumanWaitPhase.WaitingHuman -> if (event.answeredQuestionId == current.questionRef) {
                    phase = HumanWaitPhase.WaitingForResume(current.questionRef)
                    deadline = now() + activeBudgetMs
                }
                is HumanWaitPhase.WaitingForResume -> {
                    val resumedBySelection = event.selectedQuestionId == current.questionRef
                    val resumedByStatus = event.type == CaseEventView.CASE_STATUS_EVENT && event.status == "RUNNING"
                    if (resumedBySelection || resumedByStatus) phase = HumanWaitPhase.Active
                }
                HumanWaitPhase.Active -> Unit
            }
        }
        fun currentDeadline(): Long = when (val current = phase) {
            is HumanWaitPhase.WaitingHuman -> current.deadline
            else -> deadline
        }
        fun timeoutVerdict(): AgentOsExecutionVerdict = when (val current = phase) {
            is HumanWaitPhase.WaitingHuman -> humanWaitTimeout(caseId, current.questionRef)
            else -> observationTimeout(caseId)
        }
        // Seed from the warm checkpoint (fresh start ⇒ empty ⇒ full replay + dedup).
        val observed = checkpoint.events().toMutableList()
        fun evaluateVerdict(): AgentOsExecutionVerdict? {
            val verdict = VerdictDeriver.derive(observed, context) ?: return null
            return when (verdict) {
                is AgentOsExecutionVerdict.WaitingHuman -> {
                    handleIntermediate(verdict)
                    null
                }
                else -> if (phase is HumanWaitPhase.WaitingForResume) null else verdict
            }
        }
        observed.forEach(::applyLifecycleEvent)
        var reconnects = 0
        var connectedOnce = false
        while (true) {
            if (connectedOnce) {
                // Every reconnection starts with a REST catch-up so no durable
                // event is missed while the stream was down.
                val catchUp = try {
                    reconcile(caseId)
                } catch (_: Exception) {
                    null
                }
                if (catchUp != null) {
                    for (event in catchUp.events) {
                        if (event.caseId != caseId || event.isTransient()) continue
                        if (checkpoint.record(event)) {
                            observed.add(event)
                            onEvent(event)
                            applyLifecycleEvent(event)
                            if (event.type == CaseEventView.ANSWER_EVENT) onAnswerObserved(event)
                        }
                    }
                    evaluateVerdict()?.let { return it }
                }
                if (now() >= currentDeadline()) return timeoutVerdict()
                if (reconnects >= maxReconnects) {
                    if (phase is HumanWaitPhase.WaitingHuman) {
                        reconnects = 0
                    } else {
                        return AgentOsExecutionVerdict.Indeterminate(
                            VerdictDeriver.RECONNECT_BUDGET_EXHAUSTED,
                            mapOf("caseId" to caseId, "reconnects" to reconnects),
                        )
                    }
                }
                val delay = backoffDelay(reconnects)
                reconnects++
                if (now() + delay >= currentDeadline()) return timeoutVerdict()
                try {
                    sleep(delay)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return interruptedObservation(caseId)
                }
            }
            when (
                val outcome = streamOnce(
                    caseId, externalUserId, attemptId, capabilityToken, checkpoint,
                    ::currentDeadline, observed, onEvent, onAnswerObserved, ::applyLifecycleEvent, ::evaluateVerdict,
                )
            ) {
                is StreamOutcome.Verdict -> return outcome.verdict
                StreamOutcome.Timeout -> return timeoutVerdict()
                StreamOutcome.Interrupted -> return interruptedObservation(caseId)
                StreamOutcome.Dropped -> connectedOnce = true
            }
        }
    }

    private fun observationTimeout(caseId: String): AgentOsExecutionVerdict =
        AgentOsExecutionVerdict.Indeterminate(
            VerdictDeriver.OBSERVATION_TIMEOUT,
            mapOf("caseId" to caseId),
        )

    private fun humanWaitTimeout(caseId: String, questionRef: String?): AgentOsExecutionVerdict =
        AgentOsExecutionVerdict.Indeterminate(
            VerdictDeriver.HUMAN_WAIT_TIMEOUT,
            mapOf("caseId" to caseId, "questionRef" to questionRef, "humanWaitTimeoutMs" to humanWaitTimeoutMs),
        )

    private fun interruptedObservation(caseId: String): AgentOsExecutionVerdict =
        AgentOsExecutionVerdict.Interrupted(
            "SSE observation interrupted",
            mapOf("caseId" to caseId),
        )

    private sealed interface HumanWaitPhase {
        data object Active : HumanWaitPhase
        data class WaitingHuman(val questionRef: String, val deadline: Long) : HumanWaitPhase
        data class WaitingForResume(val questionRef: String) : HumanWaitPhase
    }

    private sealed interface StreamOutcome {
        data class Verdict(val verdict: AgentOsExecutionVerdict) : StreamOutcome
        data object Dropped : StreamOutcome
        data object Timeout : StreamOutcome
        data object Interrupted : StreamOutcome
    }

    private sealed interface Signal {
        data class Line(val value: String) : Signal
        data object End : Signal
    }

    private fun streamOnce(
        caseId: String,
        externalUserId: String?,
        attemptId: String?,
        capabilityToken: String?,
        checkpoint: EventCheckpoint,
        deadline: () -> Long,
        observed: MutableList<CaseEventView>,
        onEvent: (CaseEventView) -> Unit,
        onAnswerObserved: (CaseEventView) -> Unit,
        onLifecycleEvent: (CaseEventView) -> Unit,
        evaluateVerdict: () -> AgentOsExecutionVerdict?,
    ): StreamOutcome {
        val requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create("${baseUrl.trimEnd('/')}/api/cases/$caseId/events?includePreviousEvents=true"))
            .header("Accept", "text/event-stream")
            .GET()
        if (!externalUserId.isNullOrBlank()) requestBuilder.header("X-External-User-Id", externalUserId)
        if (!attemptId.isNullOrBlank()) requestBuilder.header("X-External-Context-Attempt-Id", attemptId)
        if (!capabilityToken.isNullOrBlank()) requestBuilder.header("X-External-Context-Capability-Token", capabilityToken)
        val response = try {
            httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream())
        } catch (_: Exception) {
            return StreamOutcome.Dropped
        }
        if (response.statusCode() !in 200..299) {
            try {
                response.body().close()
            } catch (_: Exception) {
                // ignored — the connection is dead either way
            }
            return StreamOutcome.Dropped
        }
        val body = response.body()
        val signals = ArrayBlockingQueue<Signal>(SIGNAL_QUEUE_CAPACITY)
        val reader = thread(start = true, isDaemon = true, name = "agentos-sse-$caseId") {
            try {
                body.bufferedReader(Charsets.UTF_8).use { buffered ->
                    while (true) {
                        val line = buffered.readLine() ?: break
                        try {
                            signals.put(Signal.Line(line))
                        } catch (_: InterruptedException) {
                            return@thread
                        }
                    }
                }
            } catch (_: Exception) {
                // broken stream: fall through to the End signal
            } finally {
                signals.offer(Signal.End)
            }
        }
        try {
            val parser = SseFrameParser()
            while (true) {
                val remaining = deadline() - now()
                if (remaining <= 0) return StreamOutcome.Timeout
                val signal = try {
                    signals.poll(minOf(stallTimeoutMs, remaining), TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return StreamOutcome.Interrupted
                } ?: return if (now() >= deadline()) StreamOutcome.Timeout else StreamOutcome.Dropped
                when (signal) {
                    Signal.End -> {
                        // Flush any unterminated trailing frame before treating
                        // the close as a dropped connection.
                        val frame = parser.finish()
                        if (frame != null) {
                            handleFrame(
                                frame, caseId, checkpoint, observed, onEvent, onAnswerObserved, onLifecycleEvent, evaluateVerdict,
                            )?.let { return it }
                        }
                        return StreamOutcome.Dropped
                    }
                    is Signal.Line -> {
                        val frame = parser.feedLine(signal.value) ?: continue
                        handleFrame(
                            frame, caseId, checkpoint, observed, onEvent, onAnswerObserved, onLifecycleEvent, evaluateVerdict,
                        )?.let { return it }
                    }
                }
            }
        } finally {
            reader.interrupt()
            try {
                body.close()
            } catch (_: Exception) {
                // ignored — best-effort cleanup
            }
        }
    }

    /**
     * Apply the boundary rules to one dispatched frame. Returns a terminal
     * [StreamOutcome.Verdict] when the case is now decidable, null otherwise.
     */
    private fun handleFrame(
        frame: SseFrame,
        caseId: String,
        checkpoint: EventCheckpoint,
        observed: MutableList<CaseEventView>,
        onEvent: (CaseEventView) -> Unit,
        onAnswerObserved: (CaseEventView) -> Unit,
        onLifecycleEvent: (CaseEventView) -> Unit,
        evaluateVerdict: () -> AgentOsExecutionVerdict?,
    ): StreamOutcome.Verdict? {
        if (frame.isHeartbeat) return null // :keep-alive — liveness only
        val view = decode(frame) ?: return null
        if (view.caseId != caseId) return null // strict caseId filter
        if (view.isTransient()) return null // display-only
        if (!checkpoint.record(view)) return null // duplicate eventId
        observed.add(view)
        onEvent(view)
        onLifecycleEvent(view)
        if (view.type == CaseEventView.ANSWER_EVENT) onAnswerObserved(view)
        return evaluateVerdict()?.let(StreamOutcome::Verdict)
    }

    private fun decode(frame: SseFrame): CaseEventView? {
        if (frame.data.isBlank()) return null
        if (frame.event != null && frame.event != CASE_EVENT_CHANNEL) return null
        val map = try {
            @Suppress("UNCHECKED_CAST")
            objectMapper.readValue(frame.data, Map::class.java) as Map<String, Any?>
        } catch (_: Exception) {
            return null
        }
        return CaseEventView.fromJson(map)
    }

    /** Bounded exponential backoff (attempt 0 → [backoffBaseMs], capped at [backoffMaxMs]). */
    private fun backoffDelay(attempt: Int): Long {
        val shift = attempt.coerceIn(0, 20)
        val exponential = backoffBaseMs * (1L shl shift)
        return minOf(backoffMaxMs, exponential)
    }

    companion object {
        const val CASE_EVENT_CHANNEL = "case-event"
        const val DEFAULT_BACKOFF_BASE_MS = 500L
        const val DEFAULT_BACKOFF_MAX_MS = 30_000L
        const val DEFAULT_MAX_RECONNECTS = 5
        const val DEFAULT_STALL_TIMEOUT_MS = 60_000L
        const val DEFAULT_HUMAN_WAIT_TIMEOUT_MS = 86_400_000L
        private const val SIGNAL_QUEUE_CAPACITY = 1_024
    }
}
