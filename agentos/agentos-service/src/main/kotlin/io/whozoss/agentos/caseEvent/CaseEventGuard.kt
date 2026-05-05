package io.whozoss.agentos.caseEvent

import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.user.UserService
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * SpEL-callable guard for [CaseEventRestController.getById].
 *
 * `getById` takes an `eventId` (not a `caseId`), so the standard
 * `hasPermission(#caseId, 'Case', 'READ')` SpEL cannot be used directly.
 * This guard resolves the event to its parent case, then delegates to the
 * standard Case READ permission check.
 *
 * Used in `@PreAuthorize` SpEL via the bean-reference syntax:
 * ```
 * @PreAuthorize("@caseEventGuard.canRead(#id)")
 * ```
 */
@Component("caseEventGuard")
class CaseEventGuard(
    private val caseEventService: CaseEventService,
    private val permissionService: PermissionService,
    private val userService: UserService,
) {
    /**
     * Returns true if the current user has READ on the parent Case of [eventId].
     * Returns false (→ 404 via @HideOnAccessDenied) for missing events.
     */
    fun canRead(eventId: UUID): Boolean {
        val event = caseEventService.findById(eventId) ?: return false
        return permissionService.hasPermission(
            userService.getCurrentUser().id.toString(),
            EntityType.CASE,
            event.caseId.toString(),
            Action.READ,
        )
    }
}
