package io.whozoss.agentos.namespace

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.auth.AccessDeniedException
import io.whozoss.agentos.auth.AuthorizationService
import io.whozoss.agentos.auth.MembershipInfo
import io.whozoss.agentos.auth.RoleRepository
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.sdk.auth.NamespaceRole
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import java.time.Instant
import java.util.UUID

/**
 * Unit tests for [MembershipController].
 *
 * The controller is instantiated directly with MockK stubs — no Spring context.
 * Tests cover:
 * - [MembershipController.assignRole]   — success, access denied, invalid role
 * - [MembershipController.listMembers]  — success with mapping MembershipInfo → MembershipResource
 * - [MembershipController.updateRole]   — success, last OWNER protection
 * - [MembershipController.revokeMember] — success, last OWNER protection
 * - isRoot bypass (implicit via AuthorizationService mock that does not throw)
 */
class MembershipControllerSpec : StringSpec({
    timeout = 5000

    val roleRepository = mockk<RoleRepository>()
    val authorizationService = mockk<AuthorizationService>()
    val userService = mockk<UserService>()
    val controller = MembershipController(roleRepository, authorizationService, userService)

    val adminUserId = UUID.randomUUID()
    val adminUser = User(
        metadata = EntityMetadata(id = adminUserId),
        externalId = "admin@example.com",
    )
    val nsId = UUID.randomUUID().toString()
    val targetUserId = UUID.randomUUID().toString()

    beforeEach {
        clearMocks(roleRepository, authorizationService, userService)
        every { userService.getCurrentUser() } returns adminUser
    }

    // -------------------------------------------------------------------------
    // assignRole
    // -------------------------------------------------------------------------

    "assignRole creates membership and returns 201" {
        every { authorizationService.requireNamespaceAccess(any(), any(), any()) } just Runs
        every { authorizationService.isRoot(adminUserId.toString()) } returns false
        every { roleRepository.findNamespaceRole(adminUserId.toString(), nsId) } returns NamespaceRole.OWNER
        every { roleRepository.assignNamespaceRole(any(), any(), any(), any()) } just Runs

        val result = controller.assignRole(
            nsId,
            MembershipResource(userId = targetUserId, role = "MEMBER"),
        )

        result.userId shouldBe targetUserId
        result.role shouldBe "MEMBER"
        verify(exactly = 1) { roleRepository.assignNamespaceRole(targetUserId, nsId, NamespaceRole.MEMBER, adminUserId.toString()) }
    }

    "assignRole throws AccessDeniedException when user is not ADMIN" {
        every {
            authorizationService.requireNamespaceAccess(any(), any(), any())
        } throws AccessDeniedException("Role MEMBER does not satisfy required ADMIN")

        shouldThrow<AccessDeniedException> {
            controller.assignRole(
                nsId,
                MembershipResource(userId = targetUserId, role = "MEMBER"),
            )
        }
    }

    "assignRole throws IllegalArgumentException for invalid role" {
        every { authorizationService.requireNamespaceAccess(any(), any(), any()) } just Runs

        shouldThrow<IllegalArgumentException> {
            controller.assignRole(
                nsId,
                MembershipResource(userId = targetUserId, role = "SUPERADMIN"),
            )
        }
    }

    "assignRole succeeds for isRoot user (implicit — authorizationService does not throw)" {
        every { authorizationService.requireNamespaceAccess(any(), any(), any()) } just Runs
        every { authorizationService.isRoot(adminUserId.toString()) } returns true
        every { roleRepository.assignNamespaceRole(any(), any(), any(), any()) } just Runs

        val result = controller.assignRole(
            nsId,
            MembershipResource(userId = targetUserId, role = "ADMIN"),
        )

        result.role shouldBe "ADMIN"
    }

    "assignRole throws AccessDeniedException when ADMIN tries to assign OWNER (P-6 escalation)" {
        every { authorizationService.requireNamespaceAccess(any(), any(), any()) } just Runs
        every { authorizationService.isRoot(adminUserId.toString()) } returns false
        every { roleRepository.findNamespaceRole(adminUserId.toString(), nsId) } returns NamespaceRole.ADMIN

        val ex = shouldThrow<AccessDeniedException> {
            controller.assignRole(
                nsId,
                MembershipResource(userId = targetUserId, role = "OWNER"),
            )
        }
        ex.requiredRole shouldBe "OWNER"
    }

    // -------------------------------------------------------------------------
    // listMembers
    // -------------------------------------------------------------------------

    "listMembers returns all members mapped to MembershipResource" {
        every { authorizationService.requireNamespaceAccess(any(), any(), any()) } just Runs
        val now = Instant.now()
        val members = listOf(
            MembershipInfo(userId = "user-1", role = NamespaceRole.OWNER, grantedAt = now, grantedBy = "system"),
            MembershipInfo(userId = "user-2", role = NamespaceRole.ADMIN, grantedAt = now, grantedBy = "user-1"),
            MembershipInfo(userId = "user-3", role = NamespaceRole.MEMBER, grantedAt = now, grantedBy = "user-1"),
        )
        every { roleRepository.findMembersOfNamespace(nsId) } returns members

        val result = controller.listMembers(nsId)

        result shouldHaveSize 3
        result[0].userId shouldBe "user-1"
        result[0].role shouldBe "OWNER"
        result[0].grantedAt shouldBe now.toString()
        result[0].grantedBy shouldBe "system"
        result[1].role shouldBe "ADMIN"
        result[2].role shouldBe "MEMBER"
    }

    "listMembers returns empty list when no members" {
        every { authorizationService.requireNamespaceAccess(any(), any(), any()) } just Runs
        every { roleRepository.findMembersOfNamespace(nsId) } returns emptyList()

        val result = controller.listMembers(nsId)

        result shouldHaveSize 0
    }

    // -------------------------------------------------------------------------
    // updateRole
    // -------------------------------------------------------------------------

    "updateRole updates membership and returns 200" {
        every { authorizationService.requireNamespaceAccess(any(), any(), any()) } just Runs
        every { authorizationService.isRoot(adminUserId.toString()) } returns false
        every { roleRepository.findNamespaceRole(targetUserId, nsId) } returns NamespaceRole.MEMBER
        every { roleRepository.findNamespaceRole(adminUserId.toString(), nsId) } returns NamespaceRole.OWNER
        every { roleRepository.assignNamespaceRole(any(), any(), any(), any()) } just Runs

        val result = controller.updateRole(
            nsId,
            targetUserId,
            MembershipResource(role = "ADMIN"),
        )

        result.userId shouldBe targetUserId
        result.role shouldBe "ADMIN"
        verify(exactly = 1) { roleRepository.assignNamespaceRole(targetUserId, nsId, NamespaceRole.ADMIN, adminUserId.toString()) }
    }

    "updateRole throws ResourceNotFoundException when target user has no role (P-3)" {
        every { authorizationService.requireNamespaceAccess(any(), any(), any()) } just Runs
        every { roleRepository.findNamespaceRole(targetUserId, nsId) } returns null

        shouldThrow<ResourceNotFoundException> {
            controller.updateRole(
                nsId,
                targetUserId,
                MembershipResource(role = "ADMIN"),
            )
        }
    }

    "updateRole throws IllegalArgumentException when demoting last OWNER" {
        every { authorizationService.requireNamespaceAccess(any(), any(), any()) } just Runs
        every { authorizationService.isRoot(adminUserId.toString()) } returns false
        every { roleRepository.findNamespaceRole(targetUserId, nsId) } returns NamespaceRole.OWNER
        every { roleRepository.findNamespaceRole(adminUserId.toString(), nsId) } returns NamespaceRole.OWNER
        every { roleRepository.countOwnersInNamespace(nsId) } returns 1

        shouldThrow<IllegalArgumentException> {
            controller.updateRole(
                nsId,
                targetUserId,
                MembershipResource(role = "MEMBER"),
            )
        }
    }

    "updateRole allows demoting OWNER when other OWNERs exist" {
        every { authorizationService.requireNamespaceAccess(any(), any(), any()) } just Runs
        every { authorizationService.isRoot(adminUserId.toString()) } returns false
        every { roleRepository.findNamespaceRole(targetUserId, nsId) } returns NamespaceRole.OWNER
        every { roleRepository.findNamespaceRole(adminUserId.toString(), nsId) } returns NamespaceRole.OWNER
        every { roleRepository.countOwnersInNamespace(nsId) } returns 2
        every { roleRepository.assignNamespaceRole(any(), any(), any(), any()) } just Runs

        val result = controller.updateRole(
            nsId,
            targetUserId,
            MembershipResource(role = "ADMIN"),
        )

        result.role shouldBe "ADMIN"
    }

    "revokeMember throws ResourceNotFoundException when target user has no role (P-3)" {
        every { authorizationService.requireNamespaceAccess(any(), any(), any()) } just Runs
        every { roleRepository.findNamespaceRole(targetUserId, nsId) } returns null

        shouldThrow<ResourceNotFoundException> {
            controller.revokeMember(nsId, targetUserId)
        }
    }

    // -------------------------------------------------------------------------
    // revokeMember
    // -------------------------------------------------------------------------

    "revokeMember removes membership and returns 204" {
        every { authorizationService.requireNamespaceAccess(any(), any(), any()) } just Runs
        every { roleRepository.findNamespaceRole(targetUserId, nsId) } returns NamespaceRole.MEMBER
        every { roleRepository.removeNamespaceRole(targetUserId, nsId) } just Runs

        controller.revokeMember(nsId, targetUserId)

        verify(exactly = 1) { roleRepository.removeNamespaceRole(targetUserId, nsId) }
    }

    "revokeMember throws IllegalArgumentException when revoking last OWNER" {
        every { authorizationService.requireNamespaceAccess(any(), any(), any()) } just Runs
        every { roleRepository.findNamespaceRole(targetUserId, nsId) } returns NamespaceRole.OWNER
        every { roleRepository.countOwnersInNamespace(nsId) } returns 1

        shouldThrow<IllegalArgumentException> {
            controller.revokeMember(nsId, targetUserId)
        }
    }

    "revokeMember allows revoking OWNER when other OWNERs exist" {
        every { authorizationService.requireNamespaceAccess(any(), any(), any()) } just Runs
        every { roleRepository.findNamespaceRole(targetUserId, nsId) } returns NamespaceRole.OWNER
        every { roleRepository.countOwnersInNamespace(nsId) } returns 2
        every { roleRepository.removeNamespaceRole(targetUserId, nsId) } just Runs

        controller.revokeMember(nsId, targetUserId)

        verify(exactly = 1) { roleRepository.removeNamespaceRole(targetUserId, nsId) }
    }

    "revokeMember allows revoking non-OWNER member" {
        every { authorizationService.requireNamespaceAccess(any(), any(), any()) } just Runs
        every { roleRepository.findNamespaceRole(targetUserId, nsId) } returns NamespaceRole.ADMIN
        every { roleRepository.removeNamespaceRole(targetUserId, nsId) } just Runs

        controller.revokeMember(nsId, targetUserId)

        verify(exactly = 1) { roleRepository.removeNamespaceRole(targetUserId, nsId) }
    }
})
