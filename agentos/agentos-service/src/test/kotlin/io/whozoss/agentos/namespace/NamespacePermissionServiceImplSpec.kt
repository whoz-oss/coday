package io.whozoss.agentos.namespace

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verifyOrder
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

class NamespacePermissionServiceImplSpec : StringSpec({

    val namespaceService = mockk<NamespaceService>()
    val userService = mockk<UserService>()
    val permissionService = mockk<PermissionService>()
    val service = NamespacePermissionServiceImpl(namespaceService, userService, permissionService)

    val namespaceId = UUID.randomUUID()
    val userId = UUID.randomUUID()

    val user = User(
        metadata = EntityMetadata(id = userId),
        externalId = "user@example.com",
        email = "user@example.com",
        isAdmin = false,
    )
    val namespace = Namespace(
        metadata = EntityMetadata(id = namespaceId),
        name = "engineering",
        externalId = "ns-ext-engineering",
    )

    fun stubCurrentRoles(adminIds: List<String> = emptyList(), readIds: List<String> = emptyList()) {
        every { permissionService.listEntitiesForUser(userId.toString(), EntityType.NAMESPACE, Action.WRITE) } returns adminIds
        every { permissionService.listEntitiesForUser(userId.toString(), EntityType.NAMESPACE, Action.READ) } returns readIds
    }

    beforeTest { clearAllMocks() }

    // -------------------------------------------------------------------------
    // Guard: unknown user
    // -------------------------------------------------------------------------

    "syncUserRoles throws 404 when user external id is unknown" {
        every { userService.findByExternalId("unknown") } returns null

        shouldThrow<ResourceNotFoundException> {
            service.syncUserRoles(SyncUserRolesRequest("unknown", emptyList()))
        }
        verify(exactly = 0) { permissionService.grantPermission(any(), any(), any(), any()) }
    }

    // -------------------------------------------------------------------------
    // Unknown namespace external id — skip behaviour
    // -------------------------------------------------------------------------

    "syncUserRoles silently skips a fully unknown namespace external id" {
        every { userService.findByExternalId(user.externalId) } returns user
        every { namespaceService.findByExternalIds(listOf("unknown-ns")) } returns emptyList()
        stubCurrentRoles()

        service.syncUserRoles(
            SyncUserRolesRequest(user.externalId, listOf(NamespaceRoleEntry("unknown-ns", "ADMIN"))),
        )

        verify(exactly = 0) { permissionService.grantPermission(any(), any(), any(), any()) }
        verify(exactly = 0) { permissionService.revokePermission(any(), any(), any(), any()) }
    }

    "syncUserRoles processes known namespaces and skips unknown ones in the same request" {
        every { userService.findByExternalId(user.externalId) } returns user
        every {
            namespaceService.findByExternalIds(match { it.containsAll(listOf(namespace.externalId!!, "ghost-ns")) })
        } returns listOf(namespace) // ghost-ns not found
        stubCurrentRoles()
        every { permissionService.grantPermission(any(), any(), any(), any()) } just Runs

        service.syncUserRoles(
            SyncUserRolesRequest(
                user.externalId,
                listOf(
                    NamespaceRoleEntry(namespace.externalId!!, "ADMIN"),
                    NamespaceRoleEntry("ghost-ns", "MEMBER"),
                ),
            ),
        )

        // known namespace is processed normally
        verify(exactly = 1) {
            permissionService.grantPermission(
                userId.toString(), EntityType.NAMESPACE, namespaceId.toString(), PermissionRelation.ADMIN,
            )
        }
        // ghost-ns produces no side-effects
        verify(exactly = 1) { permissionService.grantPermission(any(), any(), any(), any()) }
        verify(exactly = 0) { permissionService.revokePermission(any(), any(), any(), any()) }
    }

    // -------------------------------------------------------------------------
    // Empty assignments
    // -------------------------------------------------------------------------

    "syncUserRoles with empty assignments and no current relations is a no-op" {
        every { userService.findByExternalId(user.externalId) } returns user
        stubCurrentRoles()

        service.syncUserRoles(SyncUserRolesRequest(user.externalId, emptyList()))

        verify(exactly = 0) { namespaceService.findByExternalIds(any()) }
        verify(exactly = 0) { permissionService.grantPermission(any(), any(), any(), any()) }
        verify(exactly = 0) { permissionService.revokePermission(any(), any(), any(), any()) }
    }

    "syncUserRoles with empty assignments revokes all current relations" {
        val otherNsId = UUID.randomUUID()
        every { userService.findByExternalId(user.externalId) } returns user
        stubCurrentRoles(
            adminIds = listOf(namespaceId.toString()),
            readIds = listOf(namespaceId.toString(), otherNsId.toString()),
        )
        every { permissionService.revokePermission(any(), any(), any(), any()) } just Runs

        service.syncUserRoles(SyncUserRolesRequest(user.externalId, emptyList()))

        verify(exactly = 1) { permissionService.revokePermission(userId.toString(), EntityType.NAMESPACE, namespaceId.toString(), PermissionRelation.ADMIN) }
        verify(exactly = 1) { permissionService.revokePermission(userId.toString(), EntityType.NAMESPACE, otherNsId.toString(), PermissionRelation.MEMBER) }
        verify(exactly = 0) { permissionService.grantPermission(any(), any(), any(), any()) }
    }

    // -------------------------------------------------------------------------
    // Role delta
    // -------------------------------------------------------------------------

    "syncUserRoles grants ADMIN when user has no current relation" {
        every { userService.findByExternalId(user.externalId) } returns user
        every { namespaceService.findByExternalIds(listOf(namespace.externalId!!)) } returns listOf(namespace)
        stubCurrentRoles()
        every { permissionService.grantPermission(any(), any(), any(), any()) } just Runs

        service.syncUserRoles(
            SyncUserRolesRequest(user.externalId, listOf(NamespaceRoleEntry(namespace.externalId!!, "ADMIN"))),
        )

        verify(exactly = 1) { permissionService.grantPermission(userId.toString(), EntityType.NAMESPACE, namespaceId.toString(), PermissionRelation.ADMIN) }
        verify(exactly = 0) { permissionService.revokePermission(any(), any(), any(), any()) }
    }

    "syncUserRoles grants MEMBER when user has no current relation" {
        every { userService.findByExternalId(user.externalId) } returns user
        every { namespaceService.findByExternalIds(listOf(namespace.externalId!!)) } returns listOf(namespace)
        stubCurrentRoles()
        every { permissionService.grantPermission(any(), any(), any(), any()) } just Runs

        service.syncUserRoles(
            SyncUserRolesRequest(user.externalId, listOf(NamespaceRoleEntry(namespace.externalId!!, "MEMBER"))),
        )

        verify(exactly = 1) { permissionService.grantPermission(userId.toString(), EntityType.NAMESPACE, namespaceId.toString(), PermissionRelation.MEMBER) }
        verify(exactly = 0) { permissionService.revokePermission(any(), any(), any(), any()) }
    }

    "syncUserRoles is a no-op when ADMIN role is already correct" {
        every { userService.findByExternalId(user.externalId) } returns user
        every { namespaceService.findByExternalIds(listOf(namespace.externalId!!)) } returns listOf(namespace)
        stubCurrentRoles(
            adminIds = listOf(namespaceId.toString()),
            readIds = listOf(namespaceId.toString()),
        )

        service.syncUserRoles(
            SyncUserRolesRequest(user.externalId, listOf(NamespaceRoleEntry(namespace.externalId!!, "ADMIN"))),
        )

        verify(exactly = 0) { permissionService.grantPermission(any(), any(), any(), any()) }
        verify(exactly = 0) { permissionService.revokePermission(any(), any(), any(), any()) }
    }

    "syncUserRoles is a no-op when MEMBER role is already correct" {
        every { userService.findByExternalId(user.externalId) } returns user
        every { namespaceService.findByExternalIds(listOf(namespace.externalId!!)) } returns listOf(namespace)
        stubCurrentRoles(readIds = listOf(namespaceId.toString()))

        service.syncUserRoles(
            SyncUserRolesRequest(user.externalId, listOf(NamespaceRoleEntry(namespace.externalId!!, "MEMBER"))),
        )

        verify(exactly = 0) { permissionService.grantPermission(any(), any(), any(), any()) }
        verify(exactly = 0) { permissionService.revokePermission(any(), any(), any(), any()) }
    }

    "syncUserRoles revokes MEMBER and grants ADMIN on role upgrade" {
        every { userService.findByExternalId(user.externalId) } returns user
        every { namespaceService.findByExternalIds(listOf(namespace.externalId!!)) } returns listOf(namespace)
        stubCurrentRoles(readIds = listOf(namespaceId.toString()))
        every { permissionService.revokePermission(any(), any(), any(), any()) } just Runs
        every { permissionService.grantPermission(any(), any(), any(), any()) } just Runs

        service.syncUserRoles(
            SyncUserRolesRequest(user.externalId, listOf(NamespaceRoleEntry(namespace.externalId!!, "ADMIN"))),
        )

        verifyOrder {
            permissionService.revokePermission(userId.toString(), EntityType.NAMESPACE, namespaceId.toString(), PermissionRelation.MEMBER)
            permissionService.grantPermission(userId.toString(), EntityType.NAMESPACE, namespaceId.toString(), PermissionRelation.ADMIN)
        }
    }

    "syncUserRoles revokes ADMIN and grants MEMBER on role downgrade" {
        every { userService.findByExternalId(user.externalId) } returns user
        every { namespaceService.findByExternalIds(listOf(namespace.externalId!!)) } returns listOf(namespace)
        stubCurrentRoles(
            adminIds = listOf(namespaceId.toString()),
            readIds = listOf(namespaceId.toString()),
        )
        every { permissionService.revokePermission(any(), any(), any(), any()) } just Runs
        every { permissionService.grantPermission(any(), any(), any(), any()) } just Runs

        service.syncUserRoles(
            SyncUserRolesRequest(user.externalId, listOf(NamespaceRoleEntry(namespace.externalId!!, "MEMBER"))),
        )

        verifyOrder {
            permissionService.revokePermission(userId.toString(), EntityType.NAMESPACE, namespaceId.toString(), PermissionRelation.ADMIN)
            permissionService.grantPermission(userId.toString(), EntityType.NAMESPACE, namespaceId.toString(), PermissionRelation.MEMBER)
        }
    }

    // -------------------------------------------------------------------------
    // Removal of unlisted namespaces
    // -------------------------------------------------------------------------

    "syncUserRoles revokes ADMIN on namespace not in request" {
        val unlistedId = UUID.randomUUID()
        every { userService.findByExternalId(user.externalId) } returns user
        every { namespaceService.findByExternalIds(listOf(namespace.externalId!!)) } returns listOf(namespace)
        stubCurrentRoles(
            adminIds = listOf(namespaceId.toString(), unlistedId.toString()),
            readIds = listOf(namespaceId.toString(), unlistedId.toString()),
        )
        every { permissionService.revokePermission(any(), any(), any(), any()) } just Runs

        service.syncUserRoles(
            SyncUserRolesRequest(user.externalId, listOf(NamespaceRoleEntry(namespace.externalId!!, "ADMIN"))),
        )

        verify(exactly = 0) { permissionService.grantPermission(any(), any(), any(), any()) }
        verify(exactly = 0) { permissionService.revokePermission(userId.toString(), EntityType.NAMESPACE, namespaceId.toString(), any()) }
        verify(exactly = 1) { permissionService.revokePermission(userId.toString(), EntityType.NAMESPACE, unlistedId.toString(), PermissionRelation.ADMIN) }
    }

    "syncUserRoles revokes MEMBER on namespace not in request" {
        val unlistedId = UUID.randomUUID()
        every { userService.findByExternalId(user.externalId) } returns user
        every { namespaceService.findByExternalIds(listOf(namespace.externalId!!)) } returns listOf(namespace)
        stubCurrentRoles(
            adminIds = listOf(namespaceId.toString()),
            readIds = listOf(namespaceId.toString(), unlistedId.toString()),
        )
        every { permissionService.revokePermission(any(), any(), any(), any()) } just Runs

        service.syncUserRoles(
            SyncUserRolesRequest(user.externalId, listOf(NamespaceRoleEntry(namespace.externalId!!, "ADMIN"))),
        )

        verify(exactly = 0) { permissionService.grantPermission(any(), any(), any(), any()) }
        verify(exactly = 1) { permissionService.revokePermission(userId.toString(), EntityType.NAMESPACE, unlistedId.toString(), PermissionRelation.MEMBER) }
    }

    // -------------------------------------------------------------------------
    // Multiple assignments
    // -------------------------------------------------------------------------

    "syncUserRoles handles multiple assignments in one batch call" {
        val ns2ExternalId = "ns-ext-2"
        val ns2Id = UUID.randomUUID()
        val ns2 = Namespace(metadata = EntityMetadata(id = ns2Id), name = "frontend", externalId = ns2ExternalId)
        every { userService.findByExternalId(user.externalId) } returns user
        every {
            namespaceService.findByExternalIds(match { it.containsAll(listOf(namespace.externalId!!, ns2ExternalId)) })
        } returns listOf(namespace, ns2)
        stubCurrentRoles()
        every { permissionService.grantPermission(any(), any(), any(), any()) } just Runs

        service.syncUserRoles(
            SyncUserRolesRequest(
                user.externalId,
                listOf(
                    NamespaceRoleEntry(namespace.externalId!!, "ADMIN"),
                    NamespaceRoleEntry(ns2ExternalId, "MEMBER"),
                ),
            ),
        )

        verify(exactly = 1) { namespaceService.findByExternalIds(any()) }
        verify(exactly = 1) { permissionService.grantPermission(userId.toString(), EntityType.NAMESPACE, namespaceId.toString(), PermissionRelation.ADMIN) }
        verify(exactly = 1) { permissionService.grantPermission(userId.toString(), EntityType.NAMESPACE, ns2Id.toString(), PermissionRelation.MEMBER) }
        verify(exactly = 0) { permissionService.revokePermission(any(), any(), any(), any()) }
    }
})
