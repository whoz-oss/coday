package io.whozoss.agentos.namespace

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionRelation
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import java.util.UUID

/**
 * Unit tests for [NamespacePermissionEndpoints].
 *
 * Covers the fine-grained per-user grant/revoke endpoints and [updateRolesByExternalId].
 * Membership listing and batch update are tested in [NamespaceMembershipControllerSpec].
 */
class NamespacePermissionEndpointsSpec :
    StringSpec({

        val namespaceService = mockk<NamespaceService>()
        val userService = mockk<UserService>()
        val permissionService = mockk<PermissionService>()
        val namespacePermissionService = mockk<NamespacePermissionService>()
        val controller =
            NamespacePermissionEndpoints(
                namespaceService,
                userService,
                permissionService,
                namespacePermissionService,
            )

        val namespaceId = UUID.randomUUID()
        val targetUserId = UUID.randomUUID()
        val callerId = UUID.randomUUID()

        val caller =
            User(
                metadata = EntityMetadata(id = callerId),
                externalId = "caller@example.com",
                email = "caller@example.com",
                isAdmin = false,
            )
        val target =
            User(
                metadata = EntityMetadata(id = targetUserId),
                externalId = "target@example.com",
                email = "target@example.com",
                isAdmin = false,
            )
        val namespace =
            Namespace(
                metadata = EntityMetadata(id = namespaceId),
                name = "engineering",
                externalId = "ns-ext-engineering",
            )

        fun stubExistence() {
            every { namespaceService.getById(namespaceId) } returns namespace
            every { userService.getById(targetUserId) } returns target
            every { userService.getCurrentUser() } returns caller
        }

        beforeTest { clearAllMocks() }

        // -------------------------------------------------------------------------
        // PUT — grant ADMIN
        // -------------------------------------------------------------------------

        "grantAdmin delegates to permissionService.grantPermission with ADMIN relation" {
            stubExistence()
            every { permissionService.grantPermission(any(), any(), any(), any()) } just Runs

            controller.grantAdmin(namespaceId, targetUserId)

            verify(exactly = 1) {
                permissionService.grantPermission(
                    targetUserId.toString(),
                    EntityType.NAMESPACE,
                    namespaceId.toString(),
                    PermissionRelation.ADMIN,
                )
            }
        }

        "grantAdmin returns 404 when namespace not found" {
            every { namespaceService.getById(namespaceId) } throws ResourceNotFoundException("Namespace not found: $namespaceId")

            shouldThrow<ResourceNotFoundException> { controller.grantAdmin(namespaceId, targetUserId) }
            verify(exactly = 0) { permissionService.grantPermission(any(), any(), any(), any()) }
        }

        "grantAdmin returns 404 when target user not found" {
            every { namespaceService.getById(namespaceId) } returns namespace
            every { userService.getById(targetUserId) } throws ResourceNotFoundException("User not found: $targetUserId")

            shouldThrow<ResourceNotFoundException> { controller.grantAdmin(namespaceId, targetUserId) }
            verify(exactly = 0) { permissionService.grantPermission(any(), any(), any(), any()) }
        }

        "grantAdmin is idempotent: repeated calls each delegate to grantPermission (Neo4j MERGE)" {
            stubExistence()
            every { permissionService.grantPermission(any(), any(), any(), any()) } just Runs

            controller.grantAdmin(namespaceId, targetUserId)
            controller.grantAdmin(namespaceId, targetUserId)

            verify(exactly = 2) { permissionService.grantPermission(any(), any(), any(), any()) }
        }

        // -------------------------------------------------------------------------
        // DELETE — revoke ADMIN
        // -------------------------------------------------------------------------

        "revokeAdmin delegates to permissionService.revokePermission with ADMIN relation" {
            stubExistence()
            every { permissionService.revokePermission(any(), any(), any(), any()) } just Runs

            controller.revokeAdmin(namespaceId, targetUserId)

            verify(exactly = 1) {
                permissionService.revokePermission(
                    targetUserId.toString(),
                    EntityType.NAMESPACE,
                    namespaceId.toString(),
                    PermissionRelation.ADMIN,
                )
            }
        }

        "revokeAdmin returns 404 when namespace not found" {
            every { namespaceService.getById(namespaceId) } throws ResourceNotFoundException("Namespace not found: $namespaceId")

            shouldThrow<ResourceNotFoundException> { controller.revokeAdmin(namespaceId, targetUserId) }
        }

        "revokeAdmin is idempotent: revoking a non-existent relation does not throw" {
            stubExistence()
            every { permissionService.revokePermission(any(), any(), any(), any()) } just Runs

            controller.revokeAdmin(namespaceId, targetUserId)

            verify(exactly = 1) { permissionService.revokePermission(any(), any(), any(), any()) }
        }

        // -------------------------------------------------------------------------
        // PUT — grant MEMBER
        // -------------------------------------------------------------------------

        "grantMember delegates to permissionService.grantPermission with MEMBER relation" {
            stubExistence()
            every { permissionService.grantPermission(any(), any(), any(), any()) } just Runs

            controller.grantMember(namespaceId, targetUserId)

            verify(exactly = 1) {
                permissionService.grantPermission(
                    targetUserId.toString(),
                    EntityType.NAMESPACE,
                    namespaceId.toString(),
                    PermissionRelation.MEMBER,
                )
            }
        }

        "grantMember returns 404 when target user not found" {
            every { namespaceService.getById(namespaceId) } returns namespace
            every { userService.getById(targetUserId) } throws ResourceNotFoundException("User not found: $targetUserId")

            shouldThrow<ResourceNotFoundException> { controller.grantMember(namespaceId, targetUserId) }
        }

        // -------------------------------------------------------------------------
        // DELETE — revoke MEMBER (does NOT touch ADMIN)
        // -------------------------------------------------------------------------

        "revokeMember does NOT revoke ADMIN relation" {
            stubExistence()
            every { permissionService.revokePermission(any(), any(), any(), any()) } just Runs

            controller.revokeMember(namespaceId, targetUserId)

            verify(exactly = 1) {
                permissionService.revokePermission(
                    targetUserId.toString(),
                    EntityType.NAMESPACE,
                    namespaceId.toString(),
                    PermissionRelation.MEMBER,
                )
            }
            verify(exactly = 0) {
                permissionService.revokePermission(
                    targetUserId.toString(),
                    EntityType.NAMESPACE,
                    namespaceId.toString(),
                    PermissionRelation.ADMIN,
                )
            }
        }

        "revokeMember is idempotent" {
            stubExistence()
            every { permissionService.revokePermission(any(), any(), any(), any()) } just Runs

            controller.revokeMember(namespaceId, targetUserId)
            controller.revokeMember(namespaceId, targetUserId)

            verify(exactly = 2) { permissionService.revokePermission(any(), any(), any(), any()) }
        }

        // -------------------------------------------------------------------------
        // POST /update-roles-by-external-id — delegation only
        // -------------------------------------------------------------------------

        "updateRolesByExternalId delegates to namespacePermissionService and returns request" {
            val request =
                SyncUserRolesRequest(
                    target.externalId,
                    listOf(NamespaceRoleEntry(namespace.externalId!!, "ADMIN")),
                )
            every { namespacePermissionService.syncUserRoles(request) } just Runs

            controller.updateRolesByExternalId(request) shouldBe request
            verify(exactly = 1) { namespacePermissionService.syncUserRoles(request) }
        }

        "updateRolesByExternalId propagates exceptions thrown by the service" {
            val request = SyncUserRolesRequest("unknown", emptyList())
            every { namespacePermissionService.syncUserRoles(request) } throws
                ResourceNotFoundException("User not found: unknown")

            shouldThrow<ResourceNotFoundException> { controller.updateRolesByExternalId(request) }
        }
    })
