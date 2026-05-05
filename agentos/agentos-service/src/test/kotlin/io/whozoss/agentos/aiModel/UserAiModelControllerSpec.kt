package io.whozoss.agentos.aiModel

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.whozoss.agentos.aiProvider.AiProviderService
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.http.HttpStatus
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.Authentication
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Unit tests for [UserAiModelController].
 *
 * Uses package `io.whozoss.agentos.aiModel` (aligned with main sources, not the legacy
 * `aiModelConfig` package used by older specs).
 */
class UserAiModelControllerSpec : StringSpec({

    val service = mockk<AiModelService>()
    val aiProviderService = mockk<AiProviderService>()
    val guard = UserAiModelGuard(aiProviderService)
    val controller = UserAiModelController(service, guard)

    val aliceId = UUID.randomUUID()
    val bobId = UUID.randomUUID()
    val namespaceId = UUID.randomUUID()
    val providerId = UUID.randomUUID()

    fun authFor(userId: UUID): Authentication {
        val auth = mockk<Authentication>(relaxed = true)
        every { auth.name } returns userId.toString()
        return auth
    }

    fun model(
        id: UUID = UUID.randomUUID(),
        aiProviderId: UUID = providerId,
        nsId: UUID? = namespaceId,
        userId: UUID? = aliceId,
        apiModelName: String = "claude-haiku-4-5",
        alias: String? = "SMALL",
        priority: Int = 0,
        temperature: Double? = 0.7,
        maxTokens: Int? = 1024,
    ) = AiModel(
        metadata = EntityMetadata(id = id),
        aiProviderId = aiProviderId,
        namespaceId = nsId,
        userId = userId,
        apiModelName = apiModelName,
        alias = alias,
        priority = priority,
        temperature = temperature,
        maxTokens = maxTokens,
    )

    fun provider(
        id: UUID = providerId,
        userId: UUID? = aliceId,
        nsId: UUID? = namespaceId,
    ) = AiProvider(
        metadata = EntityMetadata(id = id),
        namespaceId = nsId,
        userId = userId,
        name = "MY_PROVIDER",
        apiType = AiApiType.Anthropic,
    )

    fun resource(
        id: UUID? = null,
        aiProviderId: UUID? = providerId,
        apiModelName: String = "claude-haiku-4-5",
        alias: String? = "SMALL",
        priority: Int = 0,
        temperature: Double? = 0.7,
        maxTokens: Int? = 1024,
    ) = UserAiModelResource(
        id = id,
        aiProviderId = aiProviderId,
        apiModelName = apiModelName,
        alias = alias,
        priority = priority,
        temperature = temperature,
        maxTokens = maxTokens,
    )

    beforeTest { clearAllMocks() }

    // -------------------------------------------------------------------------
    // 1-4) toResource mapping
    // -------------------------------------------------------------------------
    "toResource maps all fields correctly" {
        val m = model()
        val r = controller.toResource(m)

        r.id shouldBe m.metadata.id
        r.aiProviderId shouldBe providerId
        r.namespaceId shouldBe namespaceId
        r.userId shouldBe aliceId
        r.apiModelName shouldBe "claude-haiku-4-5"
        r.alias shouldBe "SMALL"
        r.priority shouldBe 0
        r.temperature shouldBe 0.7
        r.maxTokens shouldBe 1024
    }

    "toResource maps null optional fields without error" {
        val m = model(nsId = null, userId = null, alias = null, temperature = null, maxTokens = null)
        val r = controller.toResource(m)

        r.namespaceId shouldBe null
        r.userId shouldBe null
        r.alias shouldBe null
        r.temperature shouldBe null
        r.maxTokens shouldBe null
    }

    "toResource preserves aiProviderId from model" {
        val differentProviderId = UUID.randomUUID()
        val m = model(aiProviderId = differentProviderId)
        val r = controller.toResource(m)

        r.aiProviderId shouldBe differentProviderId
    }

    "toResource preserves priority from model" {
        val m = model(priority = 5)
        val r = controller.toResource(m)

        r.priority shouldBe 5
    }

    // -------------------------------------------------------------------------
    // 5-9) create verdicts
    // -------------------------------------------------------------------------
    "create with parent owned by me returns 201" {
        val auth = authFor(aliceId)
        val parent = provider(userId = aliceId)
        every { aiProviderService.findById(providerId) } returns parent
        every { service.create(any()) } answers { firstArg<AiModel>().copy(namespaceId = namespaceId, userId = aliceId) }

        val result = controller.create(resource(), auth)

        result.apiModelName shouldBe "claude-haiku-4-5"
    }

    "create with parent owned by bob throws AccessDeniedException (CrossUser → 404)" {
        val auth = authFor(aliceId)
        val parent = provider(userId = bobId)
        every { aiProviderService.findById(providerId) } returns parent

        shouldThrow<AccessDeniedException> { controller.create(resource(), auth) }
        verify(exactly = 0) { service.create(any()) }
    }

    "create with namespace-only parent throws 403 (ParentNotUserScoped)" {
        val auth = authFor(aliceId)
        val parent = provider(userId = null)
        every { aiProviderService.findById(providerId) } returns parent

        val ex = shouldThrow<ResponseStatusException> { controller.create(resource(), auth) }
        ex.statusCode.value() shouldBe 403
    }

    "create with missing parent throws AccessDeniedException (ParentMissing → 404)" {
        val auth = authFor(aliceId)
        every { aiProviderService.findById(providerId) } returns null

        shouldThrow<AccessDeniedException> { controller.create(resource(), auth) }
    }

    "create with body userId/namespaceId set → ignored, denormalized from parent by service" {
        val auth = authFor(aliceId)
        val parent = provider(userId = aliceId, nsId = namespaceId)
        every { aiProviderService.findById(providerId) } returns parent
        val captured = slot<AiModel>()
        every { service.create(capture(captured)) } answers {
            firstArg<AiModel>().copy(namespaceId = namespaceId, userId = aliceId)
        }

        controller.create(
            resource().copy(
                aiProviderId = providerId,
                apiModelName = "claude-haiku-4-5",
            ),
            auth,
        )

        // The controller sends null userId/namespaceId — denormalization happens in service
        captured.captured.userId shouldBe null
        captured.captured.namespaceId shouldBe null
    }

    // -------------------------------------------------------------------------
    // 10) create propagates 409 from service
    // -------------------------------------------------------------------------
    "create propagates 409 alias-uniqueness from service" {
        val auth = authFor(aliceId)
        val parent = provider(userId = aliceId)
        every { aiProviderService.findById(providerId) } returns parent
        every { service.create(any()) } throws ResponseStatusException(HttpStatus.CONFLICT, "alias conflict")

        val ex = shouldThrow<ResponseStatusException> { controller.create(resource(), auth) }
        ex.statusCode.value() shouldBe 409
    }

    // -------------------------------------------------------------------------
    // 11-13) getById
    // -------------------------------------------------------------------------
    "getById returns resource when caller owns the row" {
        val m = model(userId = aliceId)
        every { service.findById(m.metadata.id) } returns m

        val r = controller.getById(m.metadata.id, authFor(aliceId))

        r.id shouldBe m.metadata.id
        r.userId shouldBe aliceId
    }

    "getById cross-user throws AccessDeniedException" {
        val m = model(userId = bobId)
        every { service.findById(m.metadata.id) } returns m

        shouldThrow<AccessDeniedException> { controller.getById(m.metadata.id, authFor(aliceId)) }
    }

    "getById non-existent throws AccessDeniedException" {
        val id = UUID.randomUUID()
        every { service.findById(id) } returns null

        shouldThrow<AccessDeniedException> { controller.getById(id, authFor(aliceId)) }
    }

    // -------------------------------------------------------------------------
    // 14-17) list
    // -------------------------------------------------------------------------
    "list without filter returns all user's models" {
        val rows = listOf(
            model(nsId = null, userId = aliceId, alias = "GLOBAL_M"),
            model(nsId = namespaceId, userId = aliceId, alias = "NS_M"),
        )
        every { service.findByUserId(aliceId) } returns rows

        val resp = controller.list(namespaceId = null, aiProviderId = null, page = 0, size = 20, auth = authFor(aliceId))

        resp.totalElements shouldBe 2
    }

    "list with namespaceId=none returns only user-global models" {
        val rows = listOf(
            model(nsId = null, userId = aliceId, alias = "GLOBAL"),
            model(nsId = namespaceId, userId = aliceId, alias = "NS"),
        )
        every { service.findByUserId(aliceId) } returns rows

        val resp = controller.list(namespaceId = "none", aiProviderId = null, page = 0, size = 20, auth = authFor(aliceId))

        resp.content.map { it.alias } shouldBe listOf("GLOBAL")
    }

    "list with aiProviderId filter returns only models under that provider" {
        val otherProviderId = UUID.randomUUID()
        val rows = listOf(
            model(aiProviderId = providerId, userId = aliceId, alias = "MINE"),
            model(aiProviderId = otherProviderId, userId = aliceId, alias = "OTHER"),
        )
        every { service.findByUserId(aliceId) } returns rows

        val resp = controller.list(namespaceId = null, aiProviderId = providerId, page = 0, size = 20, auth = authFor(aliceId))

        resp.content.map { it.alias } shouldBe listOf("MINE")
    }

    "list ignores any client userId param, always queries by auth.name" {
        every { service.findByUserId(aliceId) } returns emptyList()

        controller.list(namespaceId = null, aiProviderId = null, page = 0, size = 20, auth = authFor(aliceId))

        verify(exactly = 1) { service.findByUserId(aliceId) }
        verify(exactly = 0) { service.findByUserId(bobId) }
    }

    // -------------------------------------------------------------------------
    // 18-20) update
    // -------------------------------------------------------------------------
    "update preserves immutable fields (id, aiProviderId, namespaceId, userId)" {
        val m = model(userId = aliceId)
        val captured = slot<AiModel>()
        every { service.findById(m.metadata.id) } returns m
        every { service.update(capture(captured)) } answers { firstArg() }

        controller.update(
            id = m.metadata.id,
            body = resource(
                id = UUID.randomUUID(),
                aiProviderId = UUID.randomUUID(),
                apiModelName = "gpt-4o",
            ),
            auth = authFor(aliceId),
        )

        captured.captured.metadata.id shouldBe m.metadata.id
        captured.captured.aiProviderId shouldBe providerId
        captured.captured.namespaceId shouldBe namespaceId
        captured.captured.userId shouldBe aliceId
        captured.captured.apiModelName shouldBe "gpt-4o"
    }

    "update cross-user throws AccessDeniedException" {
        val m = model(userId = bobId)
        every { service.findById(m.metadata.id) } returns m

        shouldThrow<AccessDeniedException> { controller.update(m.metadata.id, resource(), authFor(aliceId)) }
        verify(exactly = 0) { service.update(any()) }
    }

    "update on missing row throws AccessDeniedException" {
        val id = UUID.randomUUID()
        every { service.findById(id) } returns null

        shouldThrow<AccessDeniedException> { controller.update(id, resource(), authFor(aliceId)) }
    }

    // -------------------------------------------------------------------------
    // 21) delete
    // -------------------------------------------------------------------------
    "delete calls service.delete when caller owns the row" {
        val m = model(userId = aliceId)
        every { service.findById(m.metadata.id) } returns m
        every { service.delete(m.metadata.id) } returns true

        controller.delete(m.metadata.id, authFor(aliceId))

        verify(exactly = 1) { service.delete(m.metadata.id) }
    }

    "delete cross-user throws AccessDeniedException" {
        val m = model(userId = bobId)
        every { service.findById(m.metadata.id) } returns m

        shouldThrow<AccessDeniedException> { controller.delete(m.metadata.id, authFor(aliceId)) }
        verify(exactly = 0) { service.delete(any()) }
    }
})
