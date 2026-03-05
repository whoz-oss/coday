package io.whozoss.agentos.caseEvent

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import io.whozoss.agentos.caseFlow.CaseService
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import mu.KLogging
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
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
) {
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
    fun streamEvents(
        @PathVariable caseId: UUID,
    ): SseEmitter {
        logger.info { "Client connecting to event stream for case: $caseId" }

        val emitter = SseEmitter(0L) // Infinite timeout

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        fun sendEvent(event: CaseEvent) =
            emitter.send(
                SseEmitter
                    .event()
                    .id(event.id.toString())
                    .name(event.type.value)
                    .data(event),
            )

        val collectorJob =
            scope.launch {
                try {
                    // Replay persisted history first so clients connecting mid-run
                    // or reconnecting after a disconnect receive the full sequence.
                    caseEventService.findByParent(caseId).forEach { sendEvent(it) }

                    // If the case is still active, subscribe to the live flow.
                    // findActiveRuntime never rehydrates — safe for observation only.
                    // If the case is completed, the history replay above is sufficient
                    // and the emitter completes at the end of the try block.
                    val activeCase = caseService.findActiveRuntime(caseId)
                    activeCase?.events?.collect { event ->
                        try {
                            sendEvent(event)
                            logger.trace { "Event ${event.type} sent to SSE for case $caseId" }
                        } catch (e: Exception) {
                            logger.debug { "Failed to send event to SSE for case $caseId: ${e.message}" }
                            throw e
                        }
                    }
                    emitter.complete()
                } catch (error: Exception) {
                    logger.error("Error in event stream for case $caseId", error)
                    emitter.completeWithError(error)
                }
            }

        emitter.onCompletion {
            logger.debug { "SSE emitter completed for case $caseId" }
            collectorJob.cancel()
            scope.cancel()
        }

        emitter.onTimeout {
            logger.debug { "SSE emitter timed out for case $caseId" }
            collectorJob.cancel()
            scope.cancel()
        }

        emitter.onError { throwable ->
            logger.warn { "SSE emitter error for case $caseId: ${throwable.message}" }
            collectorJob.cancel()
            scope.cancel()
        }

        logger.info { "SSE connection established for case: $caseId" }
        return emitter
    }

    companion object : KLogging()
}
