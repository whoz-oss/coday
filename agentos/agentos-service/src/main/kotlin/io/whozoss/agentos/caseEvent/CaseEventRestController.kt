@file:Suppress("ktlint:standard:filename")

package io.whozoss.agentos.caseEvent

import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.http.HttpStatus
import java.util.UUID

/**
 * Read-only REST API for [CaseEvent] entities.
 *
 * Case events are an immutable audit log produced exclusively by the runtime.
 * All mutations go through [io.whozoss.agentos.caseFlow.CaseService].
 * The SSE streaming endpoint lives in [CaseEventSseController].
 */
@RestController
@RequestMapping("/api/case-events", produces = [MediaType.APPLICATION_JSON_VALUE])
class CaseEventRestController(
    private val caseEventService: CaseEventService,
) {
    /** GET /api/case-events/{id} — get a single event by ID. */
    @GetMapping("/{id}")
    fun getById(
        @PathVariable id: UUID,
    ): CaseEvent =
        caseEventService.findById(id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "CaseEvent not found: $id")

    /** POST /api/case-events/by-ids — get multiple events by IDs. */
    @PostMapping("/by-ids", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun getByIds(
        @RequestBody ids: List<UUID>,
    ): List<CaseEvent> = caseEventService.findByIds(ids)

    /** GET /api/case-events/by-parentId/{caseId} — list all events for a case, ordered by timestamp. */
    @GetMapping("/by-parentId/{caseId}")
    fun listByCase(
        @PathVariable caseId: UUID,
    ): List<CaseEvent> = caseEventService.findByParent(caseId)
}
