package io.whozoss.agentos.caseFlow

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.PermissionRelation
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Unit tests for [CaseController] (Story 3.1).
 *
 * Covers:
 * - `checkCreatePermission` gate: at least READ on the parent namespace is
 *   required (MEMBER and ADMIN accepted; no relation → 403)
 * - `create` override: auto-grants `[:ADMIN]` on the new case to the creator,
 *   best-effort (grant failure logs WARN but does not roll back creation)
 * - Mapping helpers (`toResource`, `toDomain`)
 *
 * Inherited endpoints (`getById`, `listByParent`, etc.) are covered at the
 * framework level by `SecuredEntityControllerSpec`.
 */
class CaseControllerSpec : StringSpec({

    val caseService = mockk<CaseService>()
    val userService = mockk<UserService>()
    val permissionService = mockk<PermissionService>()
    val controller = CaseController(caseService, userService, permissionService)

    val callerId = UUID.randomUUID()
    val caller = User(
        metadata = EntityMetadata(id = callerId),
        externalId = "member@example.com",
        email = "member@example.com",
        isAdmin = false,
    )

    val namespaceId = UUID.randomUUID()

    fun caseEntity(id: UUID = UUID.randomUUID(), title: String = "my case") = Case(
        metadata = EntityMetadata(id = id),
        namespaceId = namespaceId,
        status = CaseStatus.PENDING,
        title = title,
    )

    fun caseResource(id: UUID? = null, title: String? = "my case") = CaseResource(
        id = id,
        namespaceId = namespaceId,
        status = CaseStatus.PENDING,
        title = title,
    )

    beforeTest { clearAllMocks() }

    // -------------------------------------------------------------------------
    // getEntityType
    // -------------------------------------------------------------------------

    "getEntityType returns \"Case\" (must match Neo4j label)" {
        controller.getEntityType() shouldBe "Case"
    }

    // -------------------------------------------------------------------------
    // Mapping
    // -------------------------------------------------------------------------

    "toResource maps all case fields including namespaceId and status" {
        val entity = caseEntity(title = "engineering case")

        val result = controller.toResource(entity)

        result.id shouldBe entity.metadata.id
        result.namespaceId shouldBe namespaceId
        result.status shouldBe CaseStatus.PENDING
        result.title shouldBe "engineering case"
    }

    "toDomain generates a random UUID when resource id is null" {
        val first = controller.toDomain(caseResource(id = null)).metadata.id
        val second = controller.toDomain(caseResource(id = null)).metadata.id

        // Two consecutive calls must yield distinct ids — proving a fresh UUID
        // is generated rather than a default/sentinel value being reused.
        first shouldNotBe second
    }

    "toDomain preserves a provided id" {
        val id = UUID.randomUUID()
        controller.toDomain(caseResource(id = id)).metadata.id shouldBe id
    }

    "toDomain fills null title with the default 'Case <id>' (Story 3.4 AC4)" {
        val id = UUID.randomUUID()
        val result = controller.toDomain(caseResource(id = id, title = null))

        result.title shouldBe "Case $id"
    }

    // -------------------------------------------------------------------------
    // create (Story 3.1) — happy path + auto-grant
    // -------------------------------------------------------------------------

    "create succeeds when caller has READ on the parent namespace and auto-grants ADMIN on the new case" {
        val r = caseResource(id = null)
        val saved = caseEntity()
        every { userService.getCurrentUser() } returns caller
        every {
            permissionService.hasPermission(callerId.toString(), "Namespace", namespaceId.toString(), Action.READ)
        } returns true
        every { caseService.create(any()) } returns saved
        every {
            permissionService.grantPermission(
                callerId.toString(), "Case", saved.metadata.id.toString(), PermissionRelation.ADMIN,
            )
        } just Runs

        val result = controller.create(r)

        result.id shouldBe saved.metadata.id
        result.namespaceId shouldBe namespaceId
        verify(exactly = 1) { caseService.create(any()) }
        verify(exactly = 1) {
            permissionService.grantPermission(
                callerId.toString(), "Case", saved.metadata.id.toString(), PermissionRelation.ADMIN,
            )
        }
    }

    "create throws 403 when caller has no READ on the parent namespace" {
        val r = caseResource(id = null)
        every { userService.getCurrentUser() } returns caller
        every {
            permissionService.hasPermission(callerId.toString(), "Namespace", namespaceId.toString(), Action.READ)
        } returns false

        val ex = shouldThrow<ResponseStatusException> { controller.create(r) }

        ex.statusCode shouldBe HttpStatus.FORBIDDEN
        ex.reason shouldBe "Access denied - no access to namespace"
        // Neither persistence nor grant should happen when permission is denied
        verify(exactly = 0) { caseService.create(any()) }
        verify(exactly = 0) { permissionService.grantPermission(any(), any(), any(), any()) }
    }

    "create still succeeds when the auto-ADMIN grant fails (logs warning, no rollback)" {
        val r = caseResource(id = null)
        val saved = caseEntity()
        every { userService.getCurrentUser() } returns caller
        every {
            permissionService.hasPermission(callerId.toString(), "Namespace", namespaceId.toString(), Action.READ)
        } returns true
        every { caseService.create(any()) } returns saved
        every {
            permissionService.grantPermission(
                callerId.toString(), "Case", saved.metadata.id.toString(), PermissionRelation.ADMIN,
            )
        } throws RuntimeException("transient Neo4j failure")

        val result = controller.create(r)

        result.id shouldBe saved.metadata.id
        verify(exactly = 1) { caseService.create(any()) }
    }

    "create succeeds for super-admin via hasPermission bypass (READ returns true)" {
        val superAdmin = caller.copy(isAdmin = true)
        val r = caseResource(id = null)
        val saved = caseEntity()
        every { userService.getCurrentUser() } returns superAdmin
        every {
            permissionService.hasPermission(callerId.toString(), "Namespace", namespaceId.toString(), Action.READ)
        } returns true
        every { caseService.create(any()) } returns saved
        every { permissionService.grantPermission(any(), any(), any(), any()) } just Runs

        controller.create(r).id shouldBe saved.metadata.id

        verify(exactly = 1) {
            permissionService.grantPermission(
                callerId.toString(), "Case", saved.metadata.id.toString(), PermissionRelation.ADMIN,
            )
        }
    }

    // -------------------------------------------------------------------------
    // listByParent (Story 3.2) — short-circuit for namespace ADMIN
    // -------------------------------------------------------------------------

    "listByParent short-circuits and returns all cases unfiltered when caller has ADMIN on the parent namespace" {
        val case1 = caseEntity(title = "a")
        val case2 = caseEntity(title = "b")
        val case3 = caseEntity(title = "c")
        every { userService.getCurrentUser() } returns caller
        every {
            permissionService.hasPermission(callerId.toString(), "Namespace", namespaceId.toString(), Action.WRITE)
        } returns true
        every { caseService.findByParent(namespaceId) } returns listOf(case1, case2, case3)

        val result = controller.listByParent(namespaceId)

        result.map { it.id } shouldBe listOf(case1.metadata.id, case2.metadata.id, case3.metadata.id)
        verify(exactly = 1) { caseService.findByParent(namespaceId) }
        // No per-case hasPermission call when caller is namespace ADMIN (avoids N+1)
        verify(exactly = 0) {
            permissionService.hasPermission(any(), "Case", any(), any())
        }
    }

    "listByParent uses findAccessibleByUserInNamespace when caller is not namespace ADMIN (Story 3.3)" {
        val ownCase = caseEntity(title = "mine")
        every { userService.getCurrentUser() } returns caller
        every {
            permissionService.hasPermission(callerId.toString(), "Namespace", namespaceId.toString(), Action.WRITE)
        } returns false
        // The permission-filtered repo method returns only cases the user has
        // access to — the repo applies the FR15 rule, controller just maps.
        every {
            caseService.findAccessibleByUserInNamespace(callerId, namespaceId)
        } returns listOf(ownCase)

        val result = controller.listByParent(namespaceId)

        result.map { it.id } shouldBe listOf(ownCase.metadata.id)
        // Assert we do NOT fall back to the per-case super.listByParent path
        verify(exactly = 0) {
            permissionService.hasPermission(any(), "Case", any(), any())
        }
        verify(exactly = 0) { caseService.findByParent(namespaceId) }
        verify(exactly = 1) { caseService.findAccessibleByUserInNamespace(callerId, namespaceId) }
    }

    "listByParent non-admin path returns empty list when the user has no accessible case" {
        every { userService.getCurrentUser() } returns caller
        every {
            permissionService.hasPermission(callerId.toString(), "Namespace", namespaceId.toString(), Action.WRITE)
        } returns false
        every {
            caseService.findAccessibleByUserInNamespace(callerId, namespaceId)
        } returns emptyList()

        controller.listByParent(namespaceId) shouldBe emptyList()
    }

    "listByParent short-circuits for super-admin (hasPermission WRITE returns true via bypass)" {
        val superAdmin = caller.copy(isAdmin = true)
        val case1 = caseEntity()
        every { userService.getCurrentUser() } returns superAdmin
        every {
            permissionService.hasPermission(callerId.toString(), "Namespace", namespaceId.toString(), Action.WRITE)
        } returns true
        every { caseService.findByParent(namespaceId) } returns listOf(case1)

        controller.listByParent(namespaceId).size shouldBe 1
        verify(exactly = 0) {
            permissionService.hasPermission(any(), "Case", any(), any())
        }
    }

    // -------------------------------------------------------------------------
    // update / delete — AC3 403 for MEMBER without direct ADMIN (Story 3.4)
    // -------------------------------------------------------------------------

    "update returns 403 when caller has only namespace MEMBER and no direct ADMIN on case" {
        val entity = caseEntity()
        val updateResource = caseResource(id = entity.metadata.id, title = "updated")
        every { caseService.findById(entity.metadata.id) } returns entity
        every { userService.getCurrentUser() } returns caller
        every {
            permissionService.hasPermission(
                callerId.toString(), "Case", entity.metadata.id.toString(), Action.WRITE,
            )
        } returns false

        val ex = shouldThrow<ResponseStatusException> {
            controller.update(entity.metadata.id, updateResource)
        }

        ex.statusCode shouldBe HttpStatus.FORBIDDEN
        verify(exactly = 0) { caseService.update(any()) }
    }

    "delete returns 403 when caller has only namespace MEMBER and no direct ADMIN on case" {
        val entity = caseEntity()
        every { caseService.findById(entity.metadata.id) } returns entity
        every { userService.getCurrentUser() } returns caller
        every {
            permissionService.hasPermission(
                callerId.toString(), "Case", entity.metadata.id.toString(), Action.DELETE,
            )
        } returns false

        val ex = shouldThrow<ResponseStatusException> {
            controller.delete(entity.metadata.id)
        }

        ex.statusCode shouldBe HttpStatus.FORBIDDEN
        verify(exactly = 0) { caseService.delete(any()) }
    }
})
