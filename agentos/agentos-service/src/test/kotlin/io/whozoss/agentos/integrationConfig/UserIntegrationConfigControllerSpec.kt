package io.whozoss.agentos.integrationConfig

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.Authentication
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Unit tests for [UserIntegrationConfigController].
 *
 * Exercises the controller logic directly with mocks — Bean Validation, MVC routing,
 * and the @HideOnAccessDenied → 404 translation are verified by the MVC integration spec.
 *
 * Note on auth handling: `Authentication.name` is the user's UUID-as-String (per
 * [io.whozoss.agentos.security.declarative.AgentOsAuthentication]). We mock that contract
 * directly without spinning up a SecurityContext.
 */
class UserIntegrationConfigControllerSpec : StringSpec({

    val service = mockk<IntegrationConfigService>()
    val permissionService = mockk<PermissionService>(relaxed = true)
    val guard = UserIntegrationConfigGuard(permissionService)
    val controller = UserIntegrationConfigController(service, guard)

    val aliceId = UUID.randomUUID()
    val bobId = UUID.randomUUID()
    val namespaceId = UUID.randomUUID()
    val params = JsonNodeFactory.instance.objectNode().put("apiUrl", "https://example.com")

    fun authFor(userId: UUID): Authentication {
        val auth = mockk<Authentication>(relaxed = true)
        every { auth.name } returns userId.toString()
        return auth
    }

    fun config(
        id: UUID = UUID.randomUUID(),
        nsId: UUID? = namespaceId,
        userId: UUID? = aliceId,
        name: String = "JIRA_PROD",
        integrationType: String = "JIRA",
    ) = IntegrationConfig(
        metadata = EntityMetadata(id = id),
        namespaceId = nsId,
        userId = userId,
        name = name,
        integrationType = integrationType,
        description = null,
        parameters = params,
    )

    fun resource(
        id: UUID? = null,
        nsId: UUID? = namespaceId,
        userId: UUID? = null,
        name: String = "JIRA_PROD",
        integrationType: String = "JIRA",
    ) = UserIntegrationConfigResource(
        id = id,
        namespaceId = nsId,
        userId = userId,
        name = name,
        integrationType = integrationType,
        description = null,
        parameters = params,
    )

    beforeTest { clearAllMocks() }

    // -------------------------------------------------------------------------
    // 1) toResource maps every field
    // -------------------------------------------------------------------------
    "toResource maps id, namespaceId, userId, name, integrationType, description, parameters" {
        val cfg = config(name = "SLACK_DEV", integrationType = "SLACK").copy(description = "Dev Slack")
        val r = controller.toResource(cfg)

        r.id shouldBe cfg.metadata.id
        r.namespaceId shouldBe namespaceId
        r.userId shouldBe aliceId
        r.name shouldBe "SLACK_DEV"
        r.integrationType shouldBe "SLACK"
        r.description shouldBe "Dev Slack"
        r.parameters shouldBe params
    }

    // -------------------------------------------------------------------------
    // 2) Mass-assignment guard on create — userId from body is ignored
    // -------------------------------------------------------------------------
    "create forces userId=auth.name and ignores body.userId" {
        val auth = authFor(aliceId)
        val captured = slot<IntegrationConfig>()
        every { service.create(capture(captured)) } answers { firstArg() }
        every { permissionService.hasPermission(aliceId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.READ) } returns true

        controller.create(resource(userId = bobId), auth) // body says bob, auth says alice

        captured.captured.userId shouldBe aliceId
    }

    // -------------------------------------------------------------------------
    // 3) Create user-global (namespaceId == null) — no namespace permission check
    // -------------------------------------------------------------------------
    "create user-global skips namespace permission check" {
        val auth = authFor(aliceId)
        val captured = slot<IntegrationConfig>()
        every { service.create(capture(captured)) } answers { firstArg() }

        controller.create(resource(nsId = null), auth)

        captured.captured.namespaceId shouldBe null
        captured.captured.userId shouldBe aliceId
        verify(exactly = 0) { permissionService.hasPermission(any(), any(), any(), any()) }
    }

    // -------------------------------------------------------------------------
    // 4) Create user×namespace — checks hasPermission(NAMESPACE, READ)
    // -------------------------------------------------------------------------
    "create user-namespace checks namespace READ permission" {
        val auth = authFor(aliceId)
        every { service.create(any()) } answers { firstArg() }
        every {
            permissionService.hasPermission(aliceId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.READ)
        } returns true

        controller.create(resource(), auth)

        verify(exactly = 1) {
            permissionService.hasPermission(aliceId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.READ)
        }
    }

    // -------------------------------------------------------------------------
    // 5) Create user×namespace without permission → 403
    // -------------------------------------------------------------------------
    "create user-namespace without READ permission throws 403" {
        val auth = authFor(aliceId)
        every {
            permissionService.hasPermission(aliceId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.READ)
        } returns false

        val ex = shouldThrow<ResponseStatusException> { controller.create(resource(), auth) }
        ex.statusCode.value() shouldBe 403
        verify(exactly = 0) { service.create(any()) }
    }

    // -------------------------------------------------------------------------
    // 6) getById happy
    // -------------------------------------------------------------------------
    "getById returns the resource when caller owns the row" {
        val cfg = config(userId = aliceId)
        every { service.findById(cfg.metadata.id) } returns cfg

        val r = controller.getById(cfg.metadata.id, authFor(aliceId))

        r.id shouldBe cfg.metadata.id
        r.userId shouldBe aliceId
    }

    // -------------------------------------------------------------------------
    // 7) getById cross-user — AccessDeniedException (translated to 404 in MVC)
    // -------------------------------------------------------------------------
    "getById cross-user throws AccessDeniedException" {
        val cfg = config(userId = bobId)
        every { service.findById(cfg.metadata.id) } returns cfg

        shouldThrow<AccessDeniedException> { controller.getById(cfg.metadata.id, authFor(aliceId)) }
    }

    // -------------------------------------------------------------------------
    // 8) getById absent — AccessDeniedException (NOT a ResourceNotFoundException),
    //    so @HideOnAccessDenied returns 404 indistinguishably from cross-user.
    // -------------------------------------------------------------------------
    "getById on missing row throws AccessDeniedException (not 404 directly)" {
        val id = UUID.randomUUID()
        every { service.findById(id) } returns null

        shouldThrow<AccessDeniedException> { controller.getById(id, authFor(aliceId)) }
    }

    // -------------------------------------------------------------------------
    // 9) list without filter — returns both modes
    // -------------------------------------------------------------------------
    "list without namespace filter returns rows for both modes" {
        val rows = listOf(
            config(nsId = null, userId = aliceId, name = "GLOBAL_JIRA"),
            config(nsId = namespaceId, userId = aliceId, name = "NS_JIRA"),
        )
        every { service.findByUserId(aliceId) } returns rows

        val resp = controller.list(namespaceId = null, page = 0, size = 20, auth = authFor(aliceId))

        resp.totalElements shouldBe 2
        resp.content.map { it.name } shouldContainExactlyInAnyOrder listOf("GLOBAL_JIRA", "NS_JIRA")
    }

    // -------------------------------------------------------------------------
    // 10) list with namespaceId=none — only user-global rows
    // -------------------------------------------------------------------------
    "list with namespaceId=none returns only user-global rows" {
        val rows = listOf(
            config(nsId = null, userId = aliceId, name = "GLOBAL"),
            config(nsId = namespaceId, userId = aliceId, name = "NS"),
        )
        every { service.findByUserId(aliceId) } returns rows

        val resp = controller.list(namespaceId = "none", page = 0, size = 20, auth = authFor(aliceId))

        resp.content.map { it.name } shouldBe listOf("GLOBAL")
    }

    // -------------------------------------------------------------------------
    // 11) list with namespaceId=UUID — only that namespace
    // -------------------------------------------------------------------------
    "list with specific namespaceId returns only that namespace's rows" {
        val otherNs = UUID.randomUUID()
        val rows = listOf(
            config(nsId = null, userId = aliceId, name = "GLOBAL"),
            config(nsId = namespaceId, userId = aliceId, name = "NS"),
            config(nsId = otherNs, userId = aliceId, name = "OTHER"),
        )
        every { service.findByUserId(aliceId) } returns rows

        val resp = controller.list(namespaceId = namespaceId.toString(), page = 0, size = 20, auth = authFor(aliceId))

        resp.content.map { it.name } shouldBe listOf("NS")
    }

    // -------------------------------------------------------------------------
    // 12) list ignores client-supplied userId — service.findByUserId always called
    //     with auth.name
    // -------------------------------------------------------------------------
    "list always queries by auth.name regardless of any client param" {
        every { service.findByUserId(aliceId) } returns emptyList()

        controller.list(namespaceId = null, page = 0, size = 20, auth = authFor(aliceId))

        verify(exactly = 1) { service.findByUserId(aliceId) }
        verify(exactly = 0) { service.findByUserId(bobId) }
    }

    // -------------------------------------------------------------------------
    // 13) list pagination — page=1 size=2 of a 5-row list returns rows 2-3
    // -------------------------------------------------------------------------
    "list pagination returns the correct slice" {
        val rows = (1..5).map { config(nsId = null, userId = aliceId, name = "N$it") }
        every { service.findByUserId(aliceId) } returns rows

        val resp = controller.list(namespaceId = null, page = 1, size = 2, auth = authFor(aliceId))

        resp.content.map { it.name } shouldBe listOf("N3", "N4")
        resp.totalElements shouldBe 5
        resp.totalPages shouldBe 3
        resp.page shouldBe 1
        resp.size shouldBe 2
    }

    // -------------------------------------------------------------------------
    // 14) list pagination — size > 100 is capped at 100
    // -------------------------------------------------------------------------
    "list pagination caps size at 100" {
        every { service.findByUserId(aliceId) } returns emptyList()

        val resp = controller.list(namespaceId = null, page = 0, size = 500, auth = authFor(aliceId))

        resp.size shouldBe 100
    }

    // -------------------------------------------------------------------------
    // 15) update preserves userId, namespaceId, integrationType, id
    // -------------------------------------------------------------------------
    "update preserves immutable fields even when body sets others" {
        val cfg = config(userId = aliceId)
        val captured = slot<IntegrationConfig>()
        every { service.findById(cfg.metadata.id) } returns cfg
        every { service.update(capture(captured)) } answers { firstArg() }

        controller.update(
            id = cfg.metadata.id,
            body = resource(
                id = UUID.randomUUID(), // attempted id swap
                nsId = UUID.randomUUID(), // attempted ns swap
                userId = bobId, // attempted ownership transfer
                name = "RENAMED",
                integrationType = "ATTACKER",
            ),
            auth = authFor(aliceId),
        )

        captured.captured.metadata.id shouldBe cfg.metadata.id
        captured.captured.namespaceId shouldBe namespaceId
        captured.captured.userId shouldBe aliceId
        captured.captured.integrationType shouldBe "JIRA"
        captured.captured.name shouldBe "RENAMED"
    }

    // -------------------------------------------------------------------------
    // 16) update only carries name/description/parameters from the body
    // -------------------------------------------------------------------------
    "update body integrationType is silently ignored, existing value preserved" {
        val cfg = config(userId = aliceId, integrationType = "JIRA")
        val captured = slot<IntegrationConfig>()
        every { service.findById(cfg.metadata.id) } returns cfg
        every { service.update(capture(captured)) } answers { firstArg() }

        controller.update(cfg.metadata.id, resource(integrationType = "SLACK"), authFor(aliceId))

        captured.captured.integrationType shouldBe "JIRA"
    }

    // -------------------------------------------------------------------------
    // 17) update cross-user → AccessDeniedException
    // -------------------------------------------------------------------------
    "update cross-user throws AccessDeniedException" {
        val cfg = config(userId = bobId)
        every { service.findById(cfg.metadata.id) } returns cfg

        shouldThrow<AccessDeniedException> { controller.update(cfg.metadata.id, resource(), authFor(aliceId)) }
        verify(exactly = 0) { service.update(any()) }
    }

    // -------------------------------------------------------------------------
    // 18) update on missing row → AccessDeniedException (existence-hiding)
    // -------------------------------------------------------------------------
    "update on missing row throws AccessDeniedException" {
        val id = UUID.randomUUID()
        every { service.findById(id) } returns null

        shouldThrow<AccessDeniedException> { controller.update(id, resource(), authFor(aliceId)) }
    }

    // -------------------------------------------------------------------------
    // 19) delete happy
    // -------------------------------------------------------------------------
    "delete calls service.delete and returns Unit (204 in MVC)" {
        val cfg = config(userId = aliceId)
        every { service.findById(cfg.metadata.id) } returns cfg
        every { service.delete(cfg.metadata.id) } returns true

        controller.delete(cfg.metadata.id, authFor(aliceId))

        verify(exactly = 1) { service.delete(cfg.metadata.id) }
    }

    // -------------------------------------------------------------------------
    // 20) delete cross-user → AccessDeniedException
    // -------------------------------------------------------------------------
    "delete cross-user throws AccessDeniedException" {
        val cfg = config(userId = bobId)
        every { service.findById(cfg.metadata.id) } returns cfg

        shouldThrow<AccessDeniedException> { controller.delete(cfg.metadata.id, authFor(aliceId)) }
        verify(exactly = 0) { service.delete(any()) }
    }

    // -------------------------------------------------------------------------
    // 21) delete on missing row → AccessDeniedException
    // -------------------------------------------------------------------------
    "delete on missing row throws AccessDeniedException" {
        val id = UUID.randomUUID()
        every { service.findById(id) } returns null

        shouldThrow<AccessDeniedException> { controller.delete(id, authFor(aliceId)) }
    }

    // -------------------------------------------------------------------------
    // 22) sentinel "none" is case-insensitive
    // -------------------------------------------------------------------------
    "list namespaceId=NONE (uppercase) is also user-global" {
        val rows = listOf(
            config(nsId = null, userId = aliceId, name = "GLOBAL"),
            config(nsId = namespaceId, userId = aliceId, name = "NS"),
        )
        every { service.findByUserId(aliceId) } returns rows

        val resp = controller.list(namespaceId = "NONE", page = 0, size = 20, auth = authFor(aliceId))

        resp.content.map { it.name } shouldBe listOf("GLOBAL")
    }

    // -------------------------------------------------------------------------
    // 23) invalid namespaceId (not "none", not a UUID) → 400 BAD_REQUEST
    // -------------------------------------------------------------------------
    "list with invalid namespaceId throws 400 BAD_REQUEST" {
        every { service.findByUserId(aliceId) } returns emptyList()

        val ex = shouldThrow<ResponseStatusException> {
            controller.list(namespaceId = "not-a-uuid-and-not-none", page = 0, size = 20, auth = authFor(aliceId))
        }
        ex.statusCode.value() shouldBe 400
    }
})
