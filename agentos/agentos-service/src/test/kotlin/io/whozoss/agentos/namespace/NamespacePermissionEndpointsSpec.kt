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
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Unit tests for [NamespacePermissionEndpoints] (Story 2.2).
 *
 * Covers:
 * - PUT grant: permission delegated to [PermissionService], 403 when caller lacks WRITE,
 *   404 when namespace or target user does not exist, idempotence, super-admin bypass
 * - DELETE revoke: symmetric to grant, idempotent when relationship does not exist
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
    )

    /**
     * Stub the 3 existence/READ gates of [NamespacePermissionEndpoints.requireNamespaceAdmin]
     * with allow-all defaults. Individual tests override specific gates to assert the
     * 404/403 paths.
     */
    fun stubExistence(hasReadOnNamespace: Boolean = true) {
        every { namespaceService.findById(namespaceId) } returns namespace
        every { userService.findById(targetUserId) } returns target
        every { userService.getCurrentUser() } returns caller
        every {
            permissionService.hasPermission(callerId.toString(), "Namespace", namespaceId.toString(), Action.READ)
        } returns hasReadOnNamespace
    }

    beforeTest {
        clearAllMocks()
    }

    // -------------------------------------------------------------------------
    // PUT — grant ADMIN
    // -------------------------------------------------------------------------

    "PUT grants ADMIN to target user when caller has WRITE on namespace" {
        stubExistence()
        every {
            permissionService.hasPermission(callerId.toString(), "Namespace", namespaceId.toString(), Action.WRITE)
        } returns true
        every {
            permissionService.grantPermission(
                targetUserId.toString(), "Namespace", namespaceId.toString(), PermissionRelation.ADMIN,
            )
        } just Runs

        controller.grantAdmin(namespaceId, targetUserId)

        verify(exactly = 1) {
            permissionService.grantPermission(
                targetUserId.toString(), "Namespace", namespaceId.toString(), PermissionRelation.ADMIN,
            )
        }
    }

    "PUT succeeds for super-admin (hasPermission returns true via bypass)" {
        val superAdmin = caller.copy(isAdmin = true)
        every { namespaceService.findById(namespaceId) } returns namespace
        every { userService.findById(targetUserId) } returns target
        every { userService.getCurrentUser() } returns superAdmin
        every {
            permissionService.hasPermission(callerId.toString(), "Namespace", namespaceId.toString(), Action.READ)
        } returns true
        every {
            permissionService.hasPermission(callerId.toString(), "Namespace", namespaceId.toString(), Action.WRITE)
        } returns true
        every { permissionService.grantPermission(any(), any(), any(), any()) } just Runs

        controller.grantAdmin(namespaceId, targetUserId)

        verify(exactly = 1) {
            permissionService.grantPermission(
                targetUserId.toString(), "Namespace", namespaceId.toString(), PermissionRelation.ADMIN,
            )
        }
    }

    "PUT returns 404 when caller has no READ on namespace (hides existence)" {
        stubExistence(hasReadOnNamespace = false)

        val ex = shouldThrow<ResourceNotFoundException> { controller.grantAdmin(namespaceId, targetUserId) }

        ex.message shouldBe "Namespace not found: $namespaceId"
        // READ check runs before target user check — target user lookup must NOT happen
        verify(exactly = 0) { userService.findById(targetUserId) }
        verify(exactly = 0) { permissionService.grantPermission(any(), any(), any(), any()) }
    }

    "PUT returns 403 when caller lacks WRITE on namespace" {
        stubExistence()
        every {
            permissionService.hasPermission(callerId.toString(), "Namespace", namespaceId.toString(), Action.WRITE)
        } returns false

        val ex = shouldThrow<ResponseStatusException> { controller.grantAdmin(namespaceId, targetUserId) }

        ex.statusCode shouldBe HttpStatus.FORBIDDEN
        ex.reason shouldBe "Namespace ADMIN role required"
        verify(exactly = 0) { permissionService.grantPermission(any(), any(), any(), any()) }
    }

    "PUT returns 404 when namespace not found" {
        every { namespaceService.findById(namespaceId) } returns null

        val ex = shouldThrow<ResourceNotFoundException> { controller.grantAdmin(namespaceId, targetUserId) }

        ex.message shouldBe "Namespace not found: $namespaceId"
        verify(exactly = 0) { userService.findById(any<UUID>()) }
        verify(exactly = 0) { permissionService.grantPermission(any(), any(), any(), any()) }
    }

    "PUT returns 404 when target user not found" {
        every { namespaceService.findById(namespaceId) } returns namespace
        every { userService.getCurrentUser() } returns caller
        every {
            permissionService.hasPermission(callerId.toString(), "Namespace", namespaceId.toString(), Action.READ)
        } returns true
        every { userService.findById(targetUserId) } returns null

        val ex = shouldThrow<ResourceNotFoundException> { controller.grantAdmin(namespaceId, targetUserId) }

        ex.message shouldBe "User not found: $targetUserId"
        // WRITE check must not run when target user is missing
        verify(exactly = 0) {
            permissionService.hasPermission(any(), any(), any(), Action.WRITE)
        }
        verify(exactly = 0) { permissionService.grantPermission(any(), any(), any(), any()) }
    }

    "PUT is idempotent: calling twice delegates to grantPermission each time (MERGE is a no-op)" {
        stubExistence()
        every {
            permissionService.hasPermission(callerId.toString(), "Namespace", namespaceId.toString(), Action.WRITE)
        } returns true
        every { permissionService.grantPermission(any(), any(), any(), any()) } just Runs

        controller.grantAdmin(namespaceId, targetUserId)
        controller.grantAdmin(namespaceId, targetUserId)

        verify(exactly = 2) {
            permissionService.grantPermission(
                targetUserId.toString(), "Namespace", namespaceId.toString(), PermissionRelation.ADMIN,
            )
        }
    }

    // -------------------------------------------------------------------------
    // DELETE — revoke ADMIN
    // -------------------------------------------------------------------------

    "DELETE revokes ADMIN when caller has WRITE on namespace" {
        stubExistence()
        every {
            permissionService.hasPermission(callerId.toString(), "Namespace", namespaceId.toString(), Action.WRITE)
        } returns true
        every {
            permissionService.revokePermission(
                targetUserId.toString(), "Namespace", namespaceId.toString(), PermissionRelation.ADMIN,
            )
        } just Runs

        controller.revokeAdmin(namespaceId, targetUserId)

        verify(exactly = 1) {
            permissionService.revokePermission(
                targetUserId.toString(), "Namespace", namespaceId.toString(), PermissionRelation.ADMIN,
            )
        }
    }

    "DELETE returns 403 when caller lacks WRITE on namespace" {
        stubExistence()
        every {
            permissionService.hasPermission(callerId.toString(), "Namespace", namespaceId.toString(), Action.WRITE)
        } returns false

        val ex = shouldThrow<ResponseStatusException> { controller.revokeAdmin(namespaceId, targetUserId) }

        ex.statusCode shouldBe HttpStatus.FORBIDDEN
        verify(exactly = 0) { permissionService.revokePermission(any(), any(), any(), any()) }
    }

    "DELETE returns 404 when namespace not found" {
        every { namespaceService.findById(namespaceId) } returns null

        shouldThrow<ResourceNotFoundException> { controller.revokeAdmin(namespaceId, targetUserId) }

        verify(exactly = 0) { permissionService.revokePermission(any(), any(), any(), any()) }
    }

    "DELETE returns 404 when target user not found" {
        every { namespaceService.findById(namespaceId) } returns namespace
        every { userService.getCurrentUser() } returns caller
        every {
            permissionService.hasPermission(callerId.toString(), "Namespace", namespaceId.toString(), Action.READ)
        } returns true
        every { userService.findById(targetUserId) } returns null

        shouldThrow<ResourceNotFoundException> { controller.revokeAdmin(namespaceId, targetUserId) }

        verify(exactly = 0) { permissionService.revokePermission(any(), any(), any(), any()) }
    }

    "DELETE returns 404 when caller has no READ on namespace (hides existence)" {
        stubExistence(hasReadOnNamespace = false)

        val ex = shouldThrow<ResourceNotFoundException> { controller.revokeAdmin(namespaceId, targetUserId) }

        ex.message shouldBe "Namespace not found: $namespaceId"
        verify(exactly = 0) { userService.findById(targetUserId) }
        verify(exactly = 0) { permissionService.revokePermission(any(), any(), any(), any()) }
    }

    "DELETE is idempotent: revoking a non-existent relation does not throw" {
        stubExistence()
        every {
            permissionService.hasPermission(callerId.toString(), "Namespace", namespaceId.toString(), Action.WRITE)
        } returns true
        // revokePermission is a no-op when the relationship does not exist (Neo4j DELETE on absent pattern)
        every { permissionService.revokePermission(any(), any(), any(), any()) } just Runs

        controller.revokeAdmin(namespaceId, targetUserId)
        controller.revokeAdmin(namespaceId, targetUserId)

        verify(exactly = 2) {
            permissionService.revokePermission(
                targetUserId.toString(), "Namespace", namespaceId.toString(), PermissionRelation.ADMIN,
            )
        }
    }

    // -------------------------------------------------------------------------
    // PUT members — grant MEMBER (Story 2.3)
    // -------------------------------------------------------------------------

    "PUT members grants MEMBER to target user when caller has WRITE on namespace" {
        stubExistence()
        every {
            permissionService.hasPermission(callerId.toString(), "Namespace", namespaceId.toString(), Action.WRITE)
        } returns true
        every { permissionService.grantPermission(any(), any(), any(), any()) } just Runs

        controller.grantMember(namespaceId, targetUserId)

        verify(exactly = 1) {
            permissionService.grantPermission(
                targetUserId.toString(), "Namespace", namespaceId.toString(), PermissionRelation.MEMBER,
            )
        }
    }

    "PUT members succeeds for super-admin" {
        val superAdmin = caller.copy(isAdmin = true)
        every { namespaceService.findById(namespaceId) } returns namespace
        every { userService.findById(targetUserId) } returns target
        every { userService.getCurrentUser() } returns superAdmin
        every {
            permissionService.hasPermission(callerId.toString(), "Namespace", namespaceId.toString(), Action.READ)
        } returns true
        every {
            permissionService.hasPermission(callerId.toString(), "Namespace", namespaceId.toString(), Action.WRITE)
        } returns true
        every { permissionService.grantPermission(any(), any(), any(), any()) } just Runs

        controller.grantMember(namespaceId, targetUserId)

        verify(exactly = 1) {
            permissionService.grantPermission(
                targetUserId.toString(), "Namespace", namespaceId.toString(), PermissionRelation.MEMBER,
            )
        }
    }

    "PUT members returns 403 when caller lacks WRITE on namespace" {
        stubExistence()
        every {
            permissionService.hasPermission(callerId.toString(), "Namespace", namespaceId.toString(), Action.WRITE)
        } returns false

        val ex = shouldThrow<ResponseStatusException> { controller.grantMember(namespaceId, targetUserId) }

        ex.statusCode shouldBe HttpStatus.FORBIDDEN
        ex.reason shouldBe "Namespace ADMIN role required"
        verify(exactly = 0) { permissionService.grantPermission(any(), any(), any(), any()) }
    }

    "PUT members returns 404 when caller has no READ on namespace (hides existence)" {
        stubExistence(hasReadOnNamespace = false)

        val ex = shouldThrow<ResourceNotFoundException> { controller.grantMember(namespaceId, targetUserId) }

        ex.message shouldBe "Namespace not found: $namespaceId"
        verify(exactly = 0) { userService.findById(targetUserId) }
        verify(exactly = 0) { permissionService.grantPermission(any(), any(), any(), any()) }
    }

    "PUT members returns 404 when namespace not found" {
        every { namespaceService.findById(namespaceId) } returns null

        val ex = shouldThrow<ResourceNotFoundException> { controller.grantMember(namespaceId, targetUserId) }

        ex.message shouldBe "Namespace not found: $namespaceId"
        verify(exactly = 0) { permissionService.grantPermission(any(), any(), any(), any()) }
    }

    "PUT members returns 404 when target user not found" {
        every { namespaceService.findById(namespaceId) } returns namespace
        every { userService.getCurrentUser() } returns caller
        every {
            permissionService.hasPermission(callerId.toString(), "Namespace", namespaceId.toString(), Action.READ)
        } returns true
        every { userService.findById(targetUserId) } returns null

        val ex = shouldThrow<ResourceNotFoundException> { controller.grantMember(namespaceId, targetUserId) }

        ex.message shouldBe "User not found: $targetUserId"
        verify(exactly = 0) { permissionService.grantPermission(any(), any(), any(), any()) }
    }

    "PUT members is idempotent: repeated grants delegate to grantPermission each time" {
        stubExistence()
        every {
            permissionService.hasPermission(callerId.toString(), "Namespace", namespaceId.toString(), Action.WRITE)
        } returns true
        every { permissionService.grantPermission(any(), any(), any(), any()) } just Runs

        controller.grantMember(namespaceId, targetUserId)
        controller.grantMember(namespaceId, targetUserId)

        verify(exactly = 2) {
            permissionService.grantPermission(
                targetUserId.toString(), "Namespace", namespaceId.toString(), PermissionRelation.MEMBER,
            )
        }
    }

    // -------------------------------------------------------------------------
    // DELETE members — revoke MEMBER (Story 2.3)
    // -------------------------------------------------------------------------

    "DELETE members revokes MEMBER when caller has WRITE on namespace" {
        stubExistence()
        every {
            permissionService.hasPermission(callerId.toString(), "Namespace", namespaceId.toString(), Action.WRITE)
        } returns true
        every { permissionService.revokePermission(any(), any(), any(), any()) } just Runs

        controller.revokeMember(namespaceId, targetUserId)

        verify(exactly = 1) {
            permissionService.revokePermission(
                targetUserId.toString(), "Namespace", namespaceId.toString(), PermissionRelation.MEMBER,
            )
        }
    }

    "DELETE members does NOT revoke ADMIN relation (AC3: preserves higher privilege)" {
        stubExistence()
        every {
            permissionService.hasPermission(callerId.toString(), "Namespace", namespaceId.toString(), Action.WRITE)
        } returns true
        every { permissionService.revokePermission(any(), any(), any(), any()) } just Runs

        controller.revokeMember(namespaceId, targetUserId)

        verify(exactly = 0) {
            permissionService.revokePermission(
                any(), any(), any(), PermissionRelation.ADMIN,
            )
        }
    }

    "DELETE members returns 403 when caller lacks WRITE on namespace" {
        stubExistence()
        every {
            permissionService.hasPermission(callerId.toString(), "Namespace", namespaceId.toString(), Action.WRITE)
        } returns false

        val ex = shouldThrow<ResponseStatusException> { controller.revokeMember(namespaceId, targetUserId) }

        ex.statusCode shouldBe HttpStatus.FORBIDDEN
        verify(exactly = 0) { permissionService.revokePermission(any(), any(), any(), any()) }
    }

    "DELETE members returns 404 when caller has no READ on namespace (hides existence)" {
        stubExistence(hasReadOnNamespace = false)

        val ex = shouldThrow<ResourceNotFoundException> { controller.revokeMember(namespaceId, targetUserId) }

        ex.message shouldBe "Namespace not found: $namespaceId"
        verify(exactly = 0) { permissionService.revokePermission(any(), any(), any(), any()) }
    }

    "DELETE members returns 404 when namespace not found" {
        every { namespaceService.findById(namespaceId) } returns null

        shouldThrow<ResourceNotFoundException> { controller.revokeMember(namespaceId, targetUserId) }

        verify(exactly = 0) { permissionService.revokePermission(any(), any(), any(), any()) }
    }

    "DELETE members returns 404 when target user not found" {
        every { namespaceService.findById(namespaceId) } returns namespace
        every { userService.getCurrentUser() } returns caller
        every {
            permissionService.hasPermission(callerId.toString(), "Namespace", namespaceId.toString(), Action.READ)
        } returns true
        every { userService.findById(targetUserId) } returns null

        shouldThrow<ResourceNotFoundException> { controller.revokeMember(namespaceId, targetUserId) }

        verify(exactly = 0) { permissionService.revokePermission(any(), any(), any(), any()) }
    }

    "DELETE members is idempotent: revoking a non-existent MEMBER relation does not throw" {
        stubExistence()
        every {
            permissionService.hasPermission(callerId.toString(), "Namespace", namespaceId.toString(), Action.WRITE)
        } returns true
        every { permissionService.revokePermission(any(), any(), any(), any()) } just Runs

        controller.revokeMember(namespaceId, targetUserId)
        controller.revokeMember(namespaceId, targetUserId)

        verify(exactly = 2) {
            permissionService.revokePermission(
                targetUserId.toString(), "Namespace", namespaceId.toString(), PermissionRelation.MEMBER,
            )
        }
    }

    // -------------------------------------------------------------------------
    // GET /{namespaceId}/users — list namespace users (Story 2.5)
    // -------------------------------------------------------------------------

    /**
     * Stub the namespace-existence + caller-READ gates for [listNamespaceUsers].
     * Does NOT stub the target user (the endpoint has no target parameter).
     */
    fun stubReadOnNamespace(hasRead: Boolean = true) {
        every { namespaceService.findById(namespaceId) } returns namespace
        every { userService.getCurrentUser() } returns caller
        every {
            permissionService.hasPermission(callerId.toString(), "Namespace", namespaceId.toString(), Action.READ)
        } returns hasRead
    }

    "GET users returns list of namespace users with correct roles" {
        stubReadOnNamespace()
        val adminUser = User(
            metadata = EntityMetadata(id = UUID.randomUUID()),
            externalId = "admin@example.com",
            email = "admin@example.com",
            isAdmin = false,
        )
        val memberUser = User(
            metadata = EntityMetadata(id = UUID.randomUUID()),
            externalId = "member@example.com",
            email = "member@example.com",
            firstname = "Mem",
            lastname = "Ber",
            isAdmin = false,
        )
        every {
            permissionService.listUsersWithPermission("Namespace", namespaceId.toString(), PermissionRelation.ADMIN)
        } returns listOf(adminUser.metadata.id.toString())
        every {
            permissionService.listUsersWithPermission("Namespace", namespaceId.toString(), PermissionRelation.MEMBER)
        } returns listOf(memberUser.metadata.id.toString())
        every { userService.findByIds(any()) } returns listOf(adminUser, memberUser)

        val result = controller.listNamespaceUsers(namespaceId).associateBy { it.id }

        result.size shouldBe 2
        result[adminUser.metadata.id]?.role shouldBe "ADMIN"
        result[memberUser.metadata.id]?.role shouldBe "MEMBER"
        result[memberUser.metadata.id]?.firstname shouldBe "Mem"
    }

    "GET users deduplicates users with both ADMIN and MEMBER relations (role=ADMIN)" {
        stubReadOnNamespace()
        val dualUser = User(
            metadata = EntityMetadata(id = UUID.randomUUID()),
            externalId = "dual@example.com",
            email = "dual@example.com",
            isAdmin = false,
        )
        val dualIdString = dualUser.metadata.id.toString()
        every {
            permissionService.listUsersWithPermission("Namespace", namespaceId.toString(), PermissionRelation.ADMIN)
        } returns listOf(dualIdString)
        every {
            permissionService.listUsersWithPermission("Namespace", namespaceId.toString(), PermissionRelation.MEMBER)
        } returns listOf(dualIdString)
        every { userService.findByIds(any()) } returns listOf(dualUser)

        val result = controller.listNamespaceUsers(namespaceId)

        result.size shouldBe 1
        result.first().role shouldBe "ADMIN"
        result.first().id shouldBe dualUser.metadata.id
    }

    "GET users returns empty list when no user has a direct relation" {
        stubReadOnNamespace()
        every {
            permissionService.listUsersWithPermission("Namespace", namespaceId.toString(), PermissionRelation.ADMIN)
        } returns emptyList()
        every {
            permissionService.listUsersWithPermission("Namespace", namespaceId.toString(), PermissionRelation.MEMBER)
        } returns emptyList()

        controller.listNamespaceUsers(namespaceId) shouldBe emptyList()
        verify(exactly = 0) { userService.findByIds(any()) }
    }

    "GET users returns 404 when namespace not found" {
        every { namespaceService.findById(namespaceId) } returns null

        shouldThrow<ResourceNotFoundException> { controller.listNamespaceUsers(namespaceId) }

        verify(exactly = 0) { permissionService.listUsersWithPermission(any(), any(), any()) }
    }

    "GET users returns 404 when caller has no READ on namespace (hides existence)" {
        stubReadOnNamespace(hasRead = false)

        val ex = shouldThrow<ResourceNotFoundException> { controller.listNamespaceUsers(namespaceId) }

        ex.message shouldBe "Namespace not found: $namespaceId"
        verify(exactly = 0) { permissionService.listUsersWithPermission(any(), any(), any()) }
    }

    "GET users succeeds for super-admin via permission bypass" {
        val superAdmin = caller.copy(isAdmin = true)
        every { namespaceService.findById(namespaceId) } returns namespace
        every { userService.getCurrentUser() } returns superAdmin
        every {
            permissionService.hasPermission(callerId.toString(), "Namespace", namespaceId.toString(), Action.READ)
        } returns true
        every {
            permissionService.listUsersWithPermission("Namespace", namespaceId.toString(), PermissionRelation.ADMIN)
        } returns emptyList()
        every {
            permissionService.listUsersWithPermission("Namespace", namespaceId.toString(), PermissionRelation.MEMBER)
        } returns emptyList()

        controller.listNamespaceUsers(namespaceId) shouldBe emptyList()
    }

    "GET users filters out orphan user ids silently" {
        stubReadOnNamespace()
        val existingUser = User(
            metadata = EntityMetadata(id = UUID.randomUUID()),
            externalId = "exists@example.com",
            email = "exists@example.com",
            isAdmin = false,
        )
        val ghostId = UUID.randomUUID().toString()
        every {
            permissionService.listUsersWithPermission("Namespace", namespaceId.toString(), PermissionRelation.ADMIN)
        } returns listOf(existingUser.metadata.id.toString(), ghostId)
        every {
            permissionService.listUsersWithPermission("Namespace", namespaceId.toString(), PermissionRelation.MEMBER)
        } returns emptyList()
        // Ghost user is not returned by userService — simulates deleted user with lingering relation
        every { userService.findByIds(any()) } returns listOf(existingUser)

        val result = controller.listNamespaceUsers(namespaceId)

        result.map { it.id } shouldBe listOf(existingUser.metadata.id)
    }

    "GET users is defensive against malformed UUID strings in permission relations" {
        stubReadOnNamespace()
        val validUser = User(
            metadata = EntityMetadata(id = UUID.randomUUID()),
            externalId = "valid@example.com",
            email = "valid@example.com",
            isAdmin = false,
        )
        every {
            permissionService.listUsersWithPermission("Namespace", namespaceId.toString(), PermissionRelation.ADMIN)
        } returns listOf("not-a-uuid", validUser.metadata.id.toString())
        every {
            permissionService.listUsersWithPermission("Namespace", namespaceId.toString(), PermissionRelation.MEMBER)
        } returns emptyList()
        every { userService.findByIds(listOf(validUser.metadata.id)) } returns listOf(validUser)

        val result = controller.listNamespaceUsers(namespaceId)

        result.map { it.id } shouldBe listOf(validUser.metadata.id)
        verify(exactly = 1) { userService.findByIds(listOf(validUser.metadata.id)) }
    }
})
