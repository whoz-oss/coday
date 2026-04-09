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

/**
 * Unit tests for [LlmConfigController].
 *
 * The controller is instantiated directly with MockK stubs — no Spring context.
 * Tests cover:
 * - [LlmConfigController.toResource]  — domain → HTTP DTO mapping, including apiKey masking
 * - [LlmConfigController.toDomain]    — HTTP DTO → domain mapping
 * - [LlmConfigController.update]      — masked apiKey preservation
 * - Inherited [io.whozoss.agentos.entity.EntityController] endpoints:
 *   getById (found / not-found), getByIds, listByParent, create,
 *   update (found / not-found), delete (found / not-found)
 */
class LlmConfigControllerSpec : StringSpec({
    timeout = 5000

    val service = mockk<LlmConfigService>()
    val controller = LlmConfigController(service)

    val namespaceId = UUID.randomUUID()

    fun config(
        id: UUID = UUID.randomUUID(),
        name: String = "anthropic",
        apiType: AiApiType = AiApiType.Anthropic,
        apiKey: String? = null,
    ) = LlmConfig(
        metadata = EntityMetadata(id = id),
        namespaceId = namespaceId,
        name = name,
        apiType = apiType,
        apiKey = apiKey,
    )

    fun resource(
        id: UUID? = UUID.randomUUID(),
        name: String = "anthropic",
        apiType: AiApiType = AiApiType.Anthropic,
        apiKey: String? = null,
    ) = LlmConfigResource(
        id = id,
        namespaceId = namespaceId,
        name = name,
        apiType = apiType,
        apiKey = apiKey,
    )

    // -------------------------------------------------------------------------
    // toResource — apiKey masking
    // -------------------------------------------------------------------------

    "toResource masks a long apiKey (>= 12 chars)" {
        val result = controller.toResource(config(apiKey = "sk-ant-api03-abcdefghijklmnop"))
        result.apiKey shouldBe "sk-a****mnop"
    }

    "toResource masks a medium apiKey (9-11 chars)" {
        val result = controller.toResource(config(apiKey = "123456789"))
        result.apiKey shouldBe "12****89"
    }

    "toResource masks a short apiKey (<= 8 chars) as ****" {
        val result = controller.toResource(config(apiKey = "secret"))
        result.apiKey shouldBe "****"
    }

    "toResource returns null apiKey when no key is set" {
        controller.toResource(config(apiKey = null)).apiKey.shouldBeNull()
    }

    "toResource maps all non-sensitive fields correctly" {
        val id = UUID.randomUUID()
        val result = controller.toResource(config(id = id, name = "anthropic", apiType = AiApiType.Anthropic))

        result.id shouldBe id
        result.namespaceId shouldBe namespaceId
        result.name shouldBe "anthropic"
        result.apiType shouldBe AiApiType.Anthropic
    }

    // -------------------------------------------------------------------------
    // toDomain
    // -------------------------------------------------------------------------

    "toDomain maps all fields from resource to domain" {
        val id = UUID.randomUUID()
        val r = resource(id = id, name = "openai", apiType = AiApiType.OpenAI, apiKey = "sk-abc")

        val result = controller.toDomain(r)

        result.metadata.id shouldBe id
        result.namespaceId shouldBe namespaceId
        result.name shouldBe "openai"
        result.apiType shouldBe AiApiType.OpenAI
        result.apiKey shouldBe "sk-abc"
    }

    "toDomain generates a random UUID when resource id is null" {
        val result = controller.toDomain(resource(id = null))
        result.metadata.id shouldBe result.metadata.id
    }

    // -------------------------------------------------------------------------
    // update — masked apiKey preservation
    // -------------------------------------------------------------------------

    "update preserves persisted apiKey when incoming value is masked" {
        val existing = config(apiKey = "sk-ant-api03-real-secret")
        val incomingResource = resource(id = existing.id, apiKey = "sk-a****cret")
        every { service.findById(existing.id) } returns existing
        every { service.update(any()) } answers { firstArg() }

        val result = controller.update(existing.id, incomingResource)

        result.apiKey shouldBe maskApiKey("sk-ant-api03-real-secret")
        verify { service.update(match { it.apiKey == "sk-ant-api03-real-secret" }) }
    }

    "update uses new apiKey when incoming value is not masked" {
        val existing = config(apiKey = "sk-ant-api03-old-key")
        val incomingResource = resource(id = existing.id, apiKey = "brand-new-key-12345")
        every { service.findById(existing.id) } returns existing
        every { service.update(any()) } answers { firstArg() }

        controller.update(existing.id, incomingResource)

        verify { service.update(match { it.apiKey == "brand-new-key-12345" }) }
    }

    "update clears apiKey when incoming value is null" {
        val existing = config(apiKey = "sk-ant-api03-old-key")
        val incomingResource = resource(id = existing.id, apiKey = null)
        every { service.findById(existing.id) } returns existing
        every { service.update(any()) } answers { firstArg() }

        controller.update(existing.id, incomingResource)

        verify { service.update(match { it.apiKey == null }) }
    }

    "update throws 404 when entity is not found" {
        val id = UUID.randomUUID()
        every { service.findById(id) } returns null

        val ex = runCatching { controller.update(id, resource(id = id)) }.exceptionOrNull()
        (ex is ResourceNotFoundException) shouldBe true
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

    "getByIds returns matching entities mapped to resources" {
        val c1 = config(name = "anthropic")
        val c2 = config(name = "openai")
        every { service.findByIds(listOf(c1.id, c2.id)) } returns listOf(c1, c2)
        controller.getByIds(listOf(c1.id, c2.id)) shouldBe listOf(controller.toResource(c1), controller.toResource(c2))
    }

    "listByParent returns configs for the given namespaceId" {
        val c1 = config(name = "anthropic")
        val c2 = config(name = "openai")
        every { service.findByParent(namespaceId) } returns listOf(c1, c2)

        val result = controller.listByParent(namespaceId)

        result shouldBe listOf(controller.toResource(c1), controller.toResource(c2))
        verify(exactly = 1) { service.findByParent(namespaceId) }
    }

    "create delegates to service and returns mapped resource" {
        val r = resource(id = null)
        val saved = controller.toDomain(r)
        every { service.create(any()) } returns saved

        val result = controller.create(r)

        result shouldBe controller.toResource(saved)
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
