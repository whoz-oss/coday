package io.whozoss.agentos.caseFlow

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.caseEvent.CaseEventService
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.DirectRelation
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionRelation
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.permissions.FavoriteService
import io.whozoss.agentos.sdk.api.case.UnreadCountResponse
import io.whozoss.agentos.sdk.api.case.CaseDto
import io.whozoss.agentos.sdk.api.case.ListByUserInNamespaceRequest
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
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
        val caseEventService = mockk<CaseEventService>()
        val namespaceService = mockk<io.whozoss.agentos.namespace.NamespaceService>()
        val userService = mockk<UserService>()
        val permissionService = mockk<PermissionService>()
        val favoriteService = mockk<FavoriteService>()
        val caseReadService = mockk<CaseReadService>()
        val controller =
            CaseController(
                caseService,
                caseEventService,
                namespaceService,
                userService,
                permissionService,
                favoriteService,
                caseReadService,
            )

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
        ) = CaseDto(
            id = id,
            namespaceId = namespaceId,
            status = CaseStatus.PENDING,
            title = title,
        )

        beforeTest {
            clearAllMocks()
            // Default: the caller has no favorite entries (empty enrichment). Listing tests that
            // assert `favorite`/`role`/`readAt` override this with a specific map.
            every { favoriteService.listDirectRelations(any(), EntityType.CASE) } returns emptyMap()
            // Default: no messages in any case. Tests that assert lastMessageAt override this.
            every { caseEventService.findLastMessageTimestamps(any()) } returns emptyMap()
        }

        // -------------------------------------------------------------------------
        // Mapping
        // -------------------------------------------------------------------------

        "toDto maps scheduledPromptId when the case was started by a ScheduledPrompt" {
            val spId = UUID.randomUUID()
            val entity = caseEntity().copy(scheduledPromptId = spId)

            val result = toDto(entity)

            result.scheduledPromptId shouldBe spId
        }

        "toDto leaves scheduledPromptId null for a user-initiated case" {
            val entity = caseEntity()

            val result = toDto(entity)

            result.scheduledPromptId shouldBe null
        }

        "toDto maps all case fields including namespaceId, status, created and modified" {
            val now = Instant.now()
            val later = now.plusSeconds(60)
            val entity =
                caseEntity(title = "engineering case").copy(
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
            every { caseService.create(any()) } returns saved
            every {
                permissionService.grantPermission(
                    callerId.toString(),
                    EntityType.CASE,
                    saved.metadata.id.toString(),
                    PermissionRelation.ADMIN,
                )
            } just Runs

            val result = controller.create(r)

            result.id shouldBe saved.metadata.id
            result.namespaceId shouldBe namespaceId
            // The creator holds a fresh direct ADMIN edge — surface it so the drawer enables delete at once.
            result.role shouldBe "ADMIN"
            // No messages exist at create time — lastMessageAt is always null, no round-trip needed.
            result.lastMessageAt shouldBe null
            verify(exactly = 1) { caseService.create(any()) }
            verify(exactly = 0) { caseEventService.findLastMessageTimestamps(any()) }
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
                    callerId.toString(),
                    EntityType.CASE,
                    saved.metadata.id.toString(),
                    PermissionRelation.ADMIN,
                )
            } throws RuntimeException("transient Neo4j failure")

            val result = controller.create(r)

            result.id shouldBe saved.metadata.id
            // Grant failed → no direct edge yet, so role is left null (not a misleading ADMIN).
            result.role shouldBe null
            result.lastMessageAt shouldBe null
            verify(exactly = 1) { caseService.create(any()) }
            verify(exactly = 0) { caseEventService.findLastMessageTimestamps(any()) }
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
                    callerId.toString(),
                    EntityType.CASE,
                    saved.metadata.id.toString(),
                    PermissionRelation.ADMIN,
                )
            }
            verify(exactly = 0) { caseEventService.findLastMessageTimestamps(any()) }
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
                permissionService.hasPermission(
                    callerId.toString(),
                    EntityType.NAMESPACE,
                    namespaceId.toString(),
                    Action.WRITE,
                )
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
                permissionService.hasPermission(
                    callerId.toString(),
                    EntityType.NAMESPACE,
                    namespaceId.toString(),
                    Action.WRITE,
                )
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
                permissionService.hasPermission(
                    callerId.toString(),
                    EntityType.NAMESPACE,
                    namespaceId.toString(),
                    Action.WRITE,
                )
            } returns false
            every {
                caseService.findAccessibleByUserInNamespace(callerId, namespaceId)
            } returns emptyList()

            controller.listByParent(namespaceId) shouldBe emptyList()
        }

        // -------------------------------------------------------------------------
        // listByParent — favorite enrichment (per-user starred flag)
        // -------------------------------------------------------------------------

        "listByParent (namespace-admin branch) sets favorite=true only for favorited cases" {
            val starred = caseEntity(title = "starred")
            val plain = caseEntity(title = "plain")
            every { userService.getCurrentUser() } returns caller
            every {
                favoriteService.listDirectRelations(callerId.toString(), EntityType.CASE)
            } returns mapOf(starred.metadata.id.toString() to DirectRelation(PermissionRelation.ADMIN, favorite = true))
            every {
                permissionService.hasPermission(
                    callerId.toString(),
                    EntityType.NAMESPACE,
                    namespaceId.toString(),
                    Action.WRITE,
                )
            } returns true
            every { caseService.findByParent(namespaceId) } returns listOf(starred, plain)

            val result = controller.listByParent(namespaceId)

            result.single { it.id == starred.metadata.id }.favorite shouldBe true
            result.single { it.id == plain.metadata.id }.favorite shouldBe false
        }

        "listByParent populates lastMessageAt from caseEventService for cases that have messages" {
            val withMsg = caseEntity(title = "has messages")
            val noMsg = caseEntity(title = "no messages")
            val msgTimestamp = Instant.parse("2025-06-01T10:00:00Z")
            every { userService.getCurrentUser() } returns caller
            every {
                permissionService.hasPermission(
                    callerId.toString(),
                    EntityType.NAMESPACE,
                    namespaceId.toString(),
                    Action.WRITE,
                )
            } returns true
            every { caseService.findByParent(namespaceId) } returns listOf(withMsg, noMsg)
            every {
                caseEventService.findLastMessageTimestamps(listOf(withMsg.id, noMsg.id))
            } returns mapOf(withMsg.id to msgTimestamp)

            val result = controller.listByParent(namespaceId)

            result.single { it.id == withMsg.metadata.id }.lastMessageAt shouldBe msgTimestamp
            result.single { it.id == noMsg.metadata.id }.lastMessageAt shouldBe null
        }

        "listByParent (permission-filtered branch) sets favorite=true only for favorited cases" {
            val starred = caseEntity(title = "starred")
            val plain = caseEntity(title = "plain")
            every { userService.getCurrentUser() } returns caller
            every {
                favoriteService.listDirectRelations(callerId.toString(), EntityType.CASE)
            } returns mapOf(starred.metadata.id.toString() to DirectRelation(PermissionRelation.MEMBER, favorite = true))
            every {
                permissionService.hasPermission(
                    callerId.toString(),
                    EntityType.NAMESPACE,
                    namespaceId.toString(),
                    Action.WRITE,
                )
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

        "starCase delegates to favoriteService.setFavorite with favorite=true for the current user" {
            val caseId = UUID.randomUUID()
            every { userService.getCurrentUser() } returns caller
            every {
                favoriteService.setFavorite(
                    callerId.toString(),
                    EntityType.CASE,
                    caseId.toString(),
                    true,
                )
            } returns true

            controller.starCase(caseId)

            verify(exactly = 1) {
                favoriteService.setFavorite(callerId.toString(), EntityType.CASE, caseId.toString(), true)
            }
        }

        "starCase throws 409 when the caller has no direct relation (setFavorite wrote nothing)" {
            val caseId = UUID.randomUUID()
            every { userService.getCurrentUser() } returns caller
            every {
                favoriteService.setFavorite(
                    callerId.toString(),
                    EntityType.CASE,
                    caseId.toString(),
                    true,
                )
            } returns false

            val ex = shouldThrow<ResponseStatusException> { controller.starCase(caseId) }
            ex.statusCode shouldBe HttpStatus.CONFLICT
        }

        "unstarCase delegates to favoriteService.setFavorite with favorite=false for the current user" {
            val caseId = UUID.randomUUID()
            every { userService.getCurrentUser() } returns caller
            every {
                favoriteService.setFavorite(
                    callerId.toString(),
                    EntityType.CASE,
                    caseId.toString(),
                    false,
                )
            } returns true

            controller.unstarCase(caseId)

            verify(exactly = 1) {
                favoriteService.setFavorite(callerId.toString(), EntityType.CASE, caseId.toString(), false)
            }
        }

        "unstarCase throws 409 when the caller has no direct relation (setFavorite wrote nothing)" {
            val caseId = UUID.randomUUID()
            every { userService.getCurrentUser() } returns caller
            every {
                favoriteService.setFavorite(
                    callerId.toString(),
                    EntityType.CASE,
                    caseId.toString(),
                    false,
                )
            } returns false

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

        "listMineByParent sets favorite=true only for favorited cases" {
            val starred = caseEntity(title = "starred")
            val plain = caseEntity(title = "plain")
            every { userService.getCurrentUser() } returns caller
            every {
                favoriteService.listDirectRelations(callerId.toString(), EntityType.CASE)
            } returns
                mapOf(
                    starred.metadata.id.toString() to DirectRelation(PermissionRelation.ADMIN, favorite = true),
                    plain.metadata.id.toString() to DirectRelation(PermissionRelation.ADMIN, favorite = false),
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
                favoriteService.listDirectRelations(callerId.toString(), EntityType.CASE)
            } returns
                mapOf(
                    adminCase.metadata.id.toString() to DirectRelation(PermissionRelation.ADMIN),
                    memberCase.metadata.id.toString() to DirectRelation(PermissionRelation.MEMBER),
                )
            every { caseService.findConcerningUserInNamespace(callerId, namespaceId) } returns
                listOf(
                    adminCase,
                    memberCase,
                )

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
            every { userService.getCurrentUser() } returns caller
            every { caseService.findConcerningUser(callerId) } returns listOf(case1, case2)

            val result = controller.listByUser(callerId)

            result.map { it.id } shouldBe listOf(case1.metadata.id, case2.metadata.id)
            verify(exactly = 1) { caseService.findConcerningUser(callerId) }
        }

        "listByUser returns empty list when no cases concern the requested user" {
            every { userService.getCurrentUser() } returns caller
            every { caseService.findConcerningUser(callerId) } returns emptyList()

            controller.listByUser(callerId) shouldBe emptyList()
        }

        "listByUser populates lastMessageAt from caseEventService" {
            val withMsg = caseEntity(title = "has messages")
            val noMsg = caseEntity(title = "no messages")
            val msgTimestamp = Instant.parse("2025-06-01T10:00:00Z")
            every { userService.getCurrentUser() } returns caller
            every { caseService.findConcerningUser(callerId) } returns listOf(withMsg, noMsg)
            every {
                caseEventService.findLastMessageTimestamps(listOf(withMsg.id, noMsg.id))
            } returns mapOf(withMsg.id to msgTimestamp)

            val result = controller.listByUser(callerId)

            result.single { it.id == withMsg.metadata.id }.lastMessageAt shouldBe msgTimestamp
            result.single { it.id == noMsg.metadata.id }.lastMessageAt shouldBe null
        }

        "listByUser enriches with favorite when caller is the target user" {
            val starredCase = caseEntity(title = "favorited")
            every { userService.getCurrentUser() } returns caller
            every { caseService.findConcerningUser(callerId) } returns listOf(starredCase)
            every {
                favoriteService.listDirectRelations(callerId.toString(), EntityType.CASE)
            } returns mapOf(starredCase.metadata.id.toString() to DirectRelation(PermissionRelation.MEMBER, favorite = true))

            val result = controller.listByUser(callerId)

            result.single().favorite shouldBe true
        }

        "listByUser skips enrichment when caller is not the target user" {
            val otherId = UUID.randomUUID()
            val otherCase = caseEntity(title = "other user case")
            every { userService.getCurrentUser() } returns caller
            every { caseService.findConcerningUser(otherId) } returns listOf(otherCase)

            val result = controller.listByUser(otherId)

            result.single().favorite shouldBe false
            verify(exactly = 0) { favoriteService.listDirectRelations(any(), any()) }
        }

        // -------------------------------------------------------------------------
        // listByUserExternalId — GET /api/cases/by-user/external/{externalId}
        // -------------------------------------------------------------------------

        "listByUserExternalId populates lastMessageAt from caseEventService" {
            val withMsg = caseEntity(title = "has messages")
            val noMsg = caseEntity(title = "no messages")
            val msgTimestamp = Instant.parse("2025-06-01T10:00:00Z")
            every { userService.findByExternalId(caller.externalId) } returns caller
            every { userService.getCurrentUser() } returns caller
            every { caseService.findConcerningUser(callerId) } returns listOf(withMsg, noMsg)
            every {
                caseEventService.findLastMessageTimestamps(listOf(withMsg.id, noMsg.id))
            } returns mapOf(withMsg.id to msgTimestamp)

            val result = controller.listByUserExternalId(caller.externalId)

            result.single { it.id == withMsg.metadata.id }.lastMessageAt shouldBe msgTimestamp
            result.single { it.id == noMsg.metadata.id }.lastMessageAt shouldBe null
        }

        "listByUserExternalId returns cases concerning the resolved user" {
            val ns2 = UUID.randomUUID()
            val case1 = caseEntity(title = "in ns1")
            val case2 = caseEntity(title = "in ns2").copy(namespaceId = ns2)
            every { userService.findByExternalId(caller.externalId) } returns caller
            every { userService.getCurrentUser() } returns caller
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
            every { userService.getCurrentUser() } returns caller
            every { caseService.findConcerningUser(callerId) } returns emptyList()

            controller.listByUserExternalId(caller.externalId) shouldBe emptyList()
        }

        "listByUserExternalId enriches with favorite when caller is the target user" {
            val starredCase = caseEntity(title = "favorited")
            every { userService.findByExternalId(caller.externalId) } returns caller
            every { userService.getCurrentUser() } returns caller
            every { caseService.findConcerningUser(callerId) } returns listOf(starredCase)
            every {
                favoriteService.listDirectRelations(callerId.toString(), EntityType.CASE)
            } returns mapOf(starredCase.metadata.id.toString() to DirectRelation(PermissionRelation.MEMBER, favorite = true))

            val result = controller.listByUserExternalId(caller.externalId)

            result.single().favorite shouldBe true
        }

        "listByUserExternalId skips enrichment when caller is not the target user" {
            val otherUser =
                caller.copy(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    externalId = "other@example.com",
                    email = "other@example.com",
                )
            val otherCase = caseEntity(title = "other user case")
            every { userService.findByExternalId(otherUser.externalId) } returns otherUser
            every { userService.getCurrentUser() } returns caller
            every { caseService.findConcerningUser(otherUser.id) } returns listOf(otherCase)

            val result = controller.listByUserExternalId(otherUser.externalId)

            result.single().favorite shouldBe false
            verify(exactly = 0) { favoriteService.listDirectRelations(any(), any()) }
        }

        // -------------------------------------------------------------------------
        // listByUserInNamespace — POST /api/cases/by-user/in-namespace
        // -------------------------------------------------------------------------

        "listByUserInNamespace populates lastMessageAt from caseEventService" {
            val withMsg = caseEntity(title = "has messages")
            val noMsg = caseEntity(title = "no messages")
            val msgTimestamp = Instant.parse("2025-06-01T10:00:00Z")
            val namespaceExternalId = "ext-ns-last-msg"
            val namespace =
                io.whozoss.agentos.namespace.Namespace(
                    metadata = EntityMetadata(id = namespaceId),
                    name = "test-ns",
                    externalId = namespaceExternalId,
                )
            every { userService.findByExternalId(caller.externalId) } returns caller
            every { userService.getCurrentUser() } returns caller
            every { namespaceService.findByExternalId(namespaceExternalId) } returns namespace
            every { caseService.findConcerningUserInNamespace(callerId, namespaceId) } returns listOf(withMsg, noMsg)
            every {
                caseEventService.findLastMessageTimestamps(listOf(withMsg.id, noMsg.id))
            } returns mapOf(withMsg.id to msgTimestamp)

            val result =
                controller.listByUserInNamespace(
                    ListByUserInNamespaceRequest(
                        userExternalId = caller.externalId,
                        namespaceExternalId = namespaceExternalId,
                    ),
                )

            result.single { it.id == withMsg.metadata.id }.lastMessageAt shouldBe msgTimestamp
            result.single { it.id == noMsg.metadata.id }.lastMessageAt shouldBe null
        }

        "listByUserInNamespace returns only cases in the requested namespace" {
            val caseInNs = caseEntity(title = "in ns")
            val namespaceExternalId = "ext-ns-1"
            val namespace =
                io.whozoss.agentos.namespace.Namespace(
                    metadata = EntityMetadata(id = namespaceId),
                    name = "test-ns",
                    externalId = namespaceExternalId,
                )
            every { userService.findByExternalId(caller.externalId) } returns caller
            every { userService.getCurrentUser() } returns caller
            every { namespaceService.findByExternalId(namespaceExternalId) } returns namespace
            every { caseService.findConcerningUserInNamespace(callerId, namespaceId) } returns listOf(caseInNs)

            val result =
                controller.listByUserInNamespace(
                    ListByUserInNamespaceRequest(
                        userExternalId = caller.externalId,
                        namespaceExternalId = namespaceExternalId,
                    ),
                )

            result.map { it.id } shouldBe listOf(caseInNs.metadata.id)
            verify(exactly = 1) { caseService.findConcerningUserInNamespace(callerId, namespaceId) }
            verify(exactly = 0) { caseService.findConcerningUser(any()) }
        }

        "listByUserInNamespace enriches with favorite when caller is the target user" {
            val starredCase = caseEntity(title = "favorited")
            val plainCase = caseEntity(title = "plain")
            val namespaceExternalId = "ext-ns-fav"
            val namespace =
                io.whozoss.agentos.namespace.Namespace(
                    metadata = EntityMetadata(id = namespaceId),
                    name = "test-ns",
                    externalId = namespaceExternalId,
                )
            every { userService.findByExternalId(caller.externalId) } returns caller
            every { userService.getCurrentUser() } returns caller
            every { namespaceService.findByExternalId(namespaceExternalId) } returns namespace
            every { caseService.findConcerningUserInNamespace(callerId, namespaceId) } returns listOf(starredCase, plainCase)
            every {
                favoriteService.listDirectRelations(callerId.toString(), EntityType.CASE)
            } returns mapOf(starredCase.metadata.id.toString() to DirectRelation(PermissionRelation.MEMBER, favorite = true))

            val result =
                controller.listByUserInNamespace(
                    ListByUserInNamespaceRequest(
                        userExternalId = caller.externalId,
                        namespaceExternalId = namespaceExternalId,
                    ),
                )

            result.single { it.id == starredCase.metadata.id }.favorite shouldBe true
            result.single { it.id == plainCase.metadata.id }.favorite shouldBe false
        }

        "listByUserInNamespace returns empty list when user has no cases in the namespace" {
            val namespaceExternalId = "ext-ns-empty"
            val namespace =
                io.whozoss.agentos.namespace.Namespace(
                    metadata = EntityMetadata(id = namespaceId),
                    name = "test-ns",
                    externalId = namespaceExternalId,
                )
            every { userService.findByExternalId(caller.externalId) } returns caller
            every { userService.getCurrentUser() } returns caller
            every { namespaceService.findByExternalId(namespaceExternalId) } returns namespace
            every { caseService.findConcerningUserInNamespace(callerId, namespaceId) } returns emptyList()

            controller.listByUserInNamespace(
                ListByUserInNamespaceRequest(
                    userExternalId = caller.externalId,
                    namespaceExternalId = namespaceExternalId,
                ),
            ) shouldBe emptyList()
        }

        "listByUserInNamespace skips enrichment when caller is not the target user" {
            val otherUser =
                caller.copy(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    externalId = "other@example.com",
                    email = "other@example.com",
                )
            val namespaceExternalId = "ext-ns-other"
            val namespace =
                io.whozoss.agentos.namespace.Namespace(
                    metadata = EntityMetadata(id = namespaceId),
                    name = "test-ns",
                    externalId = namespaceExternalId,
                )
            val otherCase = caseEntity(title = "other user case")
            every { userService.findByExternalId(otherUser.externalId) } returns otherUser
            every { userService.getCurrentUser() } returns caller
            every { namespaceService.findByExternalId(namespaceExternalId) } returns namespace
            every { caseService.findConcerningUserInNamespace(otherUser.id, namespaceId) } returns listOf(otherCase)

            val result =
                controller.listByUserInNamespace(
                    ListByUserInNamespaceRequest(
                        userExternalId = otherUser.externalId,
                        namespaceExternalId = namespaceExternalId,
                    ),
                )

            result.single().favorite shouldBe false
            verify(exactly = 0) { favoriteService.listDirectRelations(any(), any()) }
        }

        "listByUserInNamespace throws 404 when no user matches the external id" {
            every { userService.findByExternalId("unknown@example.com") } returns null

            shouldThrow<io.whozoss.agentos.exception.ResourceNotFoundException> {
                controller.listByUserInNamespace(
                    ListByUserInNamespaceRequest(userExternalId = "unknown@example.com", namespaceExternalId = "any"),
                )
            }
        }

        "listByUserInNamespace throws 404 when no namespace matches the namespaceExternalId" {
            every { userService.findByExternalId(caller.externalId) } returns caller
            every { namespaceService.findByExternalId("unknown-ns") } returns null

            shouldThrow<io.whozoss.agentos.exception.ResourceNotFoundException> {
                controller.listByUserInNamespace(
                    ListByUserInNamespaceRequest(
                        userExternalId = caller.externalId,
                        namespaceExternalId = "unknown-ns",
                    ),
                )
            }
        }

        "listByParent short-circuits for super-admin (hasPermission WRITE returns true via bypass)" {
            val superAdmin = caller.copy(isAdmin = true)
            val case1 = caseEntity()
            every { userService.getCurrentUser() } returns superAdmin
            every {
                permissionService.hasPermission(
                    callerId.toString(),
                    EntityType.NAMESPACE,
                    namespaceId.toString(),
                    Action.WRITE,
                )
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
            val payload =
                caseResource(id = existing.metadata.id, title = "renamed")
                    .copy(namespaceId = otherNs, status = CaseStatus.RUNNING)
            every { userService.getCurrentUser() } returns caller
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

        "update populates lastMessageAt from caseEventService" {
            val existing = caseEntity()
            val msgTimestamp = Instant.parse("2025-06-01T10:00:00Z")
            every { userService.getCurrentUser() } returns caller
            every { caseService.findById(existing.metadata.id) } returns existing
            every { caseService.update(any()) } returns existing
            every {
                caseEventService.findLastMessageTimestamps(listOf(existing.id))
            } returns mapOf(existing.id to msgTimestamp)

            val result = controller.update(existing.metadata.id, caseResource(id = existing.metadata.id))

            result.lastMessageAt shouldBe msgTimestamp
        }

        "update populates favorite and role from favoriteService" {
            val existing = caseEntity()
            every { userService.getCurrentUser() } returns caller
            every { caseService.findById(existing.metadata.id) } returns existing
            every { caseService.update(any()) } returns existing
            every {
                favoriteService.listDirectRelations(callerId.toString(), EntityType.CASE)
            } returns mapOf(existing.metadata.id.toString() to DirectRelation(PermissionRelation.MEMBER, favorite = true))

            val result = controller.update(existing.metadata.id, caseResource(id = existing.metadata.id))

            result.favorite shouldBe true
            result.role shouldBe "MEMBER"
        }

        // -------------------------------------------------------------------------
        // getById
        // -------------------------------------------------------------------------

        "getById populates lastMessageAt from caseEventService" {
            val entity = caseEntity()
            val msgTimestamp = Instant.parse("2025-06-01T10:00:00Z")
            every { userService.getCurrentUser() } returns caller
            every { caseService.getById(entity.metadata.id) } returns entity
            every {
                caseEventService.findLastMessageTimestamps(listOf(entity.id))
            } returns mapOf(entity.id to msgTimestamp)

            val result = controller.getById(entity.metadata.id)

            result.id shouldBe entity.metadata.id
            result.lastMessageAt shouldBe msgTimestamp
        }

        "getById returns null lastMessageAt when case has no messages" {
            val entity = caseEntity()
            every { userService.getCurrentUser() } returns caller
            every { caseService.getById(entity.metadata.id) } returns entity

            val result = controller.getById(entity.metadata.id)

            result.lastMessageAt shouldBe null
        }

        "getById sets favorite=true when the caller has favorited the case" {
            val entity = caseEntity()
            every { userService.getCurrentUser() } returns caller
            every { caseService.getById(entity.metadata.id) } returns entity
            every {
                favoriteService.listDirectRelations(callerId.toString(), EntityType.CASE)
            } returns mapOf(entity.metadata.id.toString() to DirectRelation(PermissionRelation.ADMIN, favorite = true))

            val result = controller.getById(entity.metadata.id)

            result.favorite shouldBe true
            result.role shouldBe "ADMIN"
        }

        "getById sets favorite=false and role=null when the caller has no direct relation" {
            val entity = caseEntity()
            every { userService.getCurrentUser() } returns caller
            every { caseService.getById(entity.metadata.id) } returns entity
            // default mock: emptyMap()

            val result = controller.getById(entity.metadata.id)

            result.favorite shouldBe false
            result.role shouldBe null
        }

        // -------------------------------------------------------------------------
        // getByIds
        // -------------------------------------------------------------------------

        "getByIds populates lastMessageAt for cases that have messages" {
            val withMsg = caseEntity(title = "has messages")
            val noMsg = caseEntity(title = "no messages")
            val msgTimestamp = Instant.parse("2025-06-01T10:00:00Z")
            every { userService.getCurrentUser() } returns caller
            every { caseService.findByIds(any(), any()) } returns listOf(withMsg, noMsg)
            every {
                caseEventService.findLastMessageTimestamps(listOf(withMsg.id, noMsg.id))
            } returns mapOf(withMsg.id to msgTimestamp)

            val result =
                controller.getByIds(
                    io.whozoss.agentos.sdk.api.common
                        .GetByIdsRequest(listOf(withMsg.metadata.id, noMsg.metadata.id)),
                )

            result.single { it.id == withMsg.metadata.id }.lastMessageAt shouldBe msgTimestamp
            result.single { it.id == noMsg.metadata.id }.lastMessageAt shouldBe null
        }

        "getByIds populates favorite from favoriteService for the current user" {
            val starred = caseEntity(title = "favorited")
            val plain = caseEntity(title = "plain")
            every { userService.getCurrentUser() } returns caller
            every { caseService.findByIds(any(), any()) } returns listOf(starred, plain)
            every {
                favoriteService.listDirectRelations(callerId.toString(), EntityType.CASE)
            } returns mapOf(starred.metadata.id.toString() to DirectRelation(PermissionRelation.ADMIN, favorite = true))

            val result =
                controller.getByIds(
                    io.whozoss.agentos.sdk.api.common
                        .GetByIdsRequest(listOf(starred.metadata.id, plain.metadata.id)),
                )

            result.single { it.id == starred.metadata.id }.favorite shouldBe true
            result.single { it.id == plain.metadata.id }.favorite shouldBe false
        }

        // -------------------------------------------------------------------------
        // markCaseRead — POST /api/cases/{caseId}/read
        // -------------------------------------------------------------------------

        "markCaseRead delegates to caseReadService for the current user" {
            val caseId = UUID.randomUUID()
            every { userService.getCurrentUser() } returns caller
            every { caseReadService.markRead(callerId.toString(), caseId) } returns Unit

            controller.markCaseRead(caseId)

            verify(exactly = 1) { caseReadService.markRead(callerId.toString(), caseId) }
        }

        // -------------------------------------------------------------------------
        // countUnread — GET /api/cases/unread-count?namespaceId=
        // -------------------------------------------------------------------------

        "countUnread delegates to caseReadService and returns the count" {
            every { userService.getCurrentUser() } returns caller
            every { caseReadService.countUnread(callerId.toString(), namespaceId) } returns 3L

            val result = controller.countUnread(namespaceId)

            result shouldBe UnreadCountResponse(unreadCount = 3L)
            verify(exactly = 1) { caseReadService.countUnread(callerId.toString(), namespaceId) }
        }

        "countUnread returns zero when all cases are read" {
            every { userService.getCurrentUser() } returns caller
            every { caseReadService.countUnread(callerId.toString(), namespaceId) } returns 0L

            val result = controller.countUnread(namespaceId)

            result shouldBe UnreadCountResponse(unreadCount = 0L)
        }

        // -------------------------------------------------------------------------
        // readAt — populated via withCallerMeta
        // -------------------------------------------------------------------------

        "getById populates readAt from the user's WATCHES edge" {
            val entity = caseEntity()
            val readTimestamp = Instant.parse("2025-06-01T12:00:00Z")
            every { userService.getCurrentUser() } returns caller
            every { caseService.getById(entity.metadata.id) } returns entity
            every {
                favoriteService.listDirectRelations(callerId.toString(), EntityType.CASE)
            } returns mapOf(entity.metadata.id.toString() to DirectRelation(PermissionRelation.MEMBER, readAt = readTimestamp))

            val result = controller.getById(entity.metadata.id)

            result.readAt shouldBe readTimestamp
        }

        "getById returns null readAt when case has never been read" {
            val entity = caseEntity()
            every { userService.getCurrentUser() } returns caller
            every { caseService.getById(entity.metadata.id) } returns entity
            // default mock: emptyMap() — no DirectRelation entry

            val result = controller.getById(entity.metadata.id)

            result.readAt shouldBe null
        }
    })
