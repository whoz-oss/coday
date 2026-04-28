@file:Suppress("ktlint:standard:filename")

package io.whozoss.agentos.caseEvent

import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.security.declarative.HideOnAccessDenied
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.user.UserService
import org.springframework.http.MediaType
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
 * Authorization:
 * - `getById` requires READ on the parent Case (resolved via [CaseEventGuard])
 * - `getByIds` filters events whose parent **Case** is readable by the caller in a
 *   single batch Cypher (story 5-3, replaces `@PostFilter` per-event N+1)
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
    private val userService: UserService,
    private val permissionService: PermissionService,
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

    /**
     * POST /api/case-events/by-ids — get multiple events.
     *
     * Filtering is on the **parent Case**, not on the event itself: an event is
     * returned only if the caller can READ its `caseId`. Resolution is batch via
     * [PermissionService.filterVisibleIds] on the unique caseIds (typically far
     * fewer than the events, since events cluster by case).
     */
    @PostMapping("/by-ids", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("isAuthenticated()")
    fun getByIds(
        @RequestBody ids: List<UUID>,
    ): List<CaseEvent> {
        if (ids.isEmpty()) return emptyList()
        val events = caseEventService.findByIds(ids)
        if (events.isEmpty()) return emptyList()
        val currentUser = userService.getCurrentUser()
        if (currentUser.isAdmin) return events

        val caseIds = events.map { it.caseId.toString() }.distinct()
        val visibleCaseIds = permissionService
            .filterVisibleIds(currentUser.id.toString(), "Case", caseIds, Action.READ)
        return events.filter { it.caseId.toString() in visibleCaseIds }
    }

    /** GET /api/case-events/by-parentId/{caseId} — list all events for a case, ordered by timestamp. */
    @GetMapping("/by-parentId/{caseId}")
    @PreAuthorize("hasPermission(#caseId, 'Case', 'READ')")
    fun listByCase(
        @PathVariable caseId: UUID,
    ): List<CaseEvent> = caseEventService.findByParent(caseId)
}
