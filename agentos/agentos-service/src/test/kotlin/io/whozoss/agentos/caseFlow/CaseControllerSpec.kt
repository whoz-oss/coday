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
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionRelation
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
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
class CaseControllerSpec :
    StringSpec({

        val caseService = mockk<CaseService>()
        val userService = mockk<UserService>()
        val permissionService = mockk<PermissionService>()
        val controller = CaseController(caseService, userService, permissionService)

        val callerId = UUID.randomUUID()
        val caller =
            User(
                metadata = EntityMetadata(id = callerId),
                externalId = "member@example.com",
                email = "member@example.com",
                isAdmin = false,
            )

        val namespaceId = UUID.randomUUID()

        fun caseEntity(
            id: UUID = UUID.randomUUID(),
            title: String = "my case",
        ) = Case(
            metadata = EntityMetadata(id = id),
            namespaceId = namespaceId,
            status = CaseStatus.PENDING,
            title = title,
        )

        fun caseResource(
            id: UUID? = null,
            title: String? = "my case",
        ) = CaseResource(
            id = id,
            namespaceId = namespaceId,
            status = CaseStatus.PENDING,
            title = title,
        )

        beforeTest { clearAllMocks() }

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

        "toDomain fills null title with the default 'Case <id>'" {
            val id = UUID.randomUUID()
            val result = controller.toDomain(caseResource(id = id, title = null))

            result.title shouldBe "Case $id"
        }

        // -------------------------------------------------------------------------
        // create — happy path + auto-grant
        // -------------------------------------------------------------------------

        "create succeeds when caller has READ on the parent namespace and auto-grants ADMIN on the new case" {
            val r = caseResource(id = null)
            val saved = caseEntity()
            every { userService.getCurrentUser() } returns caller
            every {
                permissionService.hasPermission(
                    userId = callerId.toString(),
                    entityType = EntityType.NAMESPACE,
                    entityId = namespaceId.toString(),
                    action = Action.READ,
                )
            } returns true
            every { caseService.create(any()) } returns saved
            every {
                permissionService.grantPermission(
                    userId = callerId.toString(),
                    entityType = EntityType.CASE,
                    entityId = saved.metadata.id.toString(),
                    relation = PermissionRelation.ADMIN,
                )
            } just Runs

            val result = controller.create(r)

            result.id shouldBe saved.metadata.id
            result.namespaceId shouldBe namespaceId
            verify(exactly = 1) { caseService.create(any()) }
            verify(exactly = 1) {
                permissionService.grantPermission(
                    callerId.toString(),
                    EntityType.CASE,
                    saved.metadata.id.toString(),
                    PermissionRelation.ADMIN,
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
                    userId = callerId.toString(),
                    entityType = EntityType.CASE,
                    entityId = saved.metadata.id.toString(),
                    relation = PermissionRelation.ADMIN,
                )
            } throws RuntimeException("transient Neo4j failure")

            val result = controller.create(r)

            result.id shouldBe saved.metadata.id
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
                    userId = callerId.toString(),
                    entityType = EntityType.CASE,
                    entityId = saved.metadata.id.toString(),
                    relation = PermissionRelation.ADMIN,
                )
            }
        }

        // -------------------------------------------------------------------------
        // listByParent — super-admin fast-path vs permission-filtered path (WZ-32167)
        // -------------------------------------------------------------------------

        "listByParent uses findAccessibleByUserInNamespace for a regular (non-admin) user" {
            val ownCase = caseEntity(title = "mine")
            every { userService.getCurrentUser() } returns caller
            every {
                caseService.findAccessibleByUserInNamespace(callerId, namespaceId)
            } returns listOf(ownCase)

            val result = controller.listByParent(namespaceId)

            result.map { it.id } shouldBe listOf(ownCase.metadata.id)
            verify(exactly = 1) { caseService.findAccessibleByUserInNamespace(callerId, namespaceId) }
            verify(exactly = 0) { caseService.findByParent(namespaceId) }
            // No per-case permission check — the repo query handles that
            verify(exactly = 0) { permissionService.hasPermission(any(), EntityType.CASE, any(), any()) }
        }

        "listByParent uses findAccessibleByUserInNamespace for a namespace-ADMIN (non-super-admin) user — WZ-32167" {
            // A user with namespace ADMIN (e.g. Federation Admin, Designer) is NOT a super-admin.
            // They must only see their own cases, not all cases in the namespace.
            val namespaceAdmin = caller.copy(isAdmin = false)
            val ownCase = caseEntity(title = "my own case")
            every { userService.getCurrentUser() } returns namespaceAdmin
            every {
                caseService.findAccessibleByUserInNamespace(callerId, namespaceId)
            } returns listOf(ownCase)

            val result = controller.listByParent(namespaceId)

            result.map { it.id } shouldBe listOf(ownCase.metadata.id)
            verify(exactly = 1) { caseService.findAccessibleByUserInNamespace(callerId, namespaceId) }
            // Must NOT short-circuit to unfiltered findByParent for namespace-ADMIN non-super-admin
            verify(exactly = 0) { caseService.findByParent(namespaceId) }
        }

        "listByParent returns empty list when a non-super-admin has no accessible cases" {
            every { userService.getCurrentUser() } returns caller
            every {
                caseService.findAccessibleByUserInNamespace(callerId, namespaceId)
            } returns emptyList()

            controller.listByParent(namespaceId) shouldBe emptyList()
            verify(exactly = 0) { caseService.findByParent(namespaceId) }
        }

        "listByParent short-circuits to unfiltered listing only for super-admin (isAdmin == true)" {
            val superAdmin = caller.copy(isAdmin = true)
            val case1 = caseEntity(title = "alice's case")
            val case2 = caseEntity(title = "bob's case")
            every { userService.getCurrentUser() } returns superAdmin
            every { caseService.findByParent(namespaceId) } returns listOf(case1, case2)

            val result = controller.listByParent(namespaceId)

            result.map { it.id } shouldBe listOf(case1.metadata.id, case2.metadata.id)
            verify(exactly = 1) { caseService.findByParent(namespaceId) }
            // Super-admin path must NOT call the permission-filtered method
            verify(exactly = 0) { caseService.findAccessibleByUserInNamespace(any(), any()) }
            verify(exactly = 0) { permissionService.hasPermission(any(), EntityType.CASE, any(), any()) }
        }

        // -------------------------------------------------------------------------
        // update — mass-assignment guard ()
        // -------------------------------------------------------------------------

        "update preserves the persisted namespaceId and status when client sends different values" {
            val existing = caseEntity()
            val otherNs = UUID.randomUUID()
            val payload =
                caseResource(id = existing.metadata.id, title = "renamed")
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
