package io.whozoss.agentos.aiProvider

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.http.HttpStatus
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.Authentication
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Unit tests for [UserAiProviderController].
 *
 * Exercises controller logic directly with mocks. Bean Validation, MVC routing,
 * and the @HideOnAccessDenied → 404 translation are verified in [UserAiProviderControllerMvcTest].
 */
class UserAiProviderControllerSpec : StringSpec({

    val service = mockk<AiProviderService>()
    val permissionService = mockk<PermissionService>(relaxed = true)
    val guard = UserAiProviderGuard(permissionService)
    val controller = UserAiProviderController(service, guard)

    val aliceId = UUID.randomUUID()
    val bobId = UUID.randomUUID()
    val namespaceId = UUID.randomUUID()

    fun authFor(userId: UUID): Authentication {
        val auth = mockk<Authentication>(relaxed = true)
        every { auth.name } returns userId.toString()
        return auth
    }

    fun provider(
        id: UUID = UUID.randomUUID(),
        nsId: UUID? = namespaceId,
        userId: UUID? = aliceId,
        name: String = "MY_ANTHROPIC",
        apiType: AiApiType = AiApiType.Anthropic,
        apiKey: String? = "sk-ant-realkey1234567890",
    ) = AiProvider(
        metadata = EntityMetadata(id = id),
        namespaceId = nsId,
        userId = userId,
        name = name,
        apiType = apiType,
        apiKey = apiKey,
    )

    fun resource(
        id: UUID? = null,
        nsId: UUID? = namespaceId,
        userId: UUID? = null,
        name: String = "MY_ANTHROPIC",
        apiType: AiApiType? = AiApiType.Anthropic,
        apiKey: String? = null,
    ) = UserAiProviderResource(
        id = id,
        namespaceId = nsId,
        userId = userId,
        name = name,
        apiType = apiType,
        apiKey = apiKey,
    )

    beforeTest { clearAllMocks() }

    // -------------------------------------------------------------------------
    // 1) toResource maps all fields, apiKey masked
    // -------------------------------------------------------------------------
    "toResource maps all fields and masks apiKey" {
        val p = provider(name = "MY_OPENAI", apiType = AiApiType.OpenAI, apiKey = "sk-openai-123456789012")
        val r = controller.toResource(p)

        r.id shouldBe p.metadata.id
        r.namespaceId shouldBe namespaceId
        r.userId shouldBe aliceId
        r.name shouldBe "MY_OPENAI"
        r.apiType shouldBe AiApiType.OpenAI
        r.apiKey shouldBe maskApiKey("sk-openai-123456789012")
        r.apiKey shouldNotBe "sk-openai-123456789012"
    }

    // -------------------------------------------------------------------------
    // 2) toResource apiKey null → null
    // -------------------------------------------------------------------------
    "toResource with null apiKey returns null apiKey" {
        val p = provider(apiKey = null)
        val r = controller.toResource(p)
        r.apiKey shouldBe null
    }

    // -------------------------------------------------------------------------
    // 3) create user-global: userId forced to auth.name, body.userId ignored
    // -------------------------------------------------------------------------
    "create forces userId=auth.name and ignores body.userId" {
        val auth = authFor(aliceId)
        val captured = slot<AiProvider>()
        every { service.create(capture(captured)) } answers { firstArg() }

        controller.create(resource(nsId = null, userId = bobId), auth)

        captured.captured.userId shouldBe aliceId
        captured.captured.namespaceId shouldBe null
        verify(exactly = 0) { permissionService.hasPermission(any(), any(), any(), any()) }
    }

    // -------------------------------------------------------------------------
    // 4) create user × namespace: permission check passes → 201
    // -------------------------------------------------------------------------
    "create user-namespace with READ permission succeeds" {
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
    // 5) create user × namespace without permission → 403 explicit (not 404)
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
    // 6) create duplicate triple → propagate 409 from service
    // -------------------------------------------------------------------------
    "create duplicate triple propagates 409 from service" {
        val auth = authFor(aliceId)
        every {
            permissionService.hasPermission(aliceId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.READ)
        } returns true
        every { service.create(any()) } throws ResponseStatusException(HttpStatus.CONFLICT, "duplicate")

        val ex = shouldThrow<ResponseStatusException> { controller.create(resource(), auth) }
        ex.statusCode.value() shouldBe 409
    }

    // -------------------------------------------------------------------------
    // 7) getById happy: cfg.userId == me → 200, apiKey masked
    // -------------------------------------------------------------------------
    "getById returns resource with masked apiKey when caller owns it" {
        val p = provider(userId = aliceId, apiKey = "sk-ant-realkey1234567890")
        every { service.findById(p.metadata.id) } returns p

        val r = controller.getById(p.metadata.id, authFor(aliceId))

        r.id shouldBe p.metadata.id
        r.userId shouldBe aliceId
        r.apiKey shouldNotBe "sk-ant-realkey1234567890"
        r.apiKey shouldBe maskApiKey("sk-ant-realkey1234567890")
    }

    // -------------------------------------------------------------------------
    // 8) getById cross-user → AccessDeniedException
    // -------------------------------------------------------------------------
    "getById cross-user throws AccessDeniedException" {
        val p = provider(userId = bobId)
        every { service.findById(p.metadata.id) } returns p

        shouldThrow<AccessDeniedException> { controller.getById(p.metadata.id, authFor(aliceId)) }
    }

    // -------------------------------------------------------------------------
    // 9) getById non-existent → AccessDeniedException (existence-hiding)
    // -------------------------------------------------------------------------
    "getById on missing row throws AccessDeniedException" {
        val id = UUID.randomUUID()
        every { service.findById(id) } returns null

        shouldThrow<AccessDeniedException> { controller.getById(id, authFor(aliceId)) }
    }

    // -------------------------------------------------------------------------
    // 10) list without filter → both modes, all apiKeys masked
    // -------------------------------------------------------------------------
    "list without filter returns both modes with masked apiKeys" {
        val rows = listOf(
            provider(nsId = null, userId = aliceId, name = "GLOBAL", apiKey = "sk-ant-secret123456789"),
            provider(nsId = namespaceId, userId = aliceId, name = "NS", apiKey = "sk-ant-secret987654321"),
        )
        every { service.findByUserId(aliceId) } returns rows

        val resp = controller.list(namespaceId = null, page = 0, size = 20, auth = authFor(aliceId))

        resp.totalElements shouldBe 2
        resp.content.map { it.name } shouldContainExactlyInAnyOrder listOf("GLOBAL", "NS")
        resp.content.forEach { r ->
            r.apiKey shouldNotBe null
            r.apiKey?.contains("****") shouldBe true
        }
    }

    // -------------------------------------------------------------------------
    // 11) list namespaceId=none (case-insensitive: NONE) → only user-global
    // -------------------------------------------------------------------------
    "list with namespaceId=none returns only user-global rows" {
        val rows = listOf(
            provider(nsId = null, userId = aliceId, name = "GLOBAL"),
            provider(nsId = namespaceId, userId = aliceId, name = "NS"),
        )
        every { service.findByUserId(aliceId) } returns rows

        val respLower = controller.list(namespaceId = "none", page = 0, size = 20, auth = authFor(aliceId))
        respLower.content.map { it.name } shouldBe listOf("GLOBAL")

        val respUpper = controller.list(namespaceId = "NONE", page = 0, size = 20, auth = authFor(aliceId))
        respUpper.content.map { it.name } shouldBe listOf("GLOBAL")
    }

    // -------------------------------------------------------------------------
    // 12) list with specific namespaceId → only that namespace
    // -------------------------------------------------------------------------
    "list with specific namespaceId returns only that namespace's rows" {
        val otherNs = UUID.randomUUID()
        val rows = listOf(
            provider(nsId = null, userId = aliceId, name = "GLOBAL"),
            provider(nsId = namespaceId, userId = aliceId, name = "NS"),
            provider(nsId = otherNs, userId = aliceId, name = "OTHER"),
        )
        every { service.findByUserId(aliceId) } returns rows

        val resp = controller.list(namespaceId = namespaceId.toString(), page = 0, size = 20, auth = authFor(aliceId))

        resp.content.map { it.name } shouldBe listOf("NS")
    }

    // -------------------------------------------------------------------------
    // 13) list ignores any client userId param, always queries by auth.name
    // -------------------------------------------------------------------------
    "list always queries by auth.name regardless of any attempted userId param" {
        every { service.findByUserId(aliceId) } returns emptyList()

        controller.list(namespaceId = null, page = 0, size = 20, auth = authFor(aliceId))

        verify(exactly = 1) { service.findByUserId(aliceId) }
        verify(exactly = 0) { service.findByUserId(bobId) }
    }

    // -------------------------------------------------------------------------
    // 14) list pagination: page=1, size=2 of 5 rows → rows 2-3
    // -------------------------------------------------------------------------
    "list pagination returns the correct slice" {
        val rows = (1..5).map { provider(nsId = null, userId = aliceId, name = "P$it") }
        every { service.findByUserId(aliceId) } returns rows

        val resp = controller.list(namespaceId = null, page = 1, size = 2, auth = authFor(aliceId))

        resp.content.map { it.name } shouldBe listOf("P3", "P4")
        resp.totalElements shouldBe 5
        resp.totalPages shouldBe 3
        resp.page shouldBe 1
        resp.size shouldBe 2
    }

    // -------------------------------------------------------------------------
    // 15) list size=200 → capped to MAX_PAGE_SIZE=100
    // -------------------------------------------------------------------------
    "list pagination caps size at 100" {
        every { service.findByUserId(aliceId) } returns emptyList()

        val resp = controller.list(namespaceId = null, page = 0, size = 200, auth = authFor(aliceId))

        resp.size shouldBe 100
    }

    // -------------------------------------------------------------------------
    // 16) update preserves immutable fields (namespaceId, userId, id, apiType)
    // -------------------------------------------------------------------------
    "update preserves namespaceId, userId, id, apiType even when body sets others" {
        val p = provider(userId = aliceId, apiType = AiApiType.Anthropic)
        val captured = slot<AiProvider>()
        every { service.findById(p.metadata.id) } returns p
        every { service.update(capture(captured)) } answers { firstArg() }

        controller.update(
            id = p.metadata.id,
            body = resource(
                id = UUID.randomUUID(),
                nsId = UUID.randomUUID(),
                userId = bobId,
                apiType = AiApiType.OpenAI,
                apiKey = null,
            ),
            auth = authFor(aliceId),
        )

        captured.captured.metadata.id shouldBe p.metadata.id
        captured.captured.namespaceId shouldBe namespaceId
        captured.captured.userId shouldBe aliceId
        captured.captured.apiType shouldBe AiApiType.Anthropic
    }

    // -------------------------------------------------------------------------
    // 17) update apiKey masked → preserves existing apiKey
    // -------------------------------------------------------------------------
    "update with masked apiKey preserves existing apiKey" {
        val existingKey = "sk-ant-realkey1234567890"
        val p = provider(userId = aliceId, apiKey = existingKey)
        val captured = slot<AiProvider>()
        every { service.findById(p.metadata.id) } returns p
        every { service.update(capture(captured)) } answers { firstArg() }

        controller.update(
            id = p.metadata.id,
            body = resource(apiKey = "sk-a****wxyz"),
            auth = authFor(aliceId),
        )

        captured.captured.apiKey shouldBe existingKey
    }

    // -------------------------------------------------------------------------
    // 18) update apiKey non-masked non-blank → replacement
    // -------------------------------------------------------------------------
    "update with non-masked non-blank apiKey replaces it" {
        val p = provider(userId = aliceId, apiKey = "old-key-1234567890123")
        val captured = slot<AiProvider>()
        every { service.findById(p.metadata.id) } returns p
        every { service.update(capture(captured)) } answers { firstArg() }

        controller.update(
            id = p.metadata.id,
            body = resource(apiKey = "new-key-9876543210987"),
            auth = authFor(aliceId),
        )

        captured.captured.apiKey shouldBe "new-key-9876543210987"
    }

    // -------------------------------------------------------------------------
    // 19a) update apiKey null (field absent in JSON, preserved by Jackson) → preserves existing
    //
    // The wire contract: omitting `apiKey` from the request body means "I did not touch it,
    // keep the persisted credential". Jackson collapses both JSON-null and field-absent to a
    // Kotlin `null`, so this branch handles both — the FE never sends an explicit `apiKey: null`
    // (it omits the field entirely on untouched).
    // -------------------------------------------------------------------------
    "update with null apiKey preserves existing key" {
        val existingKey = "sk-ant-existingkey12345"
        val p = provider(userId = aliceId, apiKey = existingKey)
        val captured = slot<AiProvider>()
        every { service.findById(p.metadata.id) } returns p
        every { service.update(capture(captured)) } answers { firstArg() }

        controller.update(id = p.metadata.id, body = resource(apiKey = null), auth = authFor(aliceId))

        captured.captured.apiKey shouldBe existingKey
    }

    // -------------------------------------------------------------------------
    // 19b) update apiKey blank ("") → clears the persisted credential
    //
    // The wire contract: explicit empty string in the request body means "the user cleared the
    // field deliberately, drop the credential". Backend persists `apiKey = null` in the DB.
    // Required by FR25 to support credential rotation/revocation without recreating the row.
    // -------------------------------------------------------------------------
    "update with empty-string apiKey clears the persisted key" {
        val existingKey = "sk-ant-existingkey12345"
        val p = provider(userId = aliceId, apiKey = existingKey)
        val captured = slot<AiProvider>()
        every { service.findById(p.metadata.id) } returns p
        every { service.update(capture(captured)) } answers { firstArg() }

        controller.update(id = p.metadata.id, body = resource(apiKey = ""), auth = authFor(aliceId))

        captured.captured.apiKey shouldBe null
    }

    // -------------------------------------------------------------------------
    // 20) update cross-user → AccessDeniedException
    // -------------------------------------------------------------------------
    "update cross-user throws AccessDeniedException" {
        val p = provider(userId = bobId)
        every { service.findById(p.metadata.id) } returns p

        shouldThrow<AccessDeniedException> { controller.update(p.metadata.id, resource(), authFor(aliceId)) }
        verify(exactly = 0) { service.update(any()) }
    }

    // -------------------------------------------------------------------------
    // 21) delete happy → calls service.delete
    // -------------------------------------------------------------------------
    "delete calls service.delete when caller owns the row" {
        val p = provider(userId = aliceId)
        every { service.findById(p.metadata.id) } returns p
        every { service.delete(p.metadata.id) } returns true

        controller.delete(p.metadata.id, authFor(aliceId))

        verify(exactly = 1) { service.delete(p.metadata.id) }
    }

    // -------------------------------------------------------------------------
    // 22) delete cross-user → AccessDeniedException
    // -------------------------------------------------------------------------
    "delete cross-user throws AccessDeniedException" {
        val p = provider(userId = bobId)
        every { service.findById(p.metadata.id) } returns p

        shouldThrow<AccessDeniedException> { controller.delete(p.metadata.id, authFor(aliceId)) }
        verify(exactly = 0) { service.delete(any()) }
    }
})
