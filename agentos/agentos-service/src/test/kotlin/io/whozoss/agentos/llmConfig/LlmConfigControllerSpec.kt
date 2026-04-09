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
        models: List<LlmModelEntry> = emptyList(),
    ) = LlmConfig(
        metadata = EntityMetadata(id = id),
        namespaceId = namespaceId,
        name = name,
        apiType = apiType,
        apiKey = apiKey,
        models = models,
    )

    fun resource(
        id: UUID? = UUID.randomUUID(),
        name: String = "anthropic",
        apiType: AiApiType = AiApiType.Anthropic,
        apiKey: String? = null,
        models: List<LlmModelEntryResource> = emptyList(),
    ) = LlmConfigResource(
        id = id,
        namespaceId = namespaceId,
        name = name,
        apiType = apiType,
        apiKey = apiKey,
        models = models,
    )

    // -------------------------------------------------------------------------
    // toResource — apiKey masking
    // -------------------------------------------------------------------------

    "toResource masks a long apiKey (>= 12 chars)" {
        val c = config(apiKey = "sk-ant-api03-abcdefghijklmnop")

        val result = controller.toResource(c)

        result.apiKey shouldBe "sk-a****mnop"
    }

    "toResource masks a medium apiKey (9-11 chars)" {
        val c = config(apiKey = "123456789")

        val result = controller.toResource(c)

        result.apiKey shouldBe "12****89"
    }

    "toResource masks a short apiKey (<= 8 chars) as ****" {
        val c = config(apiKey = "secret")

        val result = controller.toResource(c)

        result.apiKey shouldBe "****"
    }

    "toResource returns null apiKey when no key is set" {
        val c = config(apiKey = null)

        val result = controller.toResource(c)

        result.apiKey.shouldBeNull()
    }

    "toResource maps all other fields correctly" {
        val id = UUID.randomUUID()
        val models = listOf(LlmModelEntry(apiName = "claude-haiku-4-5", alias = "SMALL"))
        val c = config(id = id, name = "anthropic", apiType = AiApiType.Anthropic, models = models)

        val result = controller.toResource(c)

        result.id shouldBe id
        result.namespaceId shouldBe namespaceId
        result.name shouldBe "anthropic"
        result.apiType shouldBe AiApiType.Anthropic
        result.models.size shouldBe 1
        result.models[0].apiName shouldBe "claude-haiku-4-5"
        result.models[0].alias shouldBe "SMALL"
    }

    // -------------------------------------------------------------------------
    // toDomain
    // -------------------------------------------------------------------------

    "toDomain maps all fields from resource to domain" {
        val id = UUID.randomUUID()
        val models = listOf(LlmModelEntryResource(apiName = "gpt-4o", alias = "BIG", temperature = 0.5))
        val r = resource(id = id, name = "openai", apiType = AiApiType.OpenAI, apiKey = "sk-abc", models = models)

        val result = controller.toDomain(r)

        result.metadata.id shouldBe id
        result.namespaceId shouldBe namespaceId
        result.name shouldBe "openai"
        result.apiType shouldBe AiApiType.OpenAI
        result.apiKey shouldBe "sk-abc"
        result.models.size shouldBe 1
        result.models[0].apiName shouldBe "gpt-4o"
        result.models[0].temperature shouldBe 0.5
    }

    "toDomain generates a random UUID when resource id is null" {
        val r = resource(id = null)

        val result = controller.toDomain(r)

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

        // The returned resource should show the masked form of the original key
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
    // getById (inherited)
    // -------------------------------------------------------------------------

    "getById returns a resource when the entity is found" {
        val c = config()
        every { service.findById(c.id) } returns c

        val result = controller.getById(c.id)

        result shouldBe controller.toResource(c)
    }

    "getById throws 404 when entity is not found" {
        val id = UUID.randomUUID()
        every { service.findById(id) } returns null

        val ex = runCatching { controller.getById(id) }.exceptionOrNull()

        (ex is ResourceNotFoundException) shouldBe true
    }

    // -------------------------------------------------------------------------
    // getByIds (inherited)
    // -------------------------------------------------------------------------

    "getByIds returns matching entities mapped to resources" {
        val c1 = config(name = "anthropic")
        val c2 = config(name = "openai")
        every { service.findByIds(listOf(c1.id, c2.id)) } returns listOf(c1, c2)

        val result = controller.getByIds(listOf(c1.id, c2.id))

        result shouldBe listOf(controller.toResource(c1), controller.toResource(c2))
    }

    // -------------------------------------------------------------------------
    // listByParent (inherited)
    // -------------------------------------------------------------------------

    "listByParent returns configs for the given namespaceId" {
        val c1 = config(name = "anthropic")
        val c2 = config(name = "openai")
        every { service.findByParent(namespaceId) } returns listOf(c1, c2)

        val result = controller.listByParent(namespaceId)

        result shouldBe listOf(controller.toResource(c1), controller.toResource(c2))
        verify(exactly = 1) { service.findByParent(namespaceId) }
    }

    // -------------------------------------------------------------------------
    // create (inherited)
    // -------------------------------------------------------------------------

    "create converts resource to domain, delegates to service, and returns mapped resource" {
        val r = resource(id = null)
        val saved = controller.toDomain(r)
        every { service.create(any()) } returns saved

        val result = controller.create(r)

        result shouldBe controller.toResource(saved)
        verify(exactly = 1) { service.create(any()) }
    }

    // -------------------------------------------------------------------------
    // delete (inherited)
    // -------------------------------------------------------------------------

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
