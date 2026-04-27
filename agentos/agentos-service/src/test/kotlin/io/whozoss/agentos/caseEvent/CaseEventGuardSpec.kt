package io.whozoss.agentos.caseEvent

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.caseEvent.WarnEvent
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import java.util.UUID

/**
 * Unit tests for [CaseEventGuard].
 *
 * Covers the SpEL-callable `canRead(eventId)` predicate that gates
 * `@PreAuthorize("@caseEventGuard.canRead(#id)")` on
 * [CaseEventRestController.getById]. The guard resolves the event to its
 * parent case before delegating to [PermissionService] for the standard
 * Case READ check.
 */
class CaseEventGuardSpec : StringSpec({

    val caseEventService = mockk<CaseEventService>()
    val permissionService = mockk<PermissionService>()
    val userService = mockk<UserService>()
    val guard = CaseEventGuard(caseEventService, permissionService, userService)

    val callerId = UUID.randomUUID()
    val caller = User(
        metadata = EntityMetadata(id = callerId),
        externalId = "alice@example.com",
        email = "alice@example.com",
        isAdmin = false,
    )
    val namespaceId = UUID.randomUUID()
    val caseId = UUID.randomUUID()
    val eventId = UUID.randomUUID()

    fun event(id: UUID = eventId, parentCaseId: UUID = caseId) = WarnEvent(
        metadata = EntityMetadata(id = id),
        namespaceId = namespaceId,
        caseId = parentCaseId,
        message = "test event",
    )

    "canRead returns true when caller has READ on the parent Case" {
        every { userService.getCurrentUser() } returns caller
        every { caseEventService.findById(eventId) } returns event()
        every {
            permissionService.hasPermission(callerId.toString(), "Case", caseId.toString(), Action.READ)
        } returns true

        guard.canRead(eventId) shouldBe true
    }

    "canRead returns false when caller lacks READ on the parent Case" {
        every { userService.getCurrentUser() } returns caller
        every { caseEventService.findById(eventId) } returns event()
        every {
            permissionService.hasPermission(callerId.toString(), "Case", caseId.toString(), Action.READ)
        } returns false

        guard.canRead(eventId) shouldBe false
    }

    "canRead returns false when the event does not exist (→ controller's @HideOnAccessDenied yields 404)" {
        every { caseEventService.findById(eventId) } returns null

        guard.canRead(eventId) shouldBe false
    }
})
