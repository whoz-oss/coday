package io.whozoss.agentos.namespace

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.sdk.api.membership.MemberItem
import io.whozoss.agentos.sdk.api.user.UserMembershipRole
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import java.util.UUID

/**
 * Unit tests for [NamespaceMembershipController].
 *
 * Covers HTTP-layer concerns: existence gates, delegation to
 * [NamespacePermissionService], and caller super-admin flag propagation.
 * Business logic (lockout guard, two-tier auth) is tested in
 * [NamespacePermissionServiceImplSpec].
 */
class NamespaceMembershipControllerSpec : StringSpec({

    val namespaceService = mockk<NamespaceService>()
    val userService = mockk<UserService>()
    val namespacePermissionService = mockk<NamespacePermissionService>()
    val controller = NamespaceMembershipController(namespaceService, userService, namespacePermissionService)

    val namespaceId = UUID.randomUUID()
    val targetUserId = UUID.randomUUID()
    val callerId = UUID.randomUUID()

    val caller = User(
        metadata = EntityMetadata(id = callerId),
        externalId = "caller@example.com",
        email = "caller@example.com",
        isAdmin = false,
    )
    val namespace = Namespace(
        metadata = EntityMetadata(id = namespaceId),
        name = "engineering",
        externalId = "ns-ext-engineering",
    )
    val memberItem = MemberItem(
        id = targetUserId,
        externalId = "target@example.com",
        email = "target@example.com",
        role = "MEMBER",
    )

    beforeTest { clearAllMocks() }

    // -------------------------------------------------------------------------
    // GET /{entityId}/members
    // -------------------------------------------------------------------------

    "getMembers delegates to namespacePermissionService.getMembers" {
        every { namespaceService.getById(namespaceId) } returns namespace
        every { namespacePermissionService.getMembers(namespaceId) } returns listOf(memberItem)

        val result = controller.getMembers(namespaceId)

        result shouldBe listOf(memberItem)
        verify(exactly = 1) { namespacePermissionService.getMembers(namespaceId) }
    }

    "getMembers returns 404 when namespace not found" {
        every { namespaceService.getById(namespaceId) } throws ResourceNotFoundException("Namespace not found: $namespaceId")

        shouldThrow<ResourceNotFoundException> { controller.getMembers(namespaceId) }
        verify(exactly = 0) { namespacePermissionService.getMembers(any()) }
    }

    "getMembers returns empty list when namespace has no members" {
        every { namespaceService.getById(namespaceId) } returns namespace
        every { namespacePermissionService.getMembers(namespaceId) } returns emptyList()

        controller.getMembers(namespaceId) shouldBe emptyList()
    }

    // -------------------------------------------------------------------------
    // PATCH /{entityId}/members
    // -------------------------------------------------------------------------

    "updateMembers delegates with callerIsSuperAdmin=true when caller is super-admin" {
        val members = listOf(UserMembershipRole(targetUserId, "MEMBER"))
        every { userService.getCurrentUser() } returns caller.copy(isAdmin = true)
        every { namespacePermissionService.updateMembers(namespaceId, members, true) } returns listOf(memberItem)

        val result = controller.updateMembers(namespaceId, members)

        result shouldBe listOf(memberItem)
        verify(exactly = 1) { namespacePermissionService.updateMembers(namespaceId, members, true) }
    }

    "updateMembers delegates with callerIsSuperAdmin=false when caller is not super-admin" {
        val members = listOf(UserMembershipRole(targetUserId, null))
        every { userService.getCurrentUser() } returns caller
        every { namespacePermissionService.updateMembers(namespaceId, members, false) } returns emptyList()

        controller.updateMembers(namespaceId, members)

        verify(exactly = 1) { namespacePermissionService.updateMembers(namespaceId, members, false) }
    }

    "updateMembers propagates exceptions from the service" {
        val members = listOf(UserMembershipRole(targetUserId, "ADMIN"))
        every { userService.getCurrentUser() } returns caller
        every { namespacePermissionService.updateMembers(namespaceId, members, false) } throws
            ResourceNotFoundException("Namespace not found: $namespaceId")

        shouldThrow<ResourceNotFoundException> { controller.updateMembers(namespaceId, members) }
    }
})
