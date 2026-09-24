package io.whozoss.agentos.caseEvent

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import io.whozoss.agentos.caseFlow.CaseConfigProperties
import io.whozoss.agentos.caseFlow.CaseService
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.security.declarative.HideOnAccessDenied
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import mu.KLogging
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SSE endpoint for streaming case events in real time.
 *
 * Tagged "sse" to be excluded from OpenAPI generation (ng-openapi-gen.json).
 * Clients should use the browser EventSource API, not a regular HTTP client.
 */
@Tag(name = "sse", description = "Server-Sent Events endpoints — use EventSource API, not HTTP client")
@RestController
@RequestMapping("/api/cases")
class CaseEventSseController(
    private val caseService: CaseService,
    private val caseEventService: CaseEventService,
    private val caseConfig: CaseConfigProperties,
) {
    private val heartbeatIntervalMs get() = caseConfig.sseHeartbeatIntervalMs

    /**
     * Stream events for a case via SSE.
     *
     * GET /api/cases/:caseId/events
     *
     * BREAKING PROTOCOL CHANGE: every domain event is sent on the single stable
     * `event: case-event` channel. Consumers must discriminate the JSON payload using
     * `data.type`; event-type-specific SSE names are no longer emitted.
     *
     * `includePreviousEvents` is non-null and defaults explicitly to true: a new or
     * reconnecting client receives durable history before buffered/live events.
     */
    @Operation(
        tags = ["sse"],
        summary = "Stream case events via SSE",
        description =
            "BREAKING: every SSE frame uses the stable event name 'case-event'. " +
                "Its JSON CaseEvent payload carries the subtype in its 'type' discriminant. " +
                "includePreviousEvents defaults to true.",
        responses = [
            ApiResponse(
                responseCode = "200",
                description =
                    "SSE stream — every event is named 'case-event' and carries a " +
                        "JSON-serialized CaseEvent subtype with a 'type' discriminant.",
                content = [Content(mediaType = "text/event-stream")],
            ),
        ],
    )
    @GetMapping("/{caseId}/events", produces = ["text/event-stream"])
    @PreAuthorize("hasPermission(#caseId, 'Case', 'READ')")
    @HideOnAccessDenied
    fun streamEvents(
        @PathVariable caseId: UUID,
        @RequestParam(defaultValue = "true") includePreviousEvents: Boolean = true,
    ): SseEmitter {
        logger.info { "Client connecting to event stream for case: $caseId" }

        val emitter = SseEmitter(0L)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        startCaseJob(
            scope = scope,
            includePreviousEvents = includePreviousEvents,
            caseId = caseId,
            emitter = emitter,
        )

        startHeartbeatJob(scope = scope, emitter = emitter, caseId = caseId)

        emitter.onCompletion {
            logger.debug { "SSE emitter completed for case $caseId" }
            scope.cancel()
        }
        emitter.onTimeout {
            logger.debug { "SSE emitter timed out for case $caseId" }
            scope.cancel()
        }
        emitter.onError { throwable ->
            logger.debug { "SSE emitter error for case $caseId: ${throwable.message}" }
            scope.cancel()
        }

        logger.info { "SSE connection established for case: $caseId" }
        return emitter
    }

    /**
     * Establishes the live subscription before reading persistence. Live events are put in
     * a bounded per-connection queue while history is replayed, then sent in FIFO order.
     * A durable event which is visible both in history and in the live queue is emitted once,
     * keyed by its stable CaseEvent id.
     */
    private fun startCaseJob(
        scope: CoroutineScope,
        includePreviousEvents: Boolean,
        caseId: UUID,
        emitter: SseEmitter,
    ) {
        val activeCase = caseService.findActiveRuntime(caseId)
        val liveEvents = Channel<CaseEvent>(LIVE_BUFFER_CAPACITY)
        val invalidated = AtomicBoolean(false)

        fun invalidateForSaturation(cause: Throwable) {
            if (!invalidated.compareAndSet(false, true)) return
            logger.warn(cause) { "SSE live buffer saturated for case $caseId; closing connection for durable replay" }
            emitter.completeWithError(cause)
            scope.cancel()
        }

        // UNDISTPATCHED subscribes to the hot SharedFlow before the repository replay starts.
        // trySend is deliberately non-blocking: this collector must never feed back pressure
        // into CaseRuntime. A full queue is an explicit reconnect/replay signal, never a drop.
        activeCase?.let { runtime ->
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                runtime.events.collect { event ->
                    if (liveEvents.trySend(event).isFailure) {
                        invalidateForSaturation(SseConnectionSaturatedException(caseId))
                    }
                }
            }
            // The runtime never blocks: a rejected tryEmit means its own live buffer
            // cannot preserve the stream. Close this connection so EventSource reconnects
            // and durable history is replayed rather than continuing with a gap.
            scope.launch {
                runtime.deliveryFailureCount.drop(1).first()
                invalidateForSaturation(SseConnectionSaturatedException(caseId))
            }
        }

        scope.launch {
            try {
                val emittedEventIds = mutableSetOf<UUID>()

                if (includePreviousEvents) {
                    caseEventService.findByParent(caseId).forEach { event ->
                        sendIfNew(event, emittedEventIds, emitter)
                    }
                }

                if (activeCase == null) {
                    emitter.complete()
                    return@launch
                }

                // First drain all live events accumulated while persistence was replayed.
                // Thereafter receive continuously from the same FIFO queue.
                while (true) {
                    val buffered = liveEvents.tryReceive().getOrNull() ?: break
                    sendIfNew(buffered, emittedEventIds, emitter)
                }
                for (event in liveEvents) {
                    sendIfNew(event, emittedEventIds, emitter)
                    logger.trace { "Event ${event.type} sent to SSE for case $caseId" }
                }
            } catch (e: CancellationException) {
                logger.debug { "SSE collector cancelled for case $caseId" }
                throw e
            } catch (error: Exception) {
                logger.error("Error in event stream for case $caseId", error)
                emitter.completeWithError(error)
            }
        }
    }

    private fun sendIfNew(event: CaseEvent, emittedEventIds: MutableSet<UUID>, emitter: SseEmitter) {
        if (emittedEventIds.add(event.id)) sendEvent(event, emitter)
    }

    private fun sendEvent(event: CaseEvent, emitter: SseEmitter) =
        emitter.send(
            SseEmitter.event().id(event.id.toString()).name(CASE_EVENT_CHANNEL).data(event),
        )

    private fun startHeartbeatJob(scope: CoroutineScope, emitter: SseEmitter, caseId: UUID): Job =
        scope.launch {
            while (isActive) {
                delay(heartbeatIntervalMs)
                try {
                    emitter.send(SseEmitter.event().comment("keep-alive"))
                } catch (e: Exception) {
                    logger.debug { "Heartbeat write failed for case $caseId — client likely disconnected" }
                    scope.cancel()
                }
            }
        }

    private class SseConnectionSaturatedException(caseId: UUID) :
        IllegalStateException("SSE live buffer saturated for case $caseId; reconnect to replay durable events")

    companion object : KLogging() {
        const val CASE_EVENT_CHANNEL = "case-event"
        private const val LIVE_BUFFER_CAPACITY = 100
    }
}
