package io.whozoss.agentos.caseFlow

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionRelation
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.api.membership.MemberItem
import io.whozoss.agentos.sdk.api.user.UserMembershipRole
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import java.util.UUID

/**
 * Unit tests for [CaseMembershipController].
 *
 * Covers: existence gate, delegation to [PermissionService.applyShareBatch],
 * self-modification filtering, and the return of the resulting member list.
 */
class CaseMembershipControllerSpec :
    StringSpec({

        val caseService = mockk<CaseService>()
        val userService = mockk<UserService>()
        val permissionService = mockk<PermissionService>()
        val controller = CaseMembershipController(caseService, userService, permissionService)

        val caseId = UUID.randomUUID()
        val callerId = UUID.randomUUID()
        val targetUserId = UUID.randomUUID()
        val namespaceId = UUID.randomUUID()

        val caller =
            User(
                metadata = EntityMetadata(id = callerId),
                externalId = "caller@example.com",
                email = "caller@example.com",
                isAdmin = false,
            )
        val caseEntity =
            Case(
                metadata = EntityMetadata(id = caseId),
                namespaceId = namespaceId,
                status = CaseStatus.PENDING,
                title = "test case",
            )

        fun stubCurrentMembers(
            adminIds: List<String> = emptyList(),
            memberIds: List<String> = emptyList(),
        ) {
            every {
                permissionService.listUsersWithPermission(EntityType.CASE, caseId.toString(), PermissionRelation.ADMIN)
            } returns adminIds
            every {
                permissionService.listUsersWithPermission(EntityType.CASE, caseId.toString(), PermissionRelation.MEMBER)
            } returns memberIds
        }

        beforeTest { clearAllMocks() }

        // -------------------------------------------------------------------------
        // GET /{entityId}/members
        // -------------------------------------------------------------------------

        "getMembers returns the current member list" {
            every { caseService.getById(caseId) } returns caseEntity
            stubCurrentMembers(memberIds = listOf(targetUserId.toString()))
            val targetUser =
                User(
                    metadata = EntityMetadata(id = targetUserId),
                    externalId = "target@example.com",
                    email = "target@example.com",
                    isAdmin = false,
                )
            every { userService.findByIds(listOf(targetUserId)) } returns listOf(targetUser)

            val result = controller.getMembers(caseId)

            result.size shouldBe 1
            result[0].id shouldBe targetUserId
            result[0].role shouldBe "MEMBER"
        }

        "getMembers returns 404 when case not found" {
            every { caseService.getById(caseId) } throws ResourceNotFoundException("Case not found: $caseId")

            shouldThrow<ResourceNotFoundException> { controller.getMembers(caseId) }
        }

        "getMembers returns empty list when case has no members" {
            every { caseService.getById(caseId) } returns caseEntity
            stubCurrentMembers()

            controller.getMembers(caseId) shouldBe emptyList()
        }

        // -------------------------------------------------------------------------
        // PATCH /{entityId}/members
        // -------------------------------------------------------------------------

        "updateMembers applies the batch and returns resulting member list" {
            val members = listOf(UserMembershipRole(targetUserId, "MEMBER"))
            every { caseService.getById(caseId) } returns caseEntity
            every { userService.getCurrentUser() } returns caller
            every {
                permissionService.applyShareBatch(
                    EntityType.CASE,
                    caseId.toString(),
                    listOf(targetUserId.toString() to PermissionRelation.MEMBER),
                )
            } returns listOf(targetUserId.toString())
            stubCurrentMembers(memberIds = listOf(targetUserId.toString()))
            val targetUser =
                User(
                    metadata = EntityMetadata(id = targetUserId),
                    externalId = "target@example.com",
                    email = "target@example.com",
                    isAdmin = false,
                )
            every { userService.findByIds(listOf(targetUserId)) } returns listOf(targetUser)

            val result = controller.updateMembers(caseId, members)

            result.size shouldBe 1
            result[0].id shouldBe targetUserId
            verify(exactly = 1) {
                permissionService.applyShareBatch(
                    EntityType.CASE,
                    caseId.toString(),
                    listOf(targetUserId.toString() to PermissionRelation.MEMBER),
                )
            }
        }

        "updateMembers silently filters self-modification entries" {
            val members =
                listOf(
                    UserMembershipRole(callerId, "ADMIN"), // self — filtered
                    UserMembershipRole(targetUserId, "MEMBER"),
                )
            every { caseService.getById(caseId) } returns caseEntity
            every { userService.getCurrentUser() } returns caller
            every {
                permissionService.applyShareBatch(
                    EntityType.CASE,
                    caseId.toString(),
                    listOf(targetUserId.toString() to PermissionRelation.MEMBER),
                )
            } returns listOf(targetUserId.toString())
            stubCurrentMembers(memberIds = listOf(targetUserId.toString()))
            val targetUser =
                User(
                    metadata = EntityMetadata(id = targetUserId),
                    externalId = "target@example.com",
                    email = "target@example.com",
                    isAdmin = false,
                )
            every { userService.findByIds(listOf(targetUserId)) } returns listOf(targetUser)

            controller.updateMembers(caseId, members)

            // Only the non-self entry reaches applyShareBatch
            verify(exactly = 1) {
                permissionService.applyShareBatch(
                    EntityType.CASE,
                    caseId.toString(),
                    listOf(targetUserId.toString() to PermissionRelation.MEMBER),
                )
            }
        }

        "updateMembers skips applyShareBatch when all entries are self-modification" {
            val members = listOf(UserMembershipRole(callerId, "ADMIN"))
            every { caseService.getById(caseId) } returns caseEntity
            every { userService.getCurrentUser() } returns caller
            stubCurrentMembers()

            controller.updateMembers(caseId, members)

            verify(exactly = 0) { permissionService.applyShareBatch(any(), any(), any()) }
        }

        "updateMembers returns 404 when case not found" {
            every { caseService.getById(caseId) } throws ResourceNotFoundException("Case not found: $caseId")
            every { userService.getCurrentUser() } returns caller

            shouldThrow<ResourceNotFoundException> {
                controller.updateMembers(caseId, listOf(UserMembershipRole(targetUserId, "MEMBER")))
            }
        }

        "updateMembers revokes a user when role is null" {
            val members = listOf(UserMembershipRole(targetUserId, null))
            every { caseService.getById(caseId) } returns caseEntity
            every { userService.getCurrentUser() } returns caller
            every {
                permissionService.applyShareBatch(
                    EntityType.CASE,
                    caseId.toString(),
                    listOf(targetUserId.toString() to null),
                )
            } returns emptyList()
            stubCurrentMembers()

            controller.updateMembers(caseId, members)

            verify(exactly = 1) {
                permissionService.applyShareBatch(
                    EntityType.CASE,
                    caseId.toString(),
                    listOf(targetUserId.toString() to null),
                )
            }
        }
    })
