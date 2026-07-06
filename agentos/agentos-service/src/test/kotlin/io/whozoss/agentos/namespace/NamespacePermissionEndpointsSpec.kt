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
 * Covers HTTP-layer concerns only: existence gates, delegation to collaborators,
 * and idempotency of individual grant/revoke endpoints.
 * Business logic for [NamespacePermissionEndpoints.updateRolesByExternalId] is
 * tested in [NamespacePermissionServiceImplSpec].
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
        // GET users — listing with role precedence
        // -------------------------------------------------------------------------

        "listNamespaceUsers returns list with ADMIN/MEMBER roles" {
            every { namespaceService.findById(namespaceId, false) } returns namespace
            val adminId = UUID.randomUUID()
            val memberId = UUID.randomUUID()
            val adminUser = User(metadata = EntityMetadata(id = adminId), externalId = "a", email = "a")
            val memberUser = User(metadata = EntityMetadata(id = memberId), externalId = "m", email = "m")
            every {
                permissionService.listUsersWithPermission(
                    EntityType.NAMESPACE,
                    namespaceId.toString(),
                    PermissionRelation.ADMIN,
                )
            } returns listOf(adminId.toString())
            every {
                permissionService.listUsersWithPermission(
                    EntityType.NAMESPACE,
                    namespaceId.toString(),
                    PermissionRelation.MEMBER,
                )
            } returns listOf(memberId.toString())
            every { userService.findByIds(any()) } returns listOf(adminUser, memberUser)

            val result = controller.listNamespaceUsers(namespaceId).associate { it.id to it.role }

            result[adminId] shouldBe "ADMIN"
            result[memberId] shouldBe "MEMBER"
        }

        "listNamespaceUsers deduplicates users with both ADMIN and MEMBER (role=ADMIN)" {
            every { namespaceService.findById(namespaceId, false) } returns namespace
            val dualId = UUID.randomUUID()
            val dualUser = User(metadata = EntityMetadata(id = dualId), externalId = "d", email = "d")
            every {
                permissionService.listUsersWithPermission(
                    EntityType.NAMESPACE,
                    namespaceId.toString(),
                    PermissionRelation.ADMIN,
                )
            } returns listOf(dualId.toString())
            every {
                permissionService.listUsersWithPermission(
                    EntityType.NAMESPACE,
                    namespaceId.toString(),
                    PermissionRelation.MEMBER,
                )
            } returns listOf(dualId.toString())
            every { userService.findByIds(listOf(dualId)) } returns listOf(dualUser)

            val result = controller.listNamespaceUsers(namespaceId)

            result.size shouldBe 1
            result[0].id shouldBe dualId
            result[0].role shouldBe "ADMIN"
        }

        "listNamespaceUsers returns empty list when no user has a direct relation" {
            every { namespaceService.findById(namespaceId, false) } returns namespace
            every {
                permissionService.listUsersWithPermission(
                    EntityType.NAMESPACE,
                    namespaceId.toString(),
                    PermissionRelation.ADMIN,
                )
            } returns emptyList()
            every {
                permissionService.listUsersWithPermission(
                    EntityType.NAMESPACE,
                    namespaceId.toString(),
                    PermissionRelation.MEMBER,
                )
            } returns emptyList()

            controller.listNamespaceUsers(namespaceId) shouldBe emptyList()
        }

        "listNamespaceUsers returns 404 when namespace not found" {
            every { namespaceService.findById(namespaceId, false) } returns null

            shouldThrow<ResourceNotFoundException> { controller.listNamespaceUsers(namespaceId) }
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
