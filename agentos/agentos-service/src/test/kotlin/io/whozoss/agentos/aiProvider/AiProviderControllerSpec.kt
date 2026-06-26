package io.whozoss.agentos.aiProvider

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Unit tests for the unified [AiProviderController].
 *
 * Covers all three scopes (NS-shared, user × namespace, user-global) on the
 * single CRUD route set — the user-scope cases were absorbed from
 * `UserAiProviderControllerSpec.kt` per the test-migration-checklist
 * (`_bmad-output/implementation-artifacts/test-migration-checklist.md`).
 *
 * `auth.principal` enters the controller through `SecurityContextHolder` for
 * `create()` (the override signature is fixed by [io.whozoss.agentos.entity.EntityController]),
 * and through the explicit `auth: Authentication` parameter for `list()`. The helper
 * [withAuth] sets and clears the context per test so the runtime mirrors what
 * Spring Security would inject in production.
 *
 * MVC-layer wiring (Bean Validation, the @HideOnAccessDenied → 404 translation,
 * routing) is verified in [AiProviderControllerIntegrationSpec] and
 * [AiProviderCrossUserIsolationSpec].
 */
class AiProviderControllerSpec : StringSpec({

    val service = mockk<AiProviderService>()
    val namespaceService = mockk<NamespaceService>(relaxed = true)
    val userService = mockk<UserService>(relaxed = true)
    val permissionService = mockk<PermissionService>(relaxed = true)
    val controller = AiProviderController(service, namespaceService, userService, permissionService)

    val namespaceId = UUID.randomUUID()
    val aliceId = UUID.randomUUID()
    val bobId = UUID.randomUUID()

    fun aliceUser() = User(
        metadata = EntityMetadata(id = aliceId),
        externalId = "alice@example.com",
        email = "alice@example.com",
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
        uId: UUID? = null,
        name: String = "anthropic",
        apiType: AiApiType = AiApiType.Anthropic,
        apiKey: String? = null,
    ) = AiProvider(
        metadata = EntityMetadata(id = id),
        namespaceId = nsId,
        userId = uId,
        name = name,
        apiType = apiType,
        apiKey = apiKey,
    )

    fun resource(
        id: UUID? = UUID.randomUUID(),
        nsId: UUID? = namespaceId,
        uId: UUID? = null,
        name: String = "anthropic",
        apiType: AiApiType? = AiApiType.Anthropic,
        apiKey: String? = null,
    ) = AiProviderResource(
        id = id,
        namespaceId = nsId,
        userId = uId,
        name = name,
        apiType = apiType,
        apiKey = apiKey,
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

    "toResource masks a long apiKey" {
        controller.toResource(config(apiKey = "sk-ant-api03-abcdefghijklmnop")).apiKey shouldBe "sk-a****mnop"
    }

    "toResource returns null apiKey when no key is set" {
        controller.toResource(config(apiKey = null)).apiKey.shouldBeNull()
    }

    "toResource maps namespaceId and userId" {
        val uid = UUID.randomUUID()
        val r = controller.toResource(config(nsId = namespaceId, uId = uid))
        r.namespaceId shouldBe namespaceId
        r.userId shouldBe uid
    }

    "toResource maps all fields and masks apiKey" {
        val p = config(name = "MY_OPENAI", apiType = AiApiType.OpenAI, apiKey = "sk-openai-123456789012")
        val r = controller.toResource(p)

        r.id shouldBe p.metadata.id
        r.name shouldBe "MY_OPENAI"
        r.apiType shouldBe AiApiType.OpenAI
        r.apiKey shouldBe maskApiKey("sk-openai-123456789012")
        r.apiKey shouldNotBe "sk-openai-123456789012"
    }

    "toResource with null apiKey returns null apiKey" {
        controller.toResource(config(apiKey = null)).apiKey shouldBe null
    }

    // -------------------------------------------------------------------------
    // create — Phase 1 mass-assignment guard
    // -------------------------------------------------------------------------

    "create rejects body.userId mismatched with authenticated principal with 400" {
        // Migrated from UserAiProviderControllerSpec "create forces userId=auth.name and ignores body.userId"
        // — Decision 15 Phase 1 makes the reject explicit (400) rather than silently overriding.
        withAuth(aliceId) {
            shouldThrow<BadRequestException> {
                controller.create(resource(id = null, nsId = null, uId = bobId))
            }
        }
        verify(exactly = 0) { service.create(any()) }
    }

    "create with neither namespaceId nor userId is platform scope: non-admin gets AccessDeniedException" {
        // Platform scope (both null) requires super-admin. Alice is not admin, so
        // permissionService.hasPermission returns false (default mock) → 403.
        every {
            permissionService.hasPermission(aliceId.toString(), EntityType.AI_PROVIDER, null, Action.WRITE)
        } returns false

        withAuth(aliceId) {
            shouldThrow<org.springframework.security.access.AccessDeniedException> {
                controller.create(resource(id = null, nsId = null, uId = null))
            }
        }
        verify(exactly = 0) { service.create(any()) }
    }

    "create with neither namespaceId nor userId is platform scope: super-admin succeeds" {
        every {
            permissionService.hasPermission(aliceId.toString(), EntityType.AI_PROVIDER, null, Action.WRITE)
        } returns true
        val captured = slot<AiProvider>()
        every { service.create(capture(captured)) } answers { firstArg() }

        withAuth(aliceId) {
            controller.create(resource(id = null, nsId = null, uId = null, name = "platform-provider"))
        }

        captured.captured.namespaceId shouldBe null
        captured.captured.userId shouldBe null
    }

    // -------------------------------------------------------------------------
    // create — Phase 3.5 namespace existence (now AFTER Phase 3 authz)
    // -------------------------------------------------------------------------

    "create with dangling namespaceId returns 404 only when authz passes (avoid existence leak)" {
        val unknownNs = UUID.randomUUID()
        every { namespaceService.findById(unknownNs) } returns null
        // Grant WRITE so Phase 3 passes ; Phase 3.5 then catches the dangling FK.
        every {
            permissionService.hasPermission(aliceId.toString(), EntityType.NAMESPACE, unknownNs.toString(), Action.WRITE)
        } returns true

        withAuth(aliceId) {
            shouldThrow<ResourceNotFoundException> {
                controller.create(resource(id = null, nsId = unknownNs, uId = null))
            }
        }
        verify(exactly = 0) { service.create(any()) }
    }

    "create with dangling namespaceId for a non-member surfaces as AccessDenied (no 404 leak)" {
        val unknownNs = UUID.randomUUID()
        every { namespaceService.findById(unknownNs) } returns null
        // Default mock returns false → no WRITE grant → Phase 3 fires before Phase 3.5.

        shouldThrow<org.springframework.security.access.AccessDeniedException> {
            withAuth(aliceId) {
                controller.create(resource(id = null, nsId = unknownNs, uId = null))
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
        val captured = slot<AiProvider>()
        every { service.create(capture(captured)) } answers { firstArg() }

        withAuth(aliceId) { controller.create(resource(id = null, nsId = namespaceId, uId = null, name = "shared")) }

        captured.captured.namespaceId shouldBe namespaceId
        captured.captured.userId shouldBe null
        verify(exactly = 1) {
            permissionService.hasPermission(aliceId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.WRITE)
        }
    }

    "create NS-shared without WRITE permission throws AccessDeniedException" {
        every {
            permissionService.hasPermission(aliceId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.WRITE)
        } returns false

        shouldThrow<org.springframework.security.access.AccessDeniedException> {
            withAuth(aliceId) { controller.create(resource(id = null, nsId = namespaceId, uId = null)) }
        }
        verify(exactly = 0) { service.create(any()) }
    }

    "create user-namespace with READ permission succeeds and persists userId=auth.name" {
        every {
            permissionService.hasPermission(aliceId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.READ)
        } returns true
        val captured = slot<AiProvider>()
        every { service.create(capture(captured)) } answers { firstArg() }

        withAuth(aliceId) { controller.create(resource(id = null, nsId = namespaceId, uId = aliceId)) }

        captured.captured.namespaceId shouldBe namespaceId
        captured.captured.userId shouldBe aliceId
    }

    "create user-namespace without READ permission throws AccessDeniedException" {
        every {
            permissionService.hasPermission(aliceId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.READ)
        } returns false

        shouldThrow<org.springframework.security.access.AccessDeniedException> {
            withAuth(aliceId) { controller.create(resource(id = null, nsId = namespaceId, uId = aliceId)) }
        }
        verify(exactly = 0) { service.create(any()) }
    }

    "create user-global skips namespace permission check and persists userId=auth.name with null namespaceId" {
        val captured = slot<AiProvider>()
        every { service.create(capture(captured)) } answers { firstArg() }

        withAuth(aliceId) { controller.create(resource(id = null, nsId = null, uId = aliceId, name = "global")) }

        captured.captured.namespaceId shouldBe null
        captured.captured.userId shouldBe aliceId
        verify(exactly = 0) { permissionService.hasPermission(any(), any(), any(), any()) }
    }

    "create duplicate triple propagates 409 from service" {
        every {
            permissionService.hasPermission(aliceId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.READ)
        } returns true
        every { service.create(any()) } throws ResponseStatusException(HttpStatus.CONFLICT, "duplicate")

        val ex = withAuth(aliceId) {
            shouldThrow<ResponseStatusException> {
                controller.create(resource(id = null, nsId = namespaceId, uId = aliceId))
            }
        }
        ex.statusCode.value() shouldBe 409
    }

    // -------------------------------------------------------------------------
    // update — server-owned-field preservation (mass-assignment guard) + apiKey 4-way
    // -------------------------------------------------------------------------

    "update preserves the persisted namespaceId and userId when client sends different values" {
        val existing = config(apiKey = "real-key")
        val otherNs = UUID.randomUUID()
        val otherUser = UUID.randomUUID()
        val payload = resource(id = existing.metadata.id, nsId = otherNs, uId = otherUser, name = "renamed")
        every { service.findById(existing.metadata.id) } returns existing
        every { service.update(any()) } answers {
            val saved = firstArg<AiProvider>()
            saved.namespaceId shouldBe namespaceId
            saved.userId shouldBe null
            saved.name shouldBe "renamed"
            saved
        }

        controller.update(existing.metadata.id, payload)

        verify(exactly = 1) { service.update(any()) }
    }

    "update preserves namespaceId, userId, id, apiType even when body sets others" {
        val p = config(uId = aliceId, apiType = AiApiType.Anthropic)
        val captured = slot<AiProvider>()
        every { service.findById(p.metadata.id) } returns p
        every { service.update(capture(captured)) } answers { firstArg() }

        controller.update(
            id = p.metadata.id,
            resource = resource(
                id = UUID.randomUUID(),
                nsId = UUID.randomUUID(),
                uId = bobId,
                apiType = AiApiType.OpenAI,
                apiKey = null,
            ),
        )

        captured.captured.metadata.id shouldBe p.metadata.id
        captured.captured.namespaceId shouldBe namespaceId
        captured.captured.userId shouldBe aliceId
        captured.captured.apiType shouldBe AiApiType.Anthropic
    }

    "update with masked apiKey preserves existing apiKey" {
        val existingKey = "sk-ant-realkey1234567890"
        val p = config(uId = aliceId, apiKey = existingKey)
        val captured = slot<AiProvider>()
        every { service.findById(p.metadata.id) } returns p
        every { service.update(capture(captured)) } answers { firstArg() }

        controller.update(p.metadata.id, resource(id = p.metadata.id, apiKey = "sk-a****wxyz"))

        captured.captured.apiKey shouldBe existingKey
    }

    "update with non-masked non-blank apiKey replaces it" {
        val p = config(uId = aliceId, apiKey = "old-key-1234567890123")
        val captured = slot<AiProvider>()
        every { service.findById(p.metadata.id) } returns p
        every { service.update(capture(captured)) } answers { firstArg() }

        controller.update(p.metadata.id, resource(id = p.metadata.id, apiKey = "new-key-9876543210987"))

        captured.captured.apiKey shouldBe "new-key-9876543210987"
    }

    "update with null apiKey preserves existing key" {
        val existingKey = "sk-ant-existingkey12345"
        val p = config(uId = aliceId, apiKey = existingKey)
        val captured = slot<AiProvider>()
        every { service.findById(p.metadata.id) } returns p
        every { service.update(capture(captured)) } answers { firstArg() }

        controller.update(p.metadata.id, resource(id = p.metadata.id, apiKey = null))

        captured.captured.apiKey shouldBe existingKey
    }

    "update with empty-string apiKey clears the persisted key" {
        val existingKey = "sk-ant-existingkey12345"
        val p = config(uId = aliceId, apiKey = existingKey)
        val captured = slot<AiProvider>()
        every { service.findById(p.metadata.id) } returns p
        every { service.update(capture(captured)) } answers { firstArg() }

        controller.update(p.metadata.id, resource(id = p.metadata.id, apiKey = ""))

        captured.captured.apiKey shouldBe null
    }

    "update throws 404 when the AiProvider does not exist" {
        val id = UUID.randomUUID()
        every { service.findById(id) } returns null

        shouldThrow<ResourceNotFoundException> { controller.update(id, resource(id = id)) }
    }

    // -------------------------------------------------------------------------
    // list — three modes, mass-assignment guard
    // -------------------------------------------------------------------------

    "list without filter returns the caller's overlays with masked apiKeys" {
        val rows = listOf(
            config(nsId = null, uId = aliceId, name = "GLOBAL", apiKey = "sk-ant-secret123456789"),
            config(nsId = namespaceId, uId = aliceId, name = "NS", apiKey = "sk-ant-secret987654321"),
        )
        every { service.findFiltered(any(), any(), any(), any(), any()) } returns rows

        val resp = controller.list(namespaceId = null, userId = null, auth = authFor(aliceId))

        resp.size shouldBe 2
        resp.map { it.name } shouldContainExactlyInAnyOrder listOf("GLOBAL", "NS")
        resp.forEach { r ->
            r.apiKey shouldNotBe null
            r.apiKey?.contains("****") shouldBe true
        }
    }

    "list with namespaceId=none returns only user-global rows" {
        val globalRows = listOf(
            config(nsId = null, uId = aliceId, name = "GLOBAL"),
        )
        every { service.findFiltered(any(), any(), any(), any(), any()) } returns globalRows

        val respLower = controller.list(namespaceId = "none", userId = "me", auth = authFor(aliceId))
        respLower.map { it.name } shouldBe listOf("GLOBAL")

        val respUpper = controller.list(namespaceId = "NONE", userId = "me", auth = authFor(aliceId))
        respUpper.map { it.name } shouldBe listOf("GLOBAL")
    }

    "list with specific namespaceId and userId=me returns only that namespace's user rows" {
        val rows = listOf(
            config(nsId = namespaceId, uId = aliceId, name = "NS"),
        )
        every { service.findFiltered(any(), any(), any(), any(), any()) } returns rows

        val resp = controller.list(
            namespaceId = namespaceId.toString(),
            userId = "me",
            auth = authFor(aliceId),
        )

        resp.map { it.name } shouldBe listOf("NS")
    }

    "list with specific namespaceId and no userId returns NS-shared rows for that namespace" {
        val rows = listOf(
            config(nsId = namespaceId, uId = null, name = "NS-A"),
            config(nsId = namespaceId, uId = null, name = "NS-B"),
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
        // Replaces the legacy test "list always queries by auth.name regardless of any
        // attempted userId param" : post-fusion the unified controller does not silently
        // override, it explicitly rejects cross-user listing attempts.
        shouldThrow<BadRequestException> {
            controller.list(namespaceId = null, userId = bobId.toString(), auth = authFor(aliceId))
        }
    }
})
