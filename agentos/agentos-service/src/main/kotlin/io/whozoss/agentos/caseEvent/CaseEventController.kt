package io.whozoss.agentos.caseEvent

import io.whozoss.agentos.caseFlow.CaseServiceImpl
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import mu.KLogging
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.UUID

/**
 * REST API for reading case events and streaming them via SSE.
 *
 * Events are read-only — written exclusively by the agent runtime.
 */
@RestController
@RequestMapping("/api/cases/{caseId}/events")
class CaseEventController(
    private val caseService: CaseServiceImpl,
    private val caseEventService: CaseEventService,
) {
    /**
     * List all persisted events for a case (history).
     *
     * GET /api/cases/:caseId/events/history
     */
    @GetMapping("/history")
    fun listEvents(
        @PathVariable caseId: UUID,
    ): List<CaseEvent> {
        logger.debug { "Listing events for case: $caseId" }
        return caseEventService.findByParent(caseId)
    }

    /**
     * Stream live events for a case via SSE.
     *
     * GET /api/cases/:caseId/events/stream
     */
    @GetMapping("/stream")
    fun streamEvents(
        @PathVariable caseId: UUID,
    ): SseEmitter {
        logger.info { "Client connecting to event stream for case: $caseId" }
        caseService.getCaseInstance(caseId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Case not found: $caseId")
        logger.info { "SSE connection established for case: $caseId" }
        return caseService.createSseEmitter(caseId)
    }

    companion object : KLogging()
}
