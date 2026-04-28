@file:Suppress("ktlint:standard:filename")

package io.whozoss.agentos.caseEvent

import io.whozoss.agentos.entity.EntityController
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.security.declarative.HideOnAccessDenied
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.user.UserService
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
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
     *
     * **Why this controller does NOT extend [io.whozoss.agentos.entity.EntityController]
     * (story 5-4 design choice)** :
     * The factorised `EntityController.getByIds` resolves visibility on each `filterObject.id`
     * directly — it can't traverse to a parent entity. CaseEvents are protected by their
     * **parent Case's** READ permission, not by their own id. Forcing this case into the
     * factorised pattern would require either an abstract `parentIdOf(entity): String` method
     * (overkill for a single use case) or running the wrong query (event id instead of case id).
     *
     * As a result, fixes applied to [io.whozoss.agentos.entity.EntityController.getByIds]
     * (input order preservation, batch size cap, log WARN on parse failure) **must be
     * replicated manually here**. Today this method preserves order (`events.filter { ... }`
     * is stable) and applies the same [EntityController.MAX_BATCH_SIZE] cap as the base
     * (closes adversarial review P2 of story 5-4).
     */
    @PostMapping("/by-ids", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("isAuthenticated()")
    fun getByIds(
        @RequestBody ids: List<UUID>,
    ): List<CaseEvent> {
        if (ids.size > EntityController.MAX_BATCH_SIZE) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Batch size ${ids.size} exceeds maximum of ${EntityController.MAX_BATCH_SIZE}",
            )
        }
        if (ids.isEmpty()) return emptyList()

        val currentUser = userService.getCurrentUser()
        if (currentUser.isAdmin) {
            return caseEventService.findByIds(ids)
        }
        // Non-admin path : resolve visible parent caseIds in a single batch call BEFORE
        // materialising events. Was the inverse before — we'd load all events first then
        // filter — which exposed a timing oracle and unnecessary memory pressure.
        // Trade-off : we still need the full event list to know their caseIds, so we do
        // a 2-step fetch but the second step is bounded by the visible Case set.
        val events = caseEventService.findByIds(ids)
        if (events.isEmpty()) return emptyList()
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
