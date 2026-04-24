package io.whozoss.agentos.namespace

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.inspectors.forAll
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Unit tests for [NamespaceController].
 *
 * Covers:
 * - Mapping (toResource / toDomain, including blank configPath normalization)
 * - listAll endpoint (no permission filtering — scope of Story 2.4)
 * - Inherited secured CRUD endpoints (getById, getByIds, update, delete) via
 *   [io.whozoss.agentos.entity.SecuredEntityController] — permission checks mocked
 * - Story 2.1 security overrides:
 *   * create: super-admin only (FR1) + auto-grant ADMIN to creator
 *   * delete: super-admin only (FR2) + cascade revoke of ADMIN/MEMBER relations
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

    beforeTest {
        clearAllMocks()
    }

    // -------------------------------------------------------------------------
    // toResource mapping
    // -------------------------------------------------------------------------

    "toResource maps all fields including configPath" {
        val id = UUID.randomUUID()
        val entity = ns(id = id, name = "coday", description = "Coday project", configPath = "/opt/coday")

        controller.toResource(entity) shouldBe NamespaceResource(
            id = id,
            name = "coday",
            description = "Coday project",
            configPath = "/opt/coday",
        )
    }

    "toResource preserves null configPath" {
        controller.toResource(ns(configPath = null)).configPath shouldBe null
    }

    // -------------------------------------------------------------------------
    // toDomain mapping
    // -------------------------------------------------------------------------

    "toDomain maps all fields from NamespaceResource to Namespace" {
        val id = UUID.randomUUID()
        val r = resource(id = id, name = "platform", description = "Platform team", configPath = "/opt/platform")

        val result = controller.toDomain(r)

        result.metadata.id shouldBe id
        result.name shouldBe "platform"
        result.description shouldBe "Platform team"
        result.configPath shouldBe "/opt/platform"
    }

    "toDomain normalizes blank configPath to null" {
        controller.toDomain(resource(configPath = "   ")).configPath shouldBe null
    }

    "toDomain normalizes empty string configPath to null" {
        controller.toDomain(resource(configPath = "")).configPath shouldBe null
    }

    "toDomain preserves null configPath" {
        controller.toDomain(resource(configPath = null)).configPath shouldBe null
    }

    "toDomain generates a random UUID when resource id is null" {
        val firstId = controller.toDomain(resource(id = null)).metadata.id
        val secondId = controller.toDomain(resource(id = null)).metadata.id

        // Two calls must produce distinct, non-null UUIDs — proving a fresh UUID is
        // generated rather than a default/sentinel value being reused.
        firstId shouldNotBe secondId
    }

    // -------------------------------------------------------------------------
    // getEntityType — must match the Neo4j label
    // -------------------------------------------------------------------------

    "getEntityType returns \"Namespace\" (must match Neo4j label)" {
        controller.getEntityType() shouldBe "Namespace"
    }

    // -------------------------------------------------------------------------
    // listAll (unchanged by Story 2.1 — filtering is Story 2.4 scope)
    // -------------------------------------------------------------------------

    "listAll returns all namespaces with role=SUPER-ADMIN for a super-admin caller" {
        val ns1 = ns(name = "engineering")
        val ns2 = ns(name = "product")
        every { userService.getCurrentUser() } returns superAdmin
        every { namespaceService.findAll() } returns listOf(ns1, ns2)

        val result = controller.listAll()

        result.map { it.id } shouldContainExactlyInAnyOrder listOf(ns1.id, ns2.id)
        result.forAll { it.role shouldBe "SUPER-ADMIN" }
        verify(exactly = 1) { namespaceService.findAll() }
        // Super-admin short-circuits: listEntitiesForUser must NOT be called
        verify(exactly = 0) { permissionService.listEntitiesForUser(any(), any(), any()) }
    }

    "listAll returns empty list for a regular user with no permissions" {
        every { userService.getCurrentUser() } returns regularUser
        every {
            permissionService.listEntitiesForUser(regularUserId.toString(), "Namespace", Action.READ)
        } returns emptyList()

        val result = controller.listAll()

        result shouldBe emptyList()
        // No namespace fetch when READ list is empty
        verify(exactly = 0) { namespaceService.findByIds(any()) }
        // Should not even query WRITE list when READ is empty
        verify(exactly = 0) {
            permissionService.listEntitiesForUser(any(), any(), Action.WRITE)
        }
    }

    "listAll returns accessible namespaces with role=MEMBER when user has only READ" {
        val ns1 = ns(name = "engineering")
        val ns2 = ns(name = "product")
        every { userService.getCurrentUser() } returns regularUser
        every {
            permissionService.listEntitiesForUser(regularUserId.toString(), "Namespace", Action.READ)
        } returns listOf(ns1.id.toString(), ns2.id.toString())
        every {
            permissionService.listEntitiesForUser(regularUserId.toString(), "Namespace", Action.WRITE)
        } returns emptyList()
        every { namespaceService.findByIds(any()) } returns listOf(ns1, ns2)

        val result = controller.listAll()

        result.map { it.id } shouldContainExactlyInAnyOrder listOf(ns1.id, ns2.id)
        result.forAll { it.role shouldBe "MEMBER" }
    }

    "listAll assigns role=ADMIN for namespaces in the WRITE set, role=MEMBER otherwise" {
        val nsAdmin = ns(name = "admin-ns")
        val nsMember = ns(name = "member-ns")
        every { userService.getCurrentUser() } returns regularUser
        every {
            permissionService.listEntitiesForUser(regularUserId.toString(), "Namespace", Action.READ)
        } returns listOf(nsAdmin.id.toString(), nsMember.id.toString())
        every {
            permissionService.listEntitiesForUser(regularUserId.toString(), "Namespace", Action.WRITE)
        } returns listOf(nsAdmin.id.toString())
        every { namespaceService.findByIds(any()) } returns listOf(nsAdmin, nsMember)

        val result = controller.listAll().associateBy { it.id }

        result[nsAdmin.id]?.role shouldBe "ADMIN"
        result[nsMember.id]?.role shouldBe "MEMBER"
    }

    "listAll filters out malformed UUID strings from listEntitiesForUser defensively" {
        val goodNs = ns(name = "good")
        every { userService.getCurrentUser() } returns regularUser
        every {
            permissionService.listEntitiesForUser(regularUserId.toString(), "Namespace", Action.READ)
        } returns listOf("not-a-uuid", goodNs.id.toString())
        every {
            permissionService.listEntitiesForUser(regularUserId.toString(), "Namespace", Action.WRITE)
        } returns emptyList()
        every { namespaceService.findByIds(listOf(goodNs.id)) } returns listOf(goodNs)

        val result = controller.listAll()

        result.map { it.id } shouldBe listOf(goodNs.id)
        verify(exactly = 1) { namespaceService.findByIds(listOf(goodNs.id)) }
    }

    "listAll never calls hasPermission per namespace (no N+1)" {
        val ns1 = ns(name = "a")
        val ns2 = ns(name = "b")
        every { userService.getCurrentUser() } returns regularUser
        every {
            permissionService.listEntitiesForUser(regularUserId.toString(), "Namespace", Action.READ)
        } returns listOf(ns1.id.toString(), ns2.id.toString())
        every {
            permissionService.listEntitiesForUser(regularUserId.toString(), "Namespace", Action.WRITE)
        } returns emptyList()
        every { namespaceService.findByIds(any()) } returns listOf(ns1, ns2)

        controller.listAll()

        verify(exactly = 0) { permissionService.hasPermission(any(), any(), any(), any()) }
    }

    // -------------------------------------------------------------------------
    // getById (inherited from SecuredEntityController)
    // -------------------------------------------------------------------------

    "getById returns a NamespaceResource when namespace is found and READ permission granted" {
        val entity = ns()
        every { namespaceService.findById(entity.id) } returns entity
        every { userService.getCurrentUser() } returns regularUser
        every {
            permissionService.hasPermission(
                regularUserId.toString(), "Namespace", entity.id.toString(), Action.READ,
            )
        } returns true

        controller.getById(entity.id) shouldBe controller.toResource(entity)
    }

    "getById throws 404 when namespace not found" {
        val id = UUID.randomUUID()
        every { namespaceService.findById(id) } returns null

        shouldThrow<ResourceNotFoundException> { controller.getById(id) }
    }

    "getById throws 404 when user lacks READ permission (hides existence)" {
        val entity = ns()
        every { namespaceService.findById(entity.id) } returns entity
        every { userService.getCurrentUser() } returns regularUser
        every {
            permissionService.hasPermission(
                regularUserId.toString(), "Namespace", entity.id.toString(), Action.READ,
            )
        } returns false

        shouldThrow<ResourceNotFoundException> { controller.getById(entity.id) }
    }

    // -------------------------------------------------------------------------
    // getByIds (inherited) — filters out entities without READ permission
    // -------------------------------------------------------------------------

    "getByIds returns only entities the user has READ permission on" {
        val ns1 = ns(name = "engineering")
        val ns2 = ns(name = "product")
        every { namespaceService.findByIds(listOf(ns1.id, ns2.id)) } returns listOf(ns1, ns2)
        every { userService.getCurrentUser() } returns regularUser
        every {
            permissionService.hasPermission(
                regularUserId.toString(), "Namespace", ns1.id.toString(), Action.READ,
            )
        } returns true
        every {
            permissionService.hasPermission(
                regularUserId.toString(), "Namespace", ns2.id.toString(), Action.READ,
            )
        } returns false

        controller.getByIds(listOf(ns1.id, ns2.id)) shouldBe listOf(controller.toResource(ns1))
    }

    // -------------------------------------------------------------------------
    // create (Story 2.1)
    // -------------------------------------------------------------------------

    "create succeeds for super-admin and auto-grants ADMIN on the new namespace" {
        val r = resource(id = null, name = "new-namespace")
        val savedEntity = ns(name = "new-namespace")
        every { userService.getCurrentUser() } returns superAdmin
        every { userService.findById(superAdminId) } returns superAdmin
        every { namespaceService.create(any()) } returns savedEntity
        every {
            permissionService.grantPermission(
                superAdminId.toString(), "Namespace", savedEntity.id.toString(), PermissionRelation.ADMIN,
            )
        } just Runs

        val result = controller.create(r)

        result.id shouldBe savedEntity.id
        verify(exactly = 1) { namespaceService.create(any()) }
        verify(exactly = 1) {
            permissionService.grantPermission(
                superAdminId.toString(), "Namespace", savedEntity.id.toString(), PermissionRelation.ADMIN,
            )
        }
    }

    "create throws 403 \"SUPER-ADMIN role required\" for a non-super-admin user" {
        val r = resource(id = null, name = "new-namespace")
        every { userService.getCurrentUser() } returns regularUser
        every { userService.findById(regularUserId) } returns regularUser

        val ex = shouldThrow<ResponseStatusException> { controller.create(r) }
        ex.statusCode shouldBe HttpStatus.FORBIDDEN
        ex.reason shouldBe "SUPER-ADMIN role required"
        verify(exactly = 0) { namespaceService.create(any()) }
        verify(exactly = 0) {
            permissionService.grantPermission(any(), any(), any(), any())
        }
    }

    "create throws 403 when userService.findById returns null for the caller (edge)" {
        val r = resource(id = null, name = "new-namespace")
        every { userService.getCurrentUser() } returns superAdmin
        every { userService.findById(superAdminId) } returns null

        val ex = shouldThrow<ResponseStatusException> { controller.create(r) }
        ex.statusCode shouldBe HttpStatus.FORBIDDEN
        verify(exactly = 0) { namespaceService.create(any()) }
    }

    "create still succeeds when auto-grant ADMIN fails (logs warning, no rollback)" {
        val r = resource(id = null, name = "new-namespace")
        val savedEntity = ns(name = "new-namespace")
        every { userService.getCurrentUser() } returns superAdmin
        every { userService.findById(superAdminId) } returns superAdmin
        every { namespaceService.create(any()) } returns savedEntity
        every {
            permissionService.grantPermission(
                superAdminId.toString(), "Namespace", savedEntity.id.toString(), PermissionRelation.ADMIN,
            )
        } throws RuntimeException("grant failed")

        val result = controller.create(r)

        result.id shouldBe savedEntity.id
        verify(exactly = 1) { namespaceService.create(any()) }
    }

    // -------------------------------------------------------------------------
    // update (inherited, checks WRITE permission)
    // -------------------------------------------------------------------------

    "update succeeds when user has WRITE permission (namespace ADMIN)" {
        val entity = ns()
        val updatedResource = resource(id = entity.id, name = "renamed", configPath = "/new/path")
        val updatedDomain = controller.toDomain(updatedResource)
        every { namespaceService.findById(entity.id) } returns entity
        every { namespaceService.update(any()) } returns updatedDomain
        every { userService.getCurrentUser() } returns regularUser
        every {
            permissionService.hasPermission(
                regularUserId.toString(), "Namespace", entity.id.toString(), Action.WRITE,
            )
        } returns true

        controller.update(entity.id, updatedResource).name shouldBe "renamed"
    }

    "update throws 403 when user lacks WRITE permission" {
        val entity = ns()
        val r = resource(id = entity.id)
        every { namespaceService.findById(entity.id) } returns entity
        every { userService.getCurrentUser() } returns regularUser
        every {
            permissionService.hasPermission(
                regularUserId.toString(), "Namespace", entity.id.toString(), Action.WRITE,
            )
        } returns false

        val ex = shouldThrow<ResponseStatusException> { controller.update(entity.id, r) }
        ex.statusCode shouldBe HttpStatus.FORBIDDEN
    }

    "update throws 404 when namespace not found" {
        val id = UUID.randomUUID()
        every { userService.getCurrentUser() } returns regularUser
        every { namespaceService.findById(id) } returns null

        shouldThrow<ResourceNotFoundException> { controller.update(id, resource(id = id)) }
    }

    // -------------------------------------------------------------------------
    // delete (Story 2.1) — super-admin only, cascade permissions
    // -------------------------------------------------------------------------

    "delete succeeds for super-admin and cascade-revokes ADMIN and MEMBER relations (cascade before delete)" {
        val entity = ns()
        val userA = UUID.randomUUID().toString()
        val userB = UUID.randomUUID().toString()
        every { namespaceService.findById(entity.id) } returns entity
        every { userService.getCurrentUser() } returns superAdmin
        every {
            permissionService.hasPermission(superAdminId.toString(), "Namespace", entity.id.toString(), Action.READ)
        } returns true
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
        every {
            permissionService.hasPermission(superAdminId.toString(), "Namespace", entity.id.toString(), Action.READ)
        } returns true
        every { namespaceService.delete(entity.id) } returns true
        // User with both ADMIN and MEMBER relations appears twice in the raw listing
        every {
            permissionService.listUsersWithPermission("Namespace", entity.id.toString(), null)
        } returns listOf(userA, userA)
        every { permissionService.revokePermission(any(), any(), any(), any()) } just Runs

        controller.delete(entity.id)

        // dedup: revoke ADMIN called once, revoke MEMBER called once — not twice
        verify(exactly = 1) {
            permissionService.revokePermission(userA, "Namespace", entity.id.toString(), PermissionRelation.ADMIN)
        }
        verify(exactly = 1) {
            permissionService.revokePermission(userA, "Namespace", entity.id.toString(), PermissionRelation.MEMBER)
        }
    }

    "delete succeeds with no affected users (empty cascade)" {
        val entity = ns()
        every { namespaceService.findById(entity.id) } returns entity
        every { userService.getCurrentUser() } returns superAdmin
        every {
            permissionService.hasPermission(superAdminId.toString(), "Namespace", entity.id.toString(), Action.READ)
        } returns true
        every { namespaceService.delete(entity.id) } returns true
        every {
            permissionService.listUsersWithPermission("Namespace", entity.id.toString(), null)
        } returns emptyList()

        controller.delete(entity.id)

        verify(exactly = 1) { namespaceService.delete(entity.id) }
        verify(exactly = 0) { permissionService.revokePermission(any(), any(), any(), any()) }
    }

    "delete throws 403 for non-super-admin who has READ (namespace ADMIN but not super-admin)" {
        val entity = ns()
        every { namespaceService.findById(entity.id) } returns entity
        every { userService.getCurrentUser() } returns regularUser
        every {
            permissionService.hasPermission(regularUserId.toString(), "Namespace", entity.id.toString(), Action.READ)
        } returns true

        val ex = shouldThrow<ResponseStatusException> { controller.delete(entity.id) }
        ex.statusCode shouldBe HttpStatus.FORBIDDEN
        ex.reason shouldBe "SUPER-ADMIN role required"
        verify(exactly = 0) { namespaceService.delete(any()) }
        verify(exactly = 0) { permissionService.revokePermission(any(), any(), any(), any()) }
    }

    "delete throws 404 when caller has no READ permission (hides namespace existence)" {
        val entity = ns()
        every { namespaceService.findById(entity.id) } returns entity
        every { userService.getCurrentUser() } returns regularUser
        every {
            permissionService.hasPermission(regularUserId.toString(), "Namespace", entity.id.toString(), Action.READ)
        } returns false

        val ex = shouldThrow<ResourceNotFoundException> { controller.delete(entity.id) }
        ex.message shouldBe "Entity not found: ${entity.id}"
        verify(exactly = 0) { namespaceService.delete(any()) }
        verify(exactly = 0) { permissionService.listUsersWithPermission(any(), any(), any()) }
    }

    "delete throws 404 when namespace not found" {
        val id = UUID.randomUUID()
        every { namespaceService.findById(id) } returns null

        shouldThrow<ResourceNotFoundException> { controller.delete(id) }
        verify(exactly = 0) { permissionService.revokePermission(any(), any(), any(), any()) }
    }

    "delete throws 404 when service.delete returns false (concurrent delete race)" {
        val entity = ns()
        every { namespaceService.findById(entity.id) } returns entity
        every { userService.getCurrentUser() } returns superAdmin
        every {
            permissionService.hasPermission(superAdminId.toString(), "Namespace", entity.id.toString(), Action.READ)
        } returns true
        every {
            permissionService.listUsersWithPermission("Namespace", entity.id.toString(), null)
        } returns emptyList()
        every { namespaceService.delete(entity.id) } returns false

        val ex = shouldThrow<ResourceNotFoundException> { controller.delete(entity.id) }
        ex.message shouldBe "Entity not found: ${entity.id}"
    }

    "delete re-raises when listUsersWithPermission fails (cascade before delete, no orphans)" {
        val entity = ns()
        every { namespaceService.findById(entity.id) } returns entity
        every { userService.getCurrentUser() } returns superAdmin
        every {
            permissionService.hasPermission(superAdminId.toString(), "Namespace", entity.id.toString(), Action.READ)
        } returns true
        every {
            permissionService.listUsersWithPermission("Namespace", entity.id.toString(), null)
        } throws RuntimeException("neo4j down")

        shouldThrow<RuntimeException> { controller.delete(entity.id) }

        // namespace MUST NOT be deleted if cascade cannot be attempted
        verify(exactly = 0) { namespaceService.delete(any()) }
        verify(exactly = 0) { permissionService.revokePermission(any(), any(), any(), any()) }
    }

    "delete continues cascade when an individual revoke fails" {
        val entity = ns()
        val userA = UUID.randomUUID().toString()
        val userB = UUID.randomUUID().toString()
        every { namespaceService.findById(entity.id) } returns entity
        every { userService.getCurrentUser() } returns superAdmin
        every {
            permissionService.hasPermission(superAdminId.toString(), "Namespace", entity.id.toString(), Action.READ)
        } returns true
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
