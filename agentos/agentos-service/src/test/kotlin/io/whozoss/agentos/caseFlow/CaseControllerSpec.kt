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
import io.whozoss.agentos.permissions.DirectRelation
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionRelation
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.permissions.StarredService
import io.whozoss.agentos.sdk.api.case.CaseDto
import io.whozoss.agentos.sdk.api.case.ListByUserInNamespaceRequest
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Unit tests for [CaseController].
 *
 * Covers:
 * - `checkCreatePermission` gate: at least READ on the parent namespace is
 *   required (MEMBER and ADMIN accepted; no relation → 403)
 * - `create` override: auto-grants `[:ADMIN]` on the new case to the creator,
 *   best-effort (grant failure logs WARN but does not roll back creation)
 * - Mapping helpers (`toResource`, `toDomain`)
 *
 * Authorization paths are declarative (`@PreAuthorize`) and exercised by
 * [io.whozoss.agentos.security.declarative.MethodSecurityIntegrationSpec].
 */
class CaseControllerSpec : StringSpec({

    val caseService = mockk<CaseService>()
    val namespaceService = mockk<io.whozoss.agentos.namespace.NamespaceService>()
    val userService = mockk<UserService>()
    val permissionService = mockk<PermissionService>()
    val starredService = mockk<StarredService>()
    val controller = CaseController(caseService, namespaceService, userService, permissionService, starredService)

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

    fun caseResource(id: UUID? = null, title: String? = "my case") = CaseDto(
        id = id,
        namespaceId = namespaceId,
        status = CaseStatus.PENDING,
        title = title,
    )

    beforeTest {
        clearAllMocks()
        // Default: the caller has no starred entries (empty enrichment). Listing tests that
        // assert `favorite`/`role` override this with a specific map.
        every { starredService.listDirectRelations(any(), EntityType.CASE) } returns emptyMap()
    }

    // -------------------------------------------------------------------------
    // Mapping
    // -------------------------------------------------------------------------

    "toDto maps all case fields including namespaceId, status, created and modified" {
        val now = java.time.Instant.now()
        val later = now.plusSeconds(60)
        val entity = caseEntity(title = "engineering case").copy(
            metadata = EntityMetadata(created = now, modified = later),
        )

        val result = toDto(entity)

        result.id shouldBe entity.metadata.id
        result.namespaceId shouldBe namespaceId
        result.status shouldBe CaseStatus.PENDING
        result.title shouldBe "engineering case"
        result.created shouldBe now
        result.modified shouldBe later
    }

    // -------------------------------------------------------------------------
    // create — happy path + auto-grant
    // -------------------------------------------------------------------------

    "create succeeds when caller has READ on the parent namespace and auto-grants ADMIN on the new case" {
        val r = caseResource(id = null)
        val saved = caseEntity()
        every { userService.getCurrentUser() } returns caller
        every {
            permissionService.hasPermission(callerId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.READ)
        } returns true
        every { caseService.create(any()) } returns saved
        every {
            permissionService.grantPermission(
                callerId.toString(), EntityType.CASE, saved.metadata.id.toString(), PermissionRelation.ADMIN,
            )
        } just Runs

        val result = controller.create(r)

        result.id shouldBe saved.metadata.id
        result.namespaceId shouldBe namespaceId
        // The creator holds a fresh direct ADMIN edge — surface it so the drawer enables delete at once.
        result.role shouldBe "ADMIN"
        verify(exactly = 1) { caseService.create(any()) }
        verify(exactly = 1) {
            permissionService.grantPermission(
                callerId.toString(), EntityType.CASE, saved.metadata.id.toString(), PermissionRelation.ADMIN,
            )
        }
    }

    "create still succeeds when the auto-ADMIN grant fails (logs warning, no rollback)" {
        val r = caseResource(id = null)
        val saved = caseEntity()
        every { userService.getCurrentUser() } returns caller
        every { caseService.create(any()) } returns saved
        every {
            permissionService.grantPermission(
                callerId.toString(), EntityType.CASE, saved.metadata.id.toString(), PermissionRelation.ADMIN,
            )
        } throws RuntimeException("transient Neo4j failure")

        val result = controller.create(r)

        result.id shouldBe saved.metadata.id
        // Grant failed → no direct edge yet, so role is left null (not a misleading ADMIN).
        result.role shouldBe null
        verify(exactly = 1) { caseService.create(any()) }
    }

    "create auto-grants ADMIN on the new case to the creator" {
        val r = caseResource(id = null)
        val saved = caseEntity()
        every { userService.getCurrentUser() } returns caller
        every { caseService.create(any()) } returns saved
        every { permissionService.grantPermission(any(), any(), any(), any()) } just Runs

        controller.create(r).id shouldBe saved.metadata.id

        verify(exactly = 1) {
            permissionService.grantPermission(
                callerId.toString(), EntityType.CASE, saved.metadata.id.toString(), PermissionRelation.ADMIN,
            )
        }
    }

    // -------------------------------------------------------------------------
    // listByParent — short-circuit for namespace ADMIN
    // -------------------------------------------------------------------------

    "listByParent short-circuits and returns all cases unfiltered when caller has ADMIN on the parent namespace" {
        val case1 = caseEntity(title = "a")
        val case2 = caseEntity(title = "b")
        val case3 = caseEntity(title = "c")
        every { userService.getCurrentUser() } returns caller
        every {
            permissionService.hasPermission(callerId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.WRITE)
        } returns true
        every { caseService.findByParent(namespaceId) } returns listOf(case1, case2, case3)

        val result = controller.listByParent(namespaceId)

        result.map { it.id } shouldBe listOf(case1.metadata.id, case2.metadata.id, case3.metadata.id)
        verify(exactly = 1) { caseService.findByParent(namespaceId) }
        // No per-case hasPermission call when caller is namespace ADMIN (avoids N+1)
        verify(exactly = 0) {
            permissionService.hasPermission(any(), EntityType.CASE, any(), any())
        }
    }

    "listByParent uses findAccessibleByUserInNamespace when caller is not namespace ADMIN" {
        val ownCase = caseEntity(title = "mine")
        every { userService.getCurrentUser() } returns caller
        every {
            permissionService.hasPermission(callerId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.WRITE)
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
            permissionService.hasPermission(any(), EntityType.CASE, any(), any())
        }
        verify(exactly = 0) { caseService.findByParent(namespaceId) }
        verify(exactly = 1) { caseService.findAccessibleByUserInNamespace(callerId, namespaceId) }
    }

    "listByParent non-admin path returns empty list when the user has no accessible case" {
        every { userService.getCurrentUser() } returns caller
        every {
            permissionService.hasPermission(callerId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.WRITE)
        } returns false
        every {
            caseService.findAccessibleByUserInNamespace(callerId, namespaceId)
        } returns emptyList()

        controller.listByParent(namespaceId) shouldBe emptyList()
    }

    // -------------------------------------------------------------------------
    // listByParent — favorite enrichment (per-user starred flag)
    // -------------------------------------------------------------------------

    "listByParent (namespace-admin branch) sets favorite=true only for starred cases" {
        val starred = caseEntity(title = "starred")
        val plain = caseEntity(title = "plain")
        every { userService.getCurrentUser() } returns caller
        every {
            starredService.listDirectRelations(callerId.toString(), EntityType.CASE)
        } returns mapOf(starred.metadata.id.toString() to DirectRelation(PermissionRelation.ADMIN, starred = true))
        every {
            permissionService.hasPermission(callerId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.WRITE)
        } returns true
        every { caseService.findByParent(namespaceId) } returns listOf(starred, plain)

        val result = controller.listByParent(namespaceId)

        result.single { it.id == starred.metadata.id }.favorite shouldBe true
        result.single { it.id == plain.metadata.id }.favorite shouldBe false
    }

    "listByParent (permission-filtered branch) sets favorite=true only for starred cases" {
        val starred = caseEntity(title = "starred")
        val plain = caseEntity(title = "plain")
        every { userService.getCurrentUser() } returns caller
        every {
            starredService.listDirectRelations(callerId.toString(), EntityType.CASE)
        } returns mapOf(starred.metadata.id.toString() to DirectRelation(PermissionRelation.MEMBER, starred = true))
        every {
            permissionService.hasPermission(callerId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.WRITE)
        } returns false
        every {
            caseService.findAccessibleByUserInNamespace(callerId, namespaceId)
        } returns listOf(starred, plain)

        val result = controller.listByParent(namespaceId)

        result.single { it.id == starred.metadata.id }.favorite shouldBe true
        result.single { it.id == plain.metadata.id }.favorite shouldBe false
    }

    // -------------------------------------------------------------------------
    // starCase / unstarCase — per-user favorite toggling (PUT/DELETE /{id}/star)
    // -------------------------------------------------------------------------

    "starCase delegates to starredService.setStarred with starred=true for the current user" {
        val caseId = UUID.randomUUID()
        every { userService.getCurrentUser() } returns caller
        every { starredService.setStarred(callerId.toString(), EntityType.CASE, caseId.toString(), true) } returns true

        controller.starCase(caseId)

        verify(exactly = 1) {
            starredService.setStarred(callerId.toString(), EntityType.CASE, caseId.toString(), true)
        }
    }

    "starCase throws 409 when the caller has no direct relation (setStarred wrote nothing)" {
        val caseId = UUID.randomUUID()
        every { userService.getCurrentUser() } returns caller
        every { starredService.setStarred(callerId.toString(), EntityType.CASE, caseId.toString(), true) } returns false

        val ex = shouldThrow<ResponseStatusException> { controller.starCase(caseId) }
        ex.statusCode shouldBe HttpStatus.CONFLICT
    }

    "unstarCase delegates to starredService.setStarred with starred=false for the current user" {
        val caseId = UUID.randomUUID()
        every { userService.getCurrentUser() } returns caller
        every { starredService.setStarred(callerId.toString(), EntityType.CASE, caseId.toString(), false) } returns true

        controller.unstarCase(caseId)

        verify(exactly = 1) {
            starredService.setStarred(callerId.toString(), EntityType.CASE, caseId.toString(), false)
        }
    }

    "unstarCase throws 409 when the caller has no direct relation (setStarred wrote nothing)" {
        val caseId = UUID.randomUUID()
        every { userService.getCurrentUser() } returns caller
        every { starredService.setStarred(callerId.toString(), EntityType.CASE, caseId.toString(), false) } returns false

        val ex = shouldThrow<ResponseStatusException> { controller.unstarCase(caseId) }
        ex.statusCode shouldBe HttpStatus.CONFLICT
    }

    // -------------------------------------------------------------------------
    // listMineByParent — GET /api/cases/by-parentId/{parentId}/mine
    //   Direct-relation-only listing for the CURRENT user (no admin fast path,
    //   no namespace-admin transitivity). Every returned case is starrable.
    // -------------------------------------------------------------------------

    "listMineByParent delegates to findConcerningUserInNamespace for the current user" {
        val mine1 = caseEntity(title = "mine 1")
        val mine2 = caseEntity(title = "mine 2")
        every { userService.getCurrentUser() } returns caller
        every { caseService.findConcerningUserInNamespace(callerId, namespaceId) } returns listOf(mine1, mine2)

        val result = controller.listMineByParent(namespaceId)

        result.map { it.id } shouldBe listOf(mine1.metadata.id, mine2.metadata.id)
        verify(exactly = 1) { caseService.findConcerningUserInNamespace(callerId, namespaceId) }
        // Never uses the admin fast path, the transitive/permission-filtered listing, or a namespace-admin check.
        verify(exactly = 0) { caseService.findByParent(any()) }
        verify(exactly = 0) { caseService.findAccessibleByUserInNamespace(any(), any()) }
        verify(exactly = 0) { permissionService.hasPermission(any(), EntityType.NAMESPACE, any(), any()) }
    }

    "listMineByParent sets favorite=true only for starred cases" {
        val starred = caseEntity(title = "starred")
        val plain = caseEntity(title = "plain")
        every { userService.getCurrentUser() } returns caller
        every {
            starredService.listDirectRelations(callerId.toString(), EntityType.CASE)
        } returns mapOf(
            starred.metadata.id.toString() to DirectRelation(PermissionRelation.ADMIN, starred = true),
            plain.metadata.id.toString() to DirectRelation(PermissionRelation.ADMIN, starred = false),
        )
        every { caseService.findConcerningUserInNamespace(callerId, namespaceId) } returns listOf(starred, plain)

        val result = controller.listMineByParent(namespaceId)

        result.single { it.id == starred.metadata.id }.favorite shouldBe true
        result.single { it.id == plain.metadata.id }.favorite shouldBe false
    }

    "listMineByParent sets role from the caller's direct relation (ADMIN vs MEMBER)" {
        val adminCase = caseEntity(title = "admin case")
        val memberCase = caseEntity(title = "member case")
        every { userService.getCurrentUser() } returns caller
        every {
            starredService.listDirectRelations(callerId.toString(), EntityType.CASE)
        } returns mapOf(
            adminCase.metadata.id.toString() to DirectRelation(PermissionRelation.ADMIN, starred = false),
            memberCase.metadata.id.toString() to DirectRelation(PermissionRelation.MEMBER, starred = false),
        )
        every { caseService.findConcerningUserInNamespace(callerId, namespaceId) } returns listOf(adminCase, memberCase)

        val result = controller.listMineByParent(namespaceId)

        // The UI gates the delete affordance on role == "ADMIN".
        result.single { it.id == adminCase.metadata.id }.role shouldBe "ADMIN"
        result.single { it.id == memberCase.metadata.id }.role shouldBe "MEMBER"
    }

    "listMineByParent returns empty list when the user has no directly-related case" {
        every { userService.getCurrentUser() } returns caller
        every { caseService.findConcerningUserInNamespace(callerId, namespaceId) } returns emptyList()

        controller.listMineByParent(namespaceId) shouldBe emptyList()
    }

    // -------------------------------------------------------------------------
    // listByUser — GET /api/cases/by-user/{userId}
    // -------------------------------------------------------------------------

    "listByUser returns cases concerning the requested user across namespaces" {
        val ns2 = UUID.randomUUID()
        val case1 = caseEntity(title = "in ns1")
        val case2 = caseEntity(title = "in ns2").copy(namespaceId = ns2)
        every { caseService.findConcerningUser(callerId) } returns listOf(case1, case2)

        val result = controller.listByUser(callerId)

        result.map { it.id } shouldBe listOf(case1.metadata.id, case2.metadata.id)
        verify(exactly = 1) { caseService.findConcerningUser(callerId) }
    }

    "listByUser returns empty list when no cases concern the requested user" {
        every { caseService.findConcerningUser(callerId) } returns emptyList()

        controller.listByUser(callerId) shouldBe emptyList()
    }

    // -------------------------------------------------------------------------
    // listByUserExternalId — GET /api/cases/by-user/external/{externalId}
    // -------------------------------------------------------------------------

    "listByUserExternalId returns cases concerning the resolved user" {
        val ns2 = UUID.randomUUID()
        val case1 = caseEntity(title = "in ns1")
        val case2 = caseEntity(title = "in ns2").copy(namespaceId = ns2)
        every { userService.findByExternalId(caller.externalId) } returns caller
        every { caseService.findConcerningUser(callerId) } returns listOf(case1, case2)

        val result = controller.listByUserExternalId(caller.externalId)

        result.map { it.id } shouldBe listOf(case1.metadata.id, case2.metadata.id)
        verify(exactly = 1) { caseService.findConcerningUser(callerId) }
    }

    "listByUserExternalId throws 404 when no user matches the external id" {
        every { userService.findByExternalId("unknown@example.com") } returns null

        shouldThrow<io.whozoss.agentos.exception.ResourceNotFoundException> {
            controller.listByUserExternalId("unknown@example.com")
        }
    }

    "listByUserExternalId returns empty list when the resolved user has no cases" {
        every { userService.findByExternalId(caller.externalId) } returns caller
        every { caseService.findConcerningUser(callerId) } returns emptyList()

        controller.listByUserExternalId(caller.externalId) shouldBe emptyList()
    }

    // -------------------------------------------------------------------------
    // listByUserInNamespace — POST /api/cases/by-user/in-namespace
    // -------------------------------------------------------------------------

    "listByUserInNamespace returns only cases in the requested namespace" {
        val caseInNs = caseEntity(title = "in ns")
        val namespaceExternalId = "ext-ns-1"
        val namespace = io.whozoss.agentos.namespace.Namespace(
            metadata = io.whozoss.agentos.sdk.entity.EntityMetadata(id = namespaceId),
            name = "test-ns",
            externalId = namespaceExternalId,
        )
        every { userService.findByExternalId(caller.externalId) } returns caller
        every { namespaceService.findByExternalId(namespaceExternalId) } returns namespace
        every { caseService.findConcerningUserInNamespace(callerId, namespaceId) } returns listOf(caseInNs)

        val result = controller.listByUserInNamespace(
            ListByUserInNamespaceRequest(userExternalId = caller.externalId, namespaceExternalId = namespaceExternalId)
        )

        result.map { it.id } shouldBe listOf(caseInNs.metadata.id)
        verify(exactly = 1) { caseService.findConcerningUserInNamespace(callerId, namespaceId) }
        verify(exactly = 0) { caseService.findConcerningUser(any()) }
    }

    "listByUserInNamespace returns empty list when user has no cases in the namespace" {
        val namespaceExternalId = "ext-ns-empty"
        val namespace = io.whozoss.agentos.namespace.Namespace(
            metadata = io.whozoss.agentos.sdk.entity.EntityMetadata(id = namespaceId),
            name = "test-ns",
            externalId = namespaceExternalId,
        )
        every { userService.findByExternalId(caller.externalId) } returns caller
        every { namespaceService.findByExternalId(namespaceExternalId) } returns namespace
        every { caseService.findConcerningUserInNamespace(callerId, namespaceId) } returns emptyList()

        controller.listByUserInNamespace(
            ListByUserInNamespaceRequest(userExternalId = caller.externalId, namespaceExternalId = namespaceExternalId)
        ) shouldBe emptyList()
    }

    "listByUserInNamespace throws 404 when no user matches the external id" {
        every { userService.findByExternalId("unknown@example.com") } returns null

        shouldThrow<io.whozoss.agentos.exception.ResourceNotFoundException> {
            controller.listByUserInNamespace(
                ListByUserInNamespaceRequest(userExternalId = "unknown@example.com", namespaceExternalId = "any")
            )
        }
    }

    "listByUserInNamespace throws 404 when no namespace matches the namespaceExternalId" {
        every { userService.findByExternalId(caller.externalId) } returns caller
        every { namespaceService.findByExternalId("unknown-ns") } returns null

        shouldThrow<io.whozoss.agentos.exception.ResourceNotFoundException> {
            controller.listByUserInNamespace(
                ListByUserInNamespaceRequest(userExternalId = caller.externalId, namespaceExternalId = "unknown-ns")
            )
        }
    }

    "listByParent short-circuits for super-admin (hasPermission WRITE returns true via bypass)" {
        val superAdmin = caller.copy(isAdmin = true)
        val case1 = caseEntity()
        every { userService.getCurrentUser() } returns superAdmin
        every {
            permissionService.hasPermission(callerId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.WRITE)
        } returns true
        every { caseService.findByParent(namespaceId) } returns listOf(case1)

        controller.listByParent(namespaceId).size shouldBe 1
        verify(exactly = 0) {
            permissionService.hasPermission(any(), EntityType.CASE, any(), any())
        }
    }

    // -------------------------------------------------------------------------
    // update — mass-assignment guard ()
    // -------------------------------------------------------------------------

    "update preserves the persisted namespaceId and status when client sends different values" {
        val existing = caseEntity()
        val otherNs = UUID.randomUUID()
        val payload = caseResource(id = existing.metadata.id, title = "renamed")
            .copy(namespaceId = otherNs, status = CaseStatus.RUNNING)
        every { caseService.findById(existing.metadata.id) } returns existing
        every { caseService.update(any()) } answers {
            val saved = firstArg<Case>()
            saved.namespaceId shouldBe namespaceId
            saved.status shouldBe existing.status
            saved.title shouldBe "renamed"
            saved
        }

        controller.update(existing.metadata.id, payload)

        verify(exactly = 1) { caseService.update(any()) }
    }

    "update throws 404 when the Case does not exist" {
        val id = UUID.randomUUID()
        every { caseService.findById(id) } returns null

        shouldThrow<io.whozoss.agentos.exception.ResourceNotFoundException> {
            controller.update(id, caseResource(id = id))
        }
    }

})
