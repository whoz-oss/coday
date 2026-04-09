package io.whozoss.agentos.llmConfig

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID

class LlmConfigControllerSpec : StringSpec({
    timeout = 5000

    val service = mockk<LlmConfigService>()
    val controller = LlmConfigController(service)

    val namespaceId = UUID.randomUUID()
    val userId = UUID.randomUUID()

    fun config(
        id: UUID = UUID.randomUUID(),
        nsId: UUID? = namespaceId,
        uId: UUID? = null,
        name: String = "anthropic",
        apiKey: String? = null,
    ) = LlmConfig(
        metadata = EntityMetadata(id = id),
        namespaceId = nsId,
        userId = uId,
        name = name,
        apiType = AiApiType.Anthropic,
        apiKey = apiKey,
    )

    fun resource(
        id: UUID? = UUID.randomUUID(),
        nsId: UUID? = namespaceId,
        uId: UUID? = null,
        name: String = "anthropic",
        apiKey: String? = null,
    ) = LlmConfigResource(
        id = id,
        namespaceId = nsId,
        userId = uId,
        name = name,
        apiType = AiApiType.Anthropic,
        apiKey = apiKey,
    )

    // -------------------------------------------------------------------------
    // toResource
    // -------------------------------------------------------------------------

    "toResource masks a long apiKey" {
        controller.toResource(config(apiKey = "sk-ant-api03-abcdefghijklmnop")).apiKey shouldBe "sk-a****mnop"
    }

    "toResource returns null apiKey when no key is set" {
        controller.toResource(config(apiKey = null)).apiKey.shouldBeNull()
    }

    "toResource maps namespaceId and userId" {
        val result = controller.toResource(config(nsId = namespaceId, uId = userId))
        result.namespaceId shouldBe namespaceId
        result.userId shouldBe userId
    }

    "toResource maps null namespaceId" {
        val result = controller.toResource(config(nsId = null, uId = userId))
        result.namespaceId.shouldBeNull()
    }

    // -------------------------------------------------------------------------
    // update — masked apiKey preservation
    // -------------------------------------------------------------------------

    "update preserves persisted apiKey when incoming value is masked" {
        val existing = config(apiKey = "sk-ant-api03-real-secret")
        every { service.findById(existing.id) } returns existing
        every { service.update(any()) } answers { firstArg() }

        val result = controller.update(existing.id, resource(id = existing.id, apiKey = "sk-a****cret"))

        result.apiKey shouldBe maskApiKey("sk-ant-api03-real-secret")
        verify { service.update(match { it.apiKey == "sk-ant-api03-real-secret" }) }
    }

    "update clears apiKey when incoming value is null" {
        val existing = config(apiKey = "sk-ant-api03-old")
        every { service.findById(existing.id) } returns existing
        every { service.update(any()) } answers { firstArg() }

        controller.update(existing.id, resource(id = existing.id, apiKey = null))

        verify { service.update(match { it.apiKey == null }) }
    }

    "update throws 404 when entity is not found" {
        val id = UUID.randomUUID()
        every { service.findById(id) } returns null
        val ex = runCatching { controller.update(id, resource(id = id)) }.exceptionOrNull()
        (ex is ResourceNotFoundException) shouldBe true
    }

    // -------------------------------------------------------------------------
    // listByNamespaceId / listByUserId
    // -------------------------------------------------------------------------

    "listByNamespaceId returns configs for the namespace" {
        val c1 = config(name = "anthropic")
        val c2 = config(name = "openai")
        every { service.findByNamespaceId(namespaceId) } returns listOf(c1, c2)

        val result = controller.listByNamespaceId(namespaceId)

        result shouldBe listOf(controller.toResource(c1), controller.toResource(c2))
        verify(exactly = 1) { service.findByNamespaceId(namespaceId) }
    }

    "listByUserId returns configs for the user" {
        val c = config(nsId = null, uId = userId)
        every { service.findByUserId(userId) } returns listOf(c)

        val result = controller.listByUserId(userId)

        result shouldBe listOf(controller.toResource(c))
        verify(exactly = 1) { service.findByUserId(userId) }
    }

    // -------------------------------------------------------------------------
    // Inherited endpoints
    // -------------------------------------------------------------------------

    "getById returns a resource when the entity is found" {
        val c = config()
        every { service.findById(c.id) } returns c
        controller.getById(c.id) shouldBe controller.toResource(c)
    }

    "getById throws 404 when entity is not found" {
        val id = UUID.randomUUID()
        every { service.findById(id) } returns null
        val ex = runCatching { controller.getById(id) }.exceptionOrNull()
        (ex is ResourceNotFoundException) shouldBe true
    }

    "create delegates to service and returns mapped resource" {
        val r = resource(id = null)
        val saved = controller.toDomain(r)
        every { service.create(any()) } returns saved
        controller.create(r) shouldBe controller.toResource(saved)
        verify(exactly = 1) { service.create(any()) }
    }

    "delete succeeds when entity exists" {
        val id = UUID.randomUUID()
        every { service.delete(id) } returns true
        controller.delete(id)
        verify(exactly = 1) { service.delete(id) }
    }

    "delete throws 404 when service returns false" {
        val id = UUID.randomUUID()
        every { service.delete(id) } returns false
        val ex = runCatching { controller.delete(id) }.exceptionOrNull()
        (ex is ResourceNotFoundException) shouldBe true
    }
})
