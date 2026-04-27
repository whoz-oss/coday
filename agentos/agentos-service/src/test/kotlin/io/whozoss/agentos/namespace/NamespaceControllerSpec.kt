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
import io.whozoss.agentos.permissions.PermissionRelation
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import java.util.UUID

/**
 * Unit tests for [NamespaceController].
 *
 * Authorization is now declarative (`@PreAuthorize`) and only fires through Spring AOP.
 * Pure unit tests bypass the proxy, so authorization paths are NOT exercised here —
 * they are covered by [io.whozoss.agentos.security.declarative.MethodSecurityIntegrationSpec].
 *
 * What this spec covers:
 * - Mapping (toResource / toDomain, blank-configPath normalisation)
 * - `listAll` permission-filtered listing ( — branches on `User.isAdmin`,
 *   uses `listEntitiesForUser` to avoid N+1)
 * - `create` auto-grants ADMIN to the creator
 * - `delete` cascade-revokes ADMIN/MEMBER relations before service.delete
 * - `update` 404-on-missing path
 */
class NamespaceControllerSpec : StringSpec({
    val namespaceService = mockk<NamespaceService>()
    val userService = mockk<UserService>()
    val permissionService = mockk<PermissionService>()
    val controller = NamespaceController(namespaceService, userService, permissionService)

    val superAdminId = UUID.randomUUID()
    val superAdmin = User(
        metadata = EntityMetadata(id = superAdminId),
        externalId = "super@example.com",
        email = "super@example.com",
        isAdmin = true,
    )
    val regularUserId = UUID.randomUUID()
    val regularUser = User(
        metadata = EntityMetadata(id = regularUserId),
        externalId = "user@example.com",
        email = "user@example.com",
        isAdmin = false,
    )

    fun ns(
        id: UUID = UUID.randomUUID(),
        name: String = "engineering",
        description: String? = null,
        configPath: String? = null,
    ) = Namespace(
        metadata = EntityMetadata(id = id),
        name = name,
        description = description,
        configPath = configPath,
    )

    fun resource(
        id: UUID? = UUID.randomUUID(),
        name: String = "engineering",
        description: String? = null,
        configPath: String? = null,
    ) = NamespaceResource(
        id = id,
        name = name,
        description = description,
        configPath = configPath,
    )

    beforeTest { clearAllMocks() }

    // -------------------------------------------------------------------------
    // Mapping
    // -------------------------------------------------------------------------

    "toResource maps all fields including configPath" {
        val id = UUID.randomUUID()
        controller.toResource(ns(id = id, name = "coday", description = "Coday project", configPath = "/opt/coday")) shouldBe
            NamespaceResource(id = id, name = "coday", description = "Coday project", configPath = "/opt/coday")
    }

    "toResource preserves null configPath" {
        controller.toResource(ns(configPath = null)).configPath shouldBe null
    }

    "toDomain normalizes blank configPath to null" {
        controller.toDomain(resource(configPath = "   ")).configPath shouldBe null
    }

    "toDomain normalizes empty string configPath to null" {
        controller.toDomain(resource(configPath = "")).configPath shouldBe null
    }

    "toDomain generates a random UUID when resource id is null" {
        val first = controller.toDomain(resource(id = null))
        val second = controller.toDomain(resource(id = null))
        (first.metadata.id == second.metadata.id) shouldBe false
    }

    // -------------------------------------------------------------------------
    // listAll — permission-filtered
    // -------------------------------------------------------------------------

    "listAll returns all namespaces with role=SUPER-ADMIN for a super-admin caller" {
        val n1 = ns(name = "a")
        val n2 = ns(name = "b")
        every { userService.getCurrentUser() } returns superAdmin
        every { namespaceService.findAll() } returns listOf(n1, n2)

        val result = controller.listAll()

        result.map { it.role } shouldBe listOf("SUPER-ADMIN", "SUPER-ADMIN")
    }

    "listAll returns empty list for a regular user with no permissions" {
        every { userService.getCurrentUser() } returns regularUser
        every {
            permissionService.listEntitiesForUser(regularUserId.toString(), "Namespace", Action.READ)
        } returns emptyList()

        controller.listAll() shouldBe emptyList()
    }

    "listAll assigns role=ADMIN for namespaces in the WRITE set, role=MEMBER otherwise" {
        val adminNs = ns(name = "admin-ns")
        val memberNs = ns(name = "member-ns")
        every { userService.getCurrentUser() } returns regularUser
        every {
            permissionService.listEntitiesForUser(regularUserId.toString(), "Namespace", Action.READ)
        } returns listOf(adminNs.id.toString(), memberNs.id.toString())
        every { namespaceService.findByIds(listOf(adminNs.id, memberNs.id)) } returns listOf(adminNs, memberNs)
        every {
            permissionService.listEntitiesForUser(regularUserId.toString(), "Namespace", Action.WRITE)
        } returns listOf(adminNs.id.toString())

        val result = controller.listAll().associate { it.id to it.role }

        result[adminNs.id] shouldBe "ADMIN"
        result[memberNs.id] shouldBe "MEMBER"
    }

    "listAll filters out malformed UUID strings from listEntitiesForUser defensively" {
        val n1 = ns(name = "valid")
        every { userService.getCurrentUser() } returns regularUser
        every {
            permissionService.listEntitiesForUser(regularUserId.toString(), "Namespace", Action.READ)
        } returns listOf(n1.id.toString(), "not-a-uuid")
        every { namespaceService.findByIds(listOf(n1.id)) } returns listOf(n1)
        every {
            permissionService.listEntitiesForUser(regularUserId.toString(), "Namespace", Action.WRITE)
        } returns emptyList()

        controller.listAll().map { it.id } shouldBe listOf(n1.id)
    }

    // -------------------------------------------------------------------------
    // create — auto-grant ADMIN
    // -------------------------------------------------------------------------

    "create auto-grants ADMIN on the new namespace to the creator" {
        val r = resource(id = null, name = "new-namespace")
        val savedEntity = ns(name = "new-namespace")
        every { userService.getCurrentUser() } returns superAdmin
        every { namespaceService.create(any()) } returns savedEntity
        every { permissionService.grantPermission(any(), any(), any(), any()) } just Runs

        controller.create(r).id shouldBe savedEntity.id

        verify(exactly = 1) {
            permissionService.grantPermission(
                superAdminId.toString(), "Namespace", savedEntity.id.toString(), PermissionRelation.ADMIN,
            )
        }
    }

    "create still succeeds when auto-grant ADMIN fails (logs warning, no rollback)" {
        val r = resource(id = null, name = "new-namespace")
        val savedEntity = ns(name = "new-namespace")
        every { userService.getCurrentUser() } returns superAdmin
        every { namespaceService.create(any()) } returns savedEntity
        every {
            permissionService.grantPermission(any(), any(), any(), any())
        } throws RuntimeException("transient Neo4j failure")

        controller.create(r).id shouldBe savedEntity.id
        verify(exactly = 1) { namespaceService.create(any()) }
    }

    // -------------------------------------------------------------------------
    // update — 404-on-missing path
    // -------------------------------------------------------------------------

    "update throws 404 when namespace not found" {
        val id = UUID.randomUUID()
        every { namespaceService.findById(id) } returns null

        shouldThrow<ResourceNotFoundException> { controller.update(id, resource(id = id)) }
    }

    // -------------------------------------------------------------------------
    // delete — cascade revoke
    // -------------------------------------------------------------------------

    "delete cascade-revokes ADMIN and MEMBER relations BEFORE service.delete" {
        val entity = ns()
        val userA = UUID.randomUUID().toString()
        val userB = UUID.randomUUID().toString()
        every { namespaceService.findById(entity.id) } returns entity
        every { userService.getCurrentUser() } returns superAdmin
        every { namespaceService.delete(entity.id) } returns true
        every {
            permissionService.listUsersWithPermission("Namespace", entity.id.toString(), null)
        } returns listOf(userA, userB)
        every { permissionService.revokePermission(any(), any(), any(), any()) } just Runs

        controller.delete(entity.id)

        verify(exactly = 1) { namespaceService.delete(entity.id) }
        listOf(userA, userB).forEach { uid ->
            verify(exactly = 1) {
                permissionService.revokePermission(uid, "Namespace", entity.id.toString(), PermissionRelation.ADMIN)
            }
            verify(exactly = 1) {
                permissionService.revokePermission(uid, "Namespace", entity.id.toString(), PermissionRelation.MEMBER)
            }
        }
    }

    "delete dedups affected users (cascade loops each user only once)" {
        val entity = ns()
        val userA = UUID.randomUUID().toString()
        every { namespaceService.findById(entity.id) } returns entity
        every { userService.getCurrentUser() } returns superAdmin
        every { namespaceService.delete(entity.id) } returns true
        every {
            permissionService.listUsersWithPermission("Namespace", entity.id.toString(), null)
        } returns listOf(userA, userA)
        every { permissionService.revokePermission(any(), any(), any(), any()) } just Runs

        controller.delete(entity.id)

        verify(exactly = 1) {
            permissionService.revokePermission(userA, "Namespace", entity.id.toString(), PermissionRelation.ADMIN)
        }
        verify(exactly = 1) {
            permissionService.revokePermission(userA, "Namespace", entity.id.toString(), PermissionRelation.MEMBER)
        }
    }

    "delete throws 404 when namespace not found" {
        val id = UUID.randomUUID()
        every { namespaceService.findById(id) } returns null

        shouldThrow<ResourceNotFoundException> { controller.delete(id) }
        verify(exactly = 0) { permissionService.revokePermission(any(), any(), any(), any()) }
    }

    "delete re-raises when listUsersWithPermission fails (no orphan relations)" {
        val entity = ns()
        every { namespaceService.findById(entity.id) } returns entity
        every { userService.getCurrentUser() } returns superAdmin
        every {
            permissionService.listUsersWithPermission("Namespace", entity.id.toString(), null)
        } throws RuntimeException("neo4j down")

        shouldThrow<RuntimeException> { controller.delete(entity.id) }

        verify(exactly = 0) { namespaceService.delete(any()) }
        verify(exactly = 0) { permissionService.revokePermission(any(), any(), any(), any()) }
    }

    "delete continues cascade when an individual revoke fails" {
        val entity = ns()
        val userA = UUID.randomUUID().toString()
        val userB = UUID.randomUUID().toString()
        every { namespaceService.findById(entity.id) } returns entity
        every { userService.getCurrentUser() } returns superAdmin
        every { namespaceService.delete(entity.id) } returns true
        every {
            permissionService.listUsersWithPermission("Namespace", entity.id.toString(), null)
        } returns listOf(userA, userB)
        every {
            permissionService.revokePermission(userA, "Namespace", entity.id.toString(), PermissionRelation.ADMIN)
        } throws RuntimeException("transient")
        every {
            permissionService.revokePermission(userA, "Namespace", entity.id.toString(), PermissionRelation.MEMBER)
        } just Runs
        every {
            permissionService.revokePermission(userB, "Namespace", entity.id.toString(), PermissionRelation.ADMIN)
        } just Runs
        every {
            permissionService.revokePermission(userB, "Namespace", entity.id.toString(), PermissionRelation.MEMBER)
        } just Runs

        controller.delete(entity.id)

        verify(exactly = 1) {
            permissionService.revokePermission(userB, "Namespace", entity.id.toString(), PermissionRelation.ADMIN)
        }
        verify(exactly = 1) {
            permissionService.revokePermission(userB, "Namespace", entity.id.toString(), PermissionRelation.MEMBER)
        }
    }
})
