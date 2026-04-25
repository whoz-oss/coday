@file:Suppress("ktlint:standard:filename")

package io.whozoss.agentos.caseEvent

import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.security.declarative.HideOnAccessDenied
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PostFilter
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Read-only REST API for [CaseEvent] entities.
 *
 * Authorization (Story 5.2 — closes pre-existing gap, RFC §1 / Q7):
 * - `getById` requires READ on the parent Case (resolved via [CaseEventGuard])
 * - `getByIds` filters the response per-event via @PostFilter
 * - `listByCase` requires READ on the case directly (caseId is in the path)
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
    /** GET /api/case-events/{id} — get a single event. READ on the parent Case required. */
    @GetMapping("/{id}")
    @PreAuthorize("@caseEventGuard.canRead(#id)")
    @HideOnAccessDenied
    fun getById(
        @PathVariable id: UUID,
    ): CaseEvent =
        caseEventService.findById(id)
            ?: throw ResourceNotFoundException("CaseEvent not found: $id")

    /** POST /api/case-events/by-ids — get multiple events. Per-event filter on parent Case READ. */
    @PostMapping("/by-ids", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PostFilter("hasPermission(filterObject.caseId, 'Case', 'READ')")
    fun getByIds(
        @RequestBody ids: List<UUID>,
    ): List<CaseEvent> = caseEventService.findByIds(ids)

    /** GET /api/case-events/by-parentId/{caseId} — list all events for a case, ordered by timestamp. */
    @GetMapping("/by-parentId/{caseId}")
    @PreAuthorize("hasPermission(#caseId, 'Case', 'READ')")
    fun listByCase(
        @PathVariable caseId: UUID,
    ): List<CaseEvent> = caseEventService.findByParent(caseId)
}
