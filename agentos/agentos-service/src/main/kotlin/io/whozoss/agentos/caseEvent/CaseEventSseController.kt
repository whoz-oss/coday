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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mu.KLogging
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.UUID

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
     * Each SSE event carries:
     * - id: the CaseEvent UUID
     * - name: the CaseEventType value (e.g. "MessageEvent", "ThinkingEvent")
     * - data: the JSON-serialized CaseEvent subtype (polymorphic via @JsonTypeInfo)
     */
    @Operation(
        tags = ["sse"],
        summary = "Stream case events via SSE",
        description =
            "Server-Sent Events stream emitting all events generated during case execution. " +
                "Use the browser EventSource API to consume this endpoint, not a regular HTTP client.",
        responses = [
            ApiResponse(
                responseCode = "200",
                description =
                    "SSE stream — each event is a JSON-serialized CaseEvent subtype " +
                        "(MessageEvent, ToolRequestEvent, etc.) with a \"type\" discriminant field.",
                content = [Content(mediaType = "text/event-stream")],
            ),
        ],
    )
    @GetMapping("/{caseId}/events", produces = ["text/event-stream"])
    @PreAuthorize("hasPermission(#caseId, 'Case', 'READ')")
    @HideOnAccessDenied
    fun streamEvents(
        @PathVariable caseId: UUID,
        @RequestParam includePreviousEvents: Boolean? = true,
    ): SseEmitter {
        logger.info { "Client connecting to event stream for case: $caseId" }

        val emitter = SseEmitter(0L) // Infinite timeout

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        startCaseJob(
            scope = scope,
            includePreviousEvents = includePreviousEvents,
            caseId = caseId,
            emitter = emitter,
        )

        // Heartbeat: periodically send an SSE comment frame so that a client
        // disconnect is detected even when the case is IDLE and emits no events.
        // Without this, the collector coroutine above stays suspended on collect()
        // indefinitely, keeping the SharedFlow subscriber alive and blocking eviction.
        startHeartbeatJob(
            scope = scope,
            emitter = emitter,
            caseId = caseId,
        )

        emitter.onCompletion {
            logger.debug { "SSE emitter completed for case $caseId" }
            scope.cancel() // cancels all child jobs (collectorJob, heartbeatJob)
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

    private fun startCaseJob(
        scope: CoroutineScope,
        includePreviousEvents: Boolean?,
        caseId: UUID,
        emitter: SseEmitter,
    ) {
        scope.launch {
            try {
                if (includePreviousEvents == true) {
                    // Replay persisted history first so clients connecting mid-run
                    // or reconnecting after a disconnect receive the full sequence.
                    caseEventService.findByParent(caseId).forEach { sendEvent(it, emitter) }
                }

                // If the case is still active, subscribe to the live flow.
                // findActiveRuntime never rehydrates — safe for observation only.
                // If the case is completed, the history replay above is sufficient
                // and the emitter completes at the end of the try block.
                val activeCase = caseService.findActiveRuntime(caseId)
                activeCase?.events?.collect { event ->
                    try {
                        sendEvent(event, emitter)
                        logger.trace { "Event ${event.type} sent to SSE for case $caseId" }
                    } catch (e: Exception) {
                        logger.debug { "Failed to send event to SSE for case $caseId: ${e.message}" }
                        throw e
                    }
                }
                emitter.complete()
            } catch (e: CancellationException) {
                // Normal path: collectorJob was cancelled because the client disconnected
                // (onError/onCompletion fired and called scope.cancel()). Not an error.
                logger.debug { "SSE collector cancelled for case $caseId (client disconnected)" }
                throw e // re-throw so cancellation propagates correctly through the coroutine hierarchy
            } catch (error: Exception) {
                logger.error("Error in event stream for case $caseId", error)
                emitter.completeWithError(error)
            }
        }
    }

    private fun sendEvent(
        event: CaseEvent,
        emitter: SseEmitter,
    ) = emitter.send(
        SseEmitter
            .event()
            .id(event.id.toString())
            .name(event.type.value)
            .data(event),
    )

    private fun startHeartbeatJob(
        scope: CoroutineScope,
        emitter: SseEmitter,
        caseId: UUID,
    ): Job =
        scope.launch {
            while (isActive) {
                delay(heartbeatIntervalMs)
                try {
                    // SSE comment — ignored by EventSource but forces a socket write.
                    emitter.send(
                        SseEmitter
                            .event()
                            .comment("keep-alive"),
                    )
                } catch (e: Exception) {
                    // The socket is gone. Cancel the scope explicitly here rather than
                    // relying solely on Tomcat's onError callback: if that callback never
                    // fires, collectorJob would stay subscribed indefinitely.
                    // scope.cancel() propagates to all child jobs so no explicit break is
                    // needed — this coroutine will receive CancellationException at the
                    // next delay() and exit the while loop naturally.
                    logger.debug { "Heartbeat write failed for case $caseId — client likely disconnected" }
                    scope.cancel()
                }
            }
        }

    companion object : KLogging()
}
