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
import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import java.util.UUID

/**
 * Unit tests for the unified [IntegrationConfigController].
 *
 * Covers all three scopes (NS-shared, user × namespace, user-global) on the
 * single CRUD route set — the user-scope cases were absorbed from
 * `UserIntegrationConfigControllerSpec.kt` per the test-migration-checklist
 * (`_bmad-output/implementation-artifacts/test-migration-checklist.md`).
 *
 * The controller reads `auth.principal` from `SecurityContextHolder` for `create()`
 * (signature is fixed by [io.whozoss.agentos.entity.EntityController]). [withAuth]
 * sets / clears the context per test.
 *
 * MVC-layer wiring is verified in [IntegrationConfigControllerMvcIntegrationSpec]
 * and [IntegrationConfigCrossUserIsolationSpec].
 */
class IntegrationConfigControllerSpec : StringSpec({

    val service = mockk<IntegrationConfigService>()
    val namespaceService = mockk<NamespaceService>(relaxed = true)
    val userService = mockk<UserService>(relaxed = true)
    val permissionService = mockk<PermissionService>(relaxed = true)
    val controller = IntegrationConfigController(service, namespaceService, userService, permissionService)

    val namespaceId = UUID.randomUUID()
    val aliceId = UUID.randomUUID()
    val bobId = UUID.randomUUID()
    val params = JsonNodeFactory.instance.objectNode().put("apiUrl", "https://example.com")

    fun aliceUser(isAdmin: Boolean = false) = User(
        metadata = EntityMetadata(id = aliceId),
        externalId = "alice@example.com",
        email = "alice@example.com",
        isAdmin = isAdmin,
    )

    fun authFor(userId: UUID): Authentication =
        UsernamePasswordAuthenticationToken(userId.toString(), "n/a", emptyList())

    fun <T> withAuth(userId: UUID, block: () -> T): T {
        val previous = SecurityContextHolder.getContext().authentication
        SecurityContextHolder.getContext().authentication = authFor(userId)
        return try {
            block()
        } finally {
            SecurityContextHolder.getContext().authentication = previous
        }
    }

    fun config(
        id: UUID = UUID.randomUUID(),
        nsId: UUID? = namespaceId,
        userId: UUID? = null,
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
        id: UUID? = UUID.randomUUID(),
        nsId: UUID? = namespaceId,
        userId: UUID? = null,
        name: String = "JIRA_PROD",
        integrationType: String = "JIRA",
    ) = IntegrationConfigResource(
        id = id,
        namespaceId = nsId,
        userId = userId,
        name = name,
        integrationType = integrationType,
        description = null,
        parameters = params,
    )

    val existingNamespace = Namespace(
        metadata = EntityMetadata(id = namespaceId),
        externalId = "ns-${namespaceId}",
        name = "ns",
    )

    beforeTest {
        clearAllMocks()
        every { namespaceService.findById(namespaceId) } returns existingNamespace
        every { userService.getCurrentUser() } returns aliceUser()
    }

    // -------------------------------------------------------------------------
    // toResource — mapping
    // -------------------------------------------------------------------------

    "toResource maps id, namespaceId, userId, name, integrationType, description, parameters" {
        val cfg = config(name = "SLACK_DEV", integrationType = "SLACK", userId = aliceId).copy(description = "Dev Slack")
        val r = controller.toResource(cfg)

        r.id shouldBe cfg.metadata.id
        r.namespaceId shouldBe namespaceId
        r.userId shouldBe aliceId
        r.name shouldBe "SLACK_DEV"
        r.integrationType shouldBe "SLACK"
        r.description shouldBe "Dev Slack"
        r.parameters shouldBe params
    }

    "toResource maps all fields" {
        val c = config(name = "SLACK_DEV", integrationType = "SLACK").copy(description = "Dev Slack")
        val r = controller.toResource(c)

        r.name shouldBe "SLACK_DEV"
        r.integrationType shouldBe "SLACK"
        r.description shouldBe "Dev Slack"
        r.parameters shouldBe params
    }

    "toDomain maps all fields and generates UUID when id is null" {
        val first = controller.toDomain(resource(id = null))
        val second = controller.toDomain(resource(id = null))

        (first.metadata.id == second.metadata.id) shouldBe false
    }

    // -------------------------------------------------------------------------
    // create — Phase 1 mass-assignment guard
    // -------------------------------------------------------------------------

    "create rejects body.userId mismatched with authenticated principal with 400" {
        withAuth(aliceId) {
            shouldThrow<BadRequestException> {
                controller.create(resource(id = null, nsId = null, userId = bobId))
            }
        }
        verify(exactly = 0) { service.create(any()) }
    }

    "create with neither namespaceId nor userId and non-admin user throws AccessDeniedException (platform scope)" {
        withAuth(aliceId) {
            shouldThrow<org.springframework.security.access.AccessDeniedException> {
                controller.create(resource(id = null, nsId = null, userId = null))
            }
        }
        verify(exactly = 0) { service.create(any()) }
    }

    "create platform scope (null, null) succeeds for Super Admin" {
        every { userService.getCurrentUser() } returns aliceUser(isAdmin = true)
        val captured = slot<IntegrationConfig>()
        every { service.create(capture(captured)) } answers { firstArg() }

        withAuth(aliceId) { controller.create(resource(id = null, nsId = null, userId = null)) }

        captured.captured.namespaceId shouldBe null
        captured.captured.userId shouldBe null
        verify(exactly = 0) { permissionService.hasPermission(any(), any(), any(), any()) }
    }

    // -------------------------------------------------------------------------
    // create — Phase 3.5 namespace existence (now AFTER Phase 3 authz)
    // -------------------------------------------------------------------------

    "create with dangling namespaceId returns 404 only when authz passes (avoid existence leak)" {
        val unknownNs = UUID.randomUUID()
        every { namespaceService.findById(unknownNs) } returns null
        every {
            permissionService.hasPermission(aliceId.toString(), EntityType.NAMESPACE, unknownNs.toString(), Action.WRITE)
        } returns true

        withAuth(aliceId) {
            shouldThrow<ResourceNotFoundException> {
                controller.create(resource(id = null, nsId = unknownNs, userId = null))
            }
        }
        verify(exactly = 0) { service.create(any()) }
    }

    "create with dangling namespaceId for a non-member surfaces as AccessDenied (no 404 leak)" {
        val unknownNs = UUID.randomUUID()
        every { namespaceService.findById(unknownNs) } returns null

        shouldThrow<org.springframework.security.access.AccessDeniedException> {
            withAuth(aliceId) {
                controller.create(resource(id = null, nsId = unknownNs, userId = null))
            }
        }
        verify(exactly = 0) { service.create(any()) }
    }

    // -------------------------------------------------------------------------
    // create — Phase 3 per-scope authz + Phase 4 explicit domain build
    // -------------------------------------------------------------------------

    "create NS-shared (namespaceId only) requires WRITE on namespace and persists with userId=null" {
        every {
            permissionService.hasPermission(aliceId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.WRITE)
        } returns true
        val captured = slot<IntegrationConfig>()
        every { service.create(capture(captured)) } answers { firstArg() }

        withAuth(aliceId) { controller.create(resource(id = null, nsId = namespaceId, userId = null, name = "shared")) }

        captured.captured.namespaceId shouldBe namespaceId
        captured.captured.userId shouldBe null
    }

    "create NS-shared without WRITE permission throws AccessDeniedException" {
        every {
            permissionService.hasPermission(aliceId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.WRITE)
        } returns false

        shouldThrow<org.springframework.security.access.AccessDeniedException> {
            withAuth(aliceId) { controller.create(resource(id = null, nsId = namespaceId, userId = null)) }
        }
        verify(exactly = 0) { service.create(any()) }
    }

    "create user-global skips namespace permission check" {
        val captured = slot<IntegrationConfig>()
        every { service.create(capture(captured)) } answers { firstArg() }

        withAuth(aliceId) { controller.create(resource(id = null, nsId = null, userId = aliceId)) }

        captured.captured.namespaceId shouldBe null
        captured.captured.userId shouldBe aliceId
        verify(exactly = 0) { permissionService.hasPermission(any(), any(), any(), any()) }
    }

    "create user-namespace checks namespace READ permission" {
        every {
            permissionService.hasPermission(aliceId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.READ)
        } returns true
        every { service.create(any()) } answers { firstArg() }

        withAuth(aliceId) { controller.create(resource(id = null, nsId = namespaceId, userId = aliceId)) }

        verify(exactly = 1) {
            permissionService.hasPermission(aliceId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.READ)
        }
    }

    "create user-namespace without READ permission throws AccessDeniedException" {
        every {
            permissionService.hasPermission(aliceId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.READ)
        } returns false

        shouldThrow<org.springframework.security.access.AccessDeniedException> {
            withAuth(aliceId) { controller.create(resource(id = null, nsId = namespaceId, userId = aliceId)) }
        }
        verify(exactly = 0) { service.create(any()) }
    }

    // -------------------------------------------------------------------------
    // update — server-owned-field preservation (mass-assignment guard)
    // -------------------------------------------------------------------------

    "update preserves the persisted namespaceId when client sends a different value" {
        val c = config()
        val otherNs = UUID.randomUUID()
        val payload = resource(id = c.metadata.id, nsId = otherNs, name = "RENAMED")
        every { service.findById(c.metadata.id) } returns c
        every { service.update(any()) } answers {
            val saved = firstArg<IntegrationConfig>()
            saved.namespaceId shouldBe namespaceId
            saved.name shouldBe "RENAMED"
            saved
        }

        controller.update(c.metadata.id, payload)

        verify(exactly = 1) { service.update(any()) }
    }

    "update preserves immutable fields even when body sets others" {
        val cfg = config(userId = aliceId)
        val captured = slot<IntegrationConfig>()
        every { service.findById(cfg.metadata.id) } returns cfg
        every { service.update(capture(captured)) } answers { firstArg() }

        controller.update(
            id = cfg.metadata.id,
            resource = resource(
                id = UUID.randomUUID(), // attempted id swap
                nsId = UUID.randomUUID(), // attempted ns swap
                userId = bobId, // attempted ownership transfer
                name = "RENAMED",
                integrationType = "ATTACKER",
            ),
        )

        captured.captured.metadata.id shouldBe cfg.metadata.id
        captured.captured.namespaceId shouldBe namespaceId
        captured.captured.userId shouldBe aliceId
        captured.captured.integrationType shouldBe "JIRA"
        captured.captured.name shouldBe "RENAMED"
    }

    "update body integrationType is silently ignored, existing value preserved" {
        val cfg = config(userId = aliceId, integrationType = "JIRA")
        val captured = slot<IntegrationConfig>()
        every { service.findById(cfg.metadata.id) } returns cfg
        every { service.update(capture(captured)) } answers { firstArg() }

        controller.update(cfg.metadata.id, resource(id = cfg.metadata.id, integrationType = "SLACK"))

        captured.captured.integrationType shouldBe "JIRA"
    }

    "update throws 404 when the IntegrationConfig does not exist" {
        val id = UUID.randomUUID()
        every { service.findById(id) } returns null

        shouldThrow<ResourceNotFoundException> { controller.update(id, resource(id = id)) }
    }

    // -------------------------------------------------------------------------
    // list — three modes, mass-assignment guard
    // -------------------------------------------------------------------------

    "list without namespace filter and userId=me returns caller's own rows" {
        val rows = listOf(
            config(nsId = null, userId = aliceId, name = "GLOBAL_JIRA"),
            config(nsId = namespaceId, userId = aliceId, name = "NS_JIRA"),
        )
        every { service.findFiltered(any(), any(), any(), any(), any()) } returns rows

        val resp = controller.list(namespaceId = null, userId = "me", auth = authFor(aliceId))

        resp.size shouldBe 2
        resp.map { it.name } shouldContainExactlyInAnyOrder listOf("GLOBAL_JIRA", "NS_JIRA")
    }

    "list without any param returns platform configs for any authenticated user" {
        val rows = listOf(
            config(nsId = null, userId = null, name = "PLATFORM_JIRA"),
        )
        every { service.findPlatform() } returns rows

        val resp = controller.list(namespaceId = null, userId = null, auth = authFor(aliceId))

        resp.map { it.name } shouldBe listOf("PLATFORM_JIRA")
    }

    "list with namespaceId=none returns only user-global rows" {
        val rows = listOf(
            config(nsId = null, userId = aliceId, name = "GLOBAL"),
        )
        every { service.findFiltered(any(), any(), any(), any(), any()) } returns rows

        val resp = controller.list(namespaceId = "none", userId = "me", auth = authFor(aliceId))

        resp.map { it.name } shouldBe listOf("GLOBAL")
    }

    "list with namespaceId=NONE (uppercase) is also user-global" {
        val rows = listOf(
            config(nsId = null, userId = aliceId, name = "GLOBAL"),
        )
        every { service.findFiltered(any(), any(), any(), any(), any()) } returns rows

        val resp = controller.list(namespaceId = "NONE", userId = "me", auth = authFor(aliceId))

        resp.map { it.name } shouldBe listOf("GLOBAL")
    }

    "list with specific namespaceId and userId=me returns only that namespace's user rows" {
        val rows = listOf(
            config(nsId = namespaceId, userId = aliceId, name = "NS"),
        )
        every { service.findFiltered(any(), any(), any(), any(), any()) } returns rows

        val resp = controller.list(
            namespaceId = namespaceId.toString(),
            userId = "me",
            auth = authFor(aliceId),
        )

        resp.map { it.name } shouldBe listOf("NS")
    }

    "list with specific namespaceId and no userId returns NS-shared rows" {
        val rows = listOf(
            config(nsId = namespaceId, userId = null, name = "NS-A"),
            config(nsId = namespaceId, userId = null, name = "NS-B"),
        )
        every { service.findFiltered(any(), any(), any(), any(), any()) } returns rows

        val resp = controller.list(
            namespaceId = namespaceId.toString(),
            userId = null,
            auth = authFor(aliceId),
        )

        resp.map { it.name } shouldContainExactlyInAnyOrder listOf("NS-A", "NS-B")
    }

    "list NS-shared without READ on the namespace returns empty (no 403)" {
        every { service.findFiltered(any(), any(), any(), any(), any()) } returns emptyList()

        val resp = controller.list(
            namespaceId = namespaceId.toString(),
            userId = null,
            auth = authFor(aliceId),
        )

        resp shouldBe emptyList()
    }

    "list rejects ?userId=<uuid> with 400 (only the 'me' sentinel is exposed)" {
        shouldThrow<BadRequestException> {
            controller.list(namespaceId = null, userId = bobId.toString(), auth = authFor(aliceId))
        }
    }

    "list with invalid namespaceId throws 400 BAD_REQUEST" {
        shouldThrow<BadRequestException> {
            controller.list(namespaceId = "not-a-uuid-and-not-none", userId = null, auth = authFor(aliceId))
        }
    }
})
