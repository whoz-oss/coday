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
import io.whozoss.agentos.auth.RoleRepository
import io.whozoss.agentos.sdk.auth.NamespaceRole
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import java.util.UUID

class NamespaceControllerSpec : StringSpec({
    val namespaceService = mockk<NamespaceService>()
    val authorizationService = mockk<AuthorizationService>()
    val userService = mockk<UserService>()
    val roleRepository = mockk<RoleRepository>()
    val controller = NamespaceController(namespaceService, authorizationService, userService, roleRepository)

    val currentUserId = UUID.randomUUID()
    val currentUser = User(metadata = EntityMetadata(id = currentUserId), externalId = "admin@test.com")
    val nsId = UUID.randomUUID()
    val namespace = Namespace(metadata = EntityMetadata(id = nsId), name = "test-ns")

    beforeEach {
        clearMocks(namespaceService, authorizationService, userService, roleRepository)
        every { userService.getCurrentUser() } returns currentUser
    }

    // -------------------------------------------------------------------------
    // listAll
    // -------------------------------------------------------------------------

    "listAll returns only accessible namespaces" {
        val ns2Id = UUID.randomUUID()
        val ns2 = Namespace(metadata = EntityMetadata(id = ns2Id), name = "ns-2")
        every { authorizationService.filterAccessibleNamespaceIds(currentUserId.toString()) } returns setOf(nsId.toString())
        every { namespaceService.findAll() } returns listOf(namespace, ns2)

        val result = controller.listAll()

        result shouldHaveSize 1
        result[0].name shouldBe "test-ns"
    }

    // -------------------------------------------------------------------------
    // getById
    // -------------------------------------------------------------------------

    "getById succeeds when access granted" {
        every { authorizationService.requireNamespaceAccess(any(), any(), any()) } just Runs
        every { namespaceService.findById(nsId) } returns namespace

        val result = controller.getById(nsId)

        result.name shouldBe "test-ns"
        verify { authorizationService.requireNamespaceAccess(currentUserId.toString(), nsId.toString(), NamespaceRole.VIEWER) }
    }

    "getById throws 403 when access denied" {
        every {
            authorizationService.requireNamespaceAccess(any(), any(), any())
        } throws AccessDeniedException("Access denied", requiredRole = "VIEWER")

        shouldThrow<AccessDeniedException> { controller.getById(nsId) }
    }

    // -------------------------------------------------------------------------
    // create + auto-assign OWNER
    // -------------------------------------------------------------------------

    "create auto-assigns OWNER to creator" {
        every { namespaceService.create(any()) } returns namespace
        every { roleRepository.assignNamespaceRole(any(), any(), any(), any()) } just Runs

        val result = controller.create(NamespaceResource(name = "test-ns"))

        result.name shouldBe "test-ns"
        verify(exactly = 1) {
            roleRepository.assignNamespaceRole(currentUserId.toString(), nsId.toString(), NamespaceRole.OWNER, currentUserId.toString())
        }
    }

    // -------------------------------------------------------------------------
    // update
    // -------------------------------------------------------------------------

    "update requires ADMIN access" {
        every { authorizationService.requireNamespaceAccess(any(), any(), any()) } just Runs
        every { namespaceService.findById(nsId) } returns namespace
        every { namespaceService.update(any()) } returns namespace

        controller.update(nsId, NamespaceResource(id = nsId, name = "updated"))

        verify { authorizationService.requireNamespaceAccess(currentUserId.toString(), nsId.toString(), NamespaceRole.ADMIN) }
    }

    // -------------------------------------------------------------------------
    // delete
    // -------------------------------------------------------------------------

    "delete requires OWNER access" {
        every { authorizationService.requireNamespaceAccess(any(), any(), any()) } just Runs
        every { namespaceService.delete(nsId) } returns true

        controller.delete(nsId)

        verify { authorizationService.requireNamespaceAccess(currentUserId.toString(), nsId.toString(), NamespaceRole.OWNER) }
    }

    "delete throws 403 when not OWNER" {
        every {
            authorizationService.requireNamespaceAccess(any(), any(), any())
        } throws AccessDeniedException("Not OWNER", requiredRole = "OWNER")

        shouldThrow<AccessDeniedException> { controller.delete(nsId) }
    }
})
