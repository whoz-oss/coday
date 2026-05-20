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
import io.whozoss.agentos.permissions.Action
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
 * Authorization is declarative (`@PreAuthorize`) and only fires through Spring AOP.
 * Pure unit tests bypass the proxy — only the body's existence-check + delegation
 * to [PermissionService] is exercised here. The full 403/404 contract is covered
 * by [io.whozoss.agentos.security.declarative.MethodSecurityIntegrationSpec] and
 * the MVC integration spec.
 *
 * What this spec covers:
 * - grant/revoke ADMIN delegate to [PermissionService] with the right arguments
 * - grant/revoke MEMBER delegate symmetrically
 * - existence gates: 404 when namespace or target user not found
 * - idempotence: repeated grants/revokes still call through (Neo4j MERGE/DELETE handle it)
 * - listNamespaceUsers: dedup, role precedence, empty case
 */
class NamespacePermissionEndpointsSpec : StringSpec({

    val namespaceService = mockk<NamespaceService>()
    val userService = mockk<UserService>()
    val permissionService = mockk<PermissionService>()
    val controller = NamespacePermissionEndpoints(namespaceService, userService, permissionService)

    val namespaceId = UUID.randomUUID()
    val targetUserId = UUID.randomUUID()
    val callerId = UUID.randomUUID()

    val caller = User(
        metadata = EntityMetadata(id = callerId),
        externalId = "caller@example.com",
        email = "caller@example.com",
        isAdmin = false,
    )
    val target = User(
        metadata = EntityMetadata(id = targetUserId),
        externalId = "target@example.com",
        email = "target@example.com",
        isAdmin = false,
    )
    val namespace = Namespace(
        metadata = EntityMetadata(id = namespaceId),
        name = "engineering",
        externalId = "ns-ext-engineering",
    )

    fun stubExistence() {
        every { namespaceService.findById(namespaceId) } returns namespace
        every { userService.findById(targetUserId) } returns target
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
                targetUserId.toString(), EntityType.NAMESPACE, namespaceId.toString(), PermissionRelation.ADMIN,
            )
        }
    }

    "grantAdmin returns 404 when namespace not found" {
        every { namespaceService.findById(namespaceId) } returns null

        shouldThrow<ResourceNotFoundException> { controller.grantAdmin(namespaceId, targetUserId) }
        verify(exactly = 0) { permissionService.grantPermission(any(), any(), any(), any()) }
    }

    "grantAdmin returns 404 when target user not found" {
        every { namespaceService.findById(namespaceId) } returns namespace
        every { userService.findById(targetUserId) } returns null

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
                targetUserId.toString(), EntityType.NAMESPACE, namespaceId.toString(), PermissionRelation.ADMIN,
            )
        }
    }

    "revokeAdmin returns 404 when namespace not found" {
        every { namespaceService.findById(namespaceId) } returns null

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
                targetUserId.toString(), EntityType.NAMESPACE, namespaceId.toString(), PermissionRelation.MEMBER,
            )
        }
    }

    "grantMember returns 404 when target user not found" {
        every { namespaceService.findById(namespaceId) } returns namespace
        every { userService.findById(targetUserId) } returns null

        shouldThrow<ResourceNotFoundException> { controller.grantMember(namespaceId, targetUserId) }
    }

    // -------------------------------------------------------------------------
    // DELETE — revoke MEMBER (does NOT touch ADMIN)
    // -------------------------------------------------------------------------

    "revokeMember does NOT revoke ADMIN relation (Preserves higher privilege)" {
        stubExistence()
        every { permissionService.revokePermission(any(), any(), any(), any()) } just Runs

        controller.revokeMember(namespaceId, targetUserId)

        verify(exactly = 1) {
            permissionService.revokePermission(
                targetUserId.toString(), EntityType.NAMESPACE, namespaceId.toString(), PermissionRelation.MEMBER,
            )
        }
        verify(exactly = 0) {
            permissionService.revokePermission(
                targetUserId.toString(), EntityType.NAMESPACE, namespaceId.toString(), PermissionRelation.ADMIN,
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
        every { namespaceService.findById(namespaceId) } returns namespace
        val adminId = UUID.randomUUID()
        val memberId = UUID.randomUUID()
        val adminUser = User(metadata = EntityMetadata(id = adminId), externalId = "a", email = "a")
        val memberUser = User(metadata = EntityMetadata(id = memberId), externalId = "m", email = "m")
        every {
            permissionService.listUsersWithPermission(EntityType.NAMESPACE, namespaceId.toString(), PermissionRelation.ADMIN)
        } returns listOf(adminId.toString())
        every {
            permissionService.listUsersWithPermission(EntityType.NAMESPACE, namespaceId.toString(), PermissionRelation.MEMBER)
        } returns listOf(memberId.toString())
        every { userService.findByIds(any()) } returns listOf(adminUser, memberUser)

        val result = controller.listNamespaceUsers(namespaceId).associate { it.id to it.role }

        result[adminId] shouldBe "ADMIN"
        result[memberId] shouldBe "MEMBER"
    }

    "listNamespaceUsers deduplicates users with both ADMIN and MEMBER (role=ADMIN)" {
        every { namespaceService.findById(namespaceId) } returns namespace
        val dualId = UUID.randomUUID()
        val dualUser = User(metadata = EntityMetadata(id = dualId), externalId = "d", email = "d")
        every {
            permissionService.listUsersWithPermission(EntityType.NAMESPACE, namespaceId.toString(), PermissionRelation.ADMIN)
        } returns listOf(dualId.toString())
        every {
            permissionService.listUsersWithPermission(EntityType.NAMESPACE, namespaceId.toString(), PermissionRelation.MEMBER)
        } returns listOf(dualId.toString())
        every { userService.findByIds(listOf(dualId)) } returns listOf(dualUser)

        val result = controller.listNamespaceUsers(namespaceId)

        result.size shouldBe 1
        result[0].id shouldBe dualId
        result[0].role shouldBe "ADMIN"
    }

    "listNamespaceUsers returns empty list when no user has a direct relation" {
        every { namespaceService.findById(namespaceId) } returns namespace
        every {
            permissionService.listUsersWithPermission(EntityType.NAMESPACE, namespaceId.toString(), PermissionRelation.ADMIN)
        } returns emptyList()
        every {
            permissionService.listUsersWithPermission(EntityType.NAMESPACE, namespaceId.toString(), PermissionRelation.MEMBER)
        } returns emptyList()

        controller.listNamespaceUsers(namespaceId) shouldBe emptyList()
    }

    "listNamespaceUsers returns 404 when namespace not found" {
        every { namespaceService.findById(namespaceId) } returns null

        shouldThrow<ResourceNotFoundException> { controller.listNamespaceUsers(namespaceId) }
    }

    // -------------------------------------------------------------------------
    // POST /update-roles-by-external-id
    // -------------------------------------------------------------------------

    "updateRolesByExternalId returns empty list immediately when input is empty" {
        controller.updateRolesByExternalId(emptyList()) shouldBe emptyList()
    }

    "updateRolesByExternalId returns 404 when namespace external id is unknown" {
        every { namespaceService.findByExternalId("unknown-ns") } returns null

        shouldThrow<ResourceNotFoundException> {
            controller.updateRolesByExternalId(
                listOf(NamespaceRoleAssignment("unknown-ns", target.externalId, "ADMIN")),
            )
        }
        verify(exactly = 0) { permissionService.grantPermission(any(), any(), any(), any()) }
    }

    "updateRolesByExternalId returns 404 when user external id is unknown" {
        every { namespaceService.findByExternalId(namespace.externalId!!) } returns namespace
        every { userService.findByExternalId("unknown-user") } returns null

        shouldThrow<ResourceNotFoundException> {
            controller.updateRolesByExternalId(
                listOf(NamespaceRoleAssignment(namespace.externalId!!, "unknown-user", "MEMBER")),
            )
        }
        verify(exactly = 0) { permissionService.grantPermission(any(), any(), any(), any()) }
    }

    "updateRolesByExternalId grants ADMIN when user has no current relation" {
        every { namespaceService.findByExternalId(namespace.externalId!!) } returns namespace
        every { userService.findByExternalId(target.externalId) } returns target
        every { userService.getCurrentUser() } returns caller
        every { permissionService.listUsersWithPermission(EntityType.NAMESPACE, namespaceId.toString(), PermissionRelation.ADMIN) } returns emptyList()
        every { permissionService.listUsersWithPermission(EntityType.NAMESPACE, namespaceId.toString(), PermissionRelation.MEMBER) } returns emptyList()
        every { permissionService.grantPermission(any(), any(), any(), any()) } just Runs

        val result = controller.updateRolesByExternalId(
            listOf(NamespaceRoleAssignment(namespace.externalId!!, target.externalId, "ADMIN")),
        )

        result shouldBe listOf(NamespaceRoleAssignment(namespace.externalId!!, target.externalId, "ADMIN"))
        verify(exactly = 1) { permissionService.grantPermission(targetUserId.toString(), EntityType.NAMESPACE, namespaceId.toString(), PermissionRelation.ADMIN) }
        verify(exactly = 0) { permissionService.revokePermission(any(), any(), any(), any()) }
    }

    "updateRolesByExternalId is a no-op when role is already correct" {
        every { namespaceService.findByExternalId(namespace.externalId!!) } returns namespace
        every { userService.findByExternalId(target.externalId) } returns target
        every { permissionService.listUsersWithPermission(EntityType.NAMESPACE, namespaceId.toString(), PermissionRelation.ADMIN) } returns emptyList()
        every { permissionService.listUsersWithPermission(EntityType.NAMESPACE, namespaceId.toString(), PermissionRelation.MEMBER) } returns listOf(targetUserId.toString())

        val result = controller.updateRolesByExternalId(
            listOf(NamespaceRoleAssignment(namespace.externalId!!, target.externalId, "MEMBER")),
        )

        result shouldBe listOf(NamespaceRoleAssignment(namespace.externalId!!, target.externalId, "MEMBER"))
        verify(exactly = 0) { permissionService.grantPermission(any(), any(), any(), any()) }
        verify(exactly = 0) { permissionService.revokePermission(any(), any(), any(), any()) }
    }

    "updateRolesByExternalId revokes MEMBER and grants ADMIN on role upgrade" {
        every { namespaceService.findByExternalId(namespace.externalId!!) } returns namespace
        every { userService.findByExternalId(target.externalId) } returns target
        every { userService.getCurrentUser() } returns caller
        every { permissionService.listUsersWithPermission(EntityType.NAMESPACE, namespaceId.toString(), PermissionRelation.ADMIN) } returns emptyList()
        every { permissionService.listUsersWithPermission(EntityType.NAMESPACE, namespaceId.toString(), PermissionRelation.MEMBER) } returns listOf(targetUserId.toString())
        every { permissionService.revokePermission(any(), any(), any(), any()) } just Runs
        every { permissionService.grantPermission(any(), any(), any(), any()) } just Runs

        controller.updateRolesByExternalId(
            listOf(NamespaceRoleAssignment(namespace.externalId!!, target.externalId, "ADMIN")),
        )

        verify(exactly = 1) { permissionService.revokePermission(targetUserId.toString(), EntityType.NAMESPACE, namespaceId.toString(), PermissionRelation.MEMBER) }
        verify(exactly = 1) { permissionService.grantPermission(targetUserId.toString(), EntityType.NAMESPACE, namespaceId.toString(), PermissionRelation.ADMIN) }
    }

    "updateRolesByExternalId revokes ADMIN and grants MEMBER on role downgrade" {
        every { namespaceService.findByExternalId(namespace.externalId!!) } returns namespace
        every { userService.findByExternalId(target.externalId) } returns target
        every { userService.getCurrentUser() } returns caller
        every { permissionService.listUsersWithPermission(EntityType.NAMESPACE, namespaceId.toString(), PermissionRelation.ADMIN) } returns listOf(targetUserId.toString())
        every { permissionService.listUsersWithPermission(EntityType.NAMESPACE, namespaceId.toString(), PermissionRelation.MEMBER) } returns emptyList()
        every { permissionService.revokePermission(any(), any(), any(), any()) } just Runs
        every { permissionService.grantPermission(any(), any(), any(), any()) } just Runs

        controller.updateRolesByExternalId(
            listOf(NamespaceRoleAssignment(namespace.externalId!!, target.externalId, "MEMBER")),
        )

        verify(exactly = 1) { permissionService.revokePermission(targetUserId.toString(), EntityType.NAMESPACE, namespaceId.toString(), PermissionRelation.ADMIN) }
        verify(exactly = 1) { permissionService.grantPermission(targetUserId.toString(), EntityType.NAMESPACE, namespaceId.toString(), PermissionRelation.MEMBER) }
    }

    "updateRolesByExternalId handles multiple assignments in one call" {
        val ns2ExternalId = "ns-ext-2"
        val ns2Id = UUID.randomUUID()
        val ns2 = Namespace(metadata = EntityMetadata(id = ns2Id), name = "frontend", externalId = ns2ExternalId)
        every { namespaceService.findByExternalId(namespace.externalId!!) } returns namespace
        every { namespaceService.findByExternalId(ns2ExternalId) } returns ns2
        every { userService.findByExternalId(target.externalId) } returns target
        every { userService.getCurrentUser() } returns caller
        every { permissionService.listUsersWithPermission(EntityType.NAMESPACE, namespaceId.toString(), PermissionRelation.ADMIN) } returns emptyList()
        every { permissionService.listUsersWithPermission(EntityType.NAMESPACE, namespaceId.toString(), PermissionRelation.MEMBER) } returns emptyList()
        every { permissionService.listUsersWithPermission(EntityType.NAMESPACE, ns2Id.toString(), PermissionRelation.ADMIN) } returns emptyList()
        every { permissionService.listUsersWithPermission(EntityType.NAMESPACE, ns2Id.toString(), PermissionRelation.MEMBER) } returns emptyList()
        every { permissionService.grantPermission(any(), any(), any(), any()) } just Runs

        val input = listOf(
            NamespaceRoleAssignment(namespace.externalId!!, target.externalId, "ADMIN"),
            NamespaceRoleAssignment(ns2ExternalId, target.externalId, "MEMBER"),
        )
        val result = controller.updateRolesByExternalId(input)

        result shouldBe input
        verify(exactly = 1) { permissionService.grantPermission(targetUserId.toString(), EntityType.NAMESPACE, namespaceId.toString(), PermissionRelation.ADMIN) }
        verify(exactly = 1) { permissionService.grantPermission(targetUserId.toString(), EntityType.NAMESPACE, ns2Id.toString(), PermissionRelation.MEMBER) }
    }

    // -------------------------------------------------------------------------
    // GET /roles-for-user/{userId}
    // -------------------------------------------------------------------------

    "listNamespaceRolesForUser returns 404 when user not found" {
        every { userService.findById(targetUserId) } returns null

        shouldThrow<ResourceNotFoundException> { controller.listNamespaceRolesForUser(targetUserId) }
    }

    "listNamespaceRolesForUser returns empty list when user has no namespace grants" {
        every { userService.findById(targetUserId) } returns target
        every { permissionService.listEntitiesForUser(targetUserId.toString(), EntityType.NAMESPACE, Action.WRITE) } returns emptyList()
        every { permissionService.listEntitiesForUser(targetUserId.toString(), EntityType.NAMESPACE, Action.READ) } returns emptyList()

        controller.listNamespaceRolesForUser(targetUserId) shouldBe emptyList()
    }

    "listNamespaceRolesForUser returns ADMIN role for namespace where user has WRITE permission" {
        every { userService.findById(targetUserId) } returns target
        every { permissionService.listEntitiesForUser(targetUserId.toString(), EntityType.NAMESPACE, Action.WRITE) } returns listOf(namespaceId.toString())
        every { permissionService.listEntitiesForUser(targetUserId.toString(), EntityType.NAMESPACE, Action.READ) } returns listOf(namespaceId.toString())
        every { namespaceService.findByIds(listOf(namespaceId)) } returns listOf(namespace)

        val result = controller.listNamespaceRolesForUser(targetUserId)

        result.size shouldBe 1
        result[0].namespaceId shouldBe namespaceId
        result[0].namespaceName shouldBe namespace.name
        result[0].role shouldBe "ADMIN"
    }

    "listNamespaceRolesForUser returns MEMBER role for namespace where user has READ but not WRITE permission" {
        every { userService.findById(targetUserId) } returns target
        every { permissionService.listEntitiesForUser(targetUserId.toString(), EntityType.NAMESPACE, Action.WRITE) } returns emptyList()
        every { permissionService.listEntitiesForUser(targetUserId.toString(), EntityType.NAMESPACE, Action.READ) } returns listOf(namespaceId.toString())
        every { namespaceService.findByIds(listOf(namespaceId)) } returns listOf(namespace)

        val result = controller.listNamespaceRolesForUser(targetUserId)

        result.size shouldBe 1
        result[0].role shouldBe "MEMBER"
    }

    "listNamespaceRolesForUser returns ADMIN and MEMBER entries for different namespaces" {
        val ns2Id = UUID.randomUUID()
        val ns2 = Namespace(metadata = EntityMetadata(id = ns2Id), name = "frontend")
        every { userService.findById(targetUserId) } returns target
        every { permissionService.listEntitiesForUser(targetUserId.toString(), EntityType.NAMESPACE, Action.WRITE) } returns listOf(namespaceId.toString())
        every { permissionService.listEntitiesForUser(targetUserId.toString(), EntityType.NAMESPACE, Action.READ) } returns listOf(namespaceId.toString(), ns2Id.toString())
        every { namespaceService.findByIds(match { it.containsAll(listOf(namespaceId, ns2Id)) }) } returns listOf(namespace, ns2)

        val result = controller.listNamespaceRolesForUser(targetUserId).associateBy { it.namespaceId }

        result[namespaceId]?.role shouldBe "ADMIN"
        result[ns2Id]?.role shouldBe "MEMBER"
    }

    "listNamespaceRolesForUser assigns ADMIN when user has both WRITE and READ on same namespace" {
        every { userService.findById(targetUserId) } returns target
        every { permissionService.listEntitiesForUser(targetUserId.toString(), EntityType.NAMESPACE, Action.WRITE) } returns listOf(namespaceId.toString())
        every { permissionService.listEntitiesForUser(targetUserId.toString(), EntityType.NAMESPACE, Action.READ) } returns listOf(namespaceId.toString())
        every { namespaceService.findByIds(listOf(namespaceId)) } returns listOf(namespace)

        val result = controller.listNamespaceRolesForUser(targetUserId)

        result.size shouldBe 1
        result[0].role shouldBe "ADMIN"
    }
})
