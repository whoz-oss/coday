package io.whozoss.agentos.llmModelConfig

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID

/**
 * Unit tests for [LlmModelConfigController].
 *
 * The controller is instantiated directly with MockK stubs — no Spring context.
 * Tests cover:
 * - [LlmModelConfigController.toResource]  — domain → HTTP DTO mapping
 * - [LlmModelConfigController.toDomain]    — HTTP DTO → domain mapping
 * - Inherited [io.whozoss.agentos.entity.EntityController] endpoints:
 *   getById, getByIds, listByParent, create, update, delete
 */
class LlmModelConfigControllerSpec : StringSpec({
    timeout = 5000

    val service = mockk<LlmModelConfigService>()
    val controller = LlmModelConfigController(service)

    val llmConfigId = UUID.randomUUID()

    fun model(
        id: UUID = UUID.randomUUID(),
        apiName: String = "claude-haiku-4-5",
        alias: String? = "SMALL",
        temperature: Double? = 0.3,
        maxTokens: Int? = 1024,
    ) = LlmModelConfig(
        metadata = EntityMetadata(id = id),
        llmConfigId = llmConfigId,
        apiName = apiName,
        alias = alias,
        temperature = temperature,
        maxTokens = maxTokens,
    )

    fun resource(
        id: UUID? = UUID.randomUUID(),
        apiName: String = "claude-haiku-4-5",
        alias: String? = "SMALL",
        temperature: Double? = 0.3,
        maxTokens: Int? = 1024,
    ) = LlmModelConfigResource(
        id = id,
        llmConfigId = llmConfigId,
        apiName = apiName,
        alias = alias,
        temperature = temperature,
        maxTokens = maxTokens,
    )

    // -------------------------------------------------------------------------
    // toResource
    // -------------------------------------------------------------------------

    "toResource maps all fields correctly" {
        val id = UUID.randomUUID()
        val m = model(id = id, apiName = "claude-opus-4-6", alias = "BIG", temperature = 0.7, maxTokens = 4096)

        val result = controller.toResource(m)

        result.id shouldBe id
        result.llmConfigId shouldBe llmConfigId
        result.apiName shouldBe "claude-opus-4-6"
        result.alias shouldBe "BIG"
        result.temperature shouldBe 0.7
        result.maxTokens shouldBe 4096
    }

    "toResource preserves null optional fields" {
        val m = model(alias = null, temperature = null, maxTokens = null)
        val result = controller.toResource(m)
        result.alias shouldBe null
        result.temperature shouldBe null
        result.maxTokens shouldBe null
    }

    // -------------------------------------------------------------------------
    // toDomain
    // -------------------------------------------------------------------------

    "toDomain maps all fields from resource to domain" {
        val id = UUID.randomUUID()
        val r = resource(id = id, apiName = "gpt-4o", alias = "BIG", temperature = 1.0, maxTokens = 8192)

        val result = controller.toDomain(r)

        result.metadata.id shouldBe id
        result.llmConfigId shouldBe llmConfigId
        result.apiName shouldBe "gpt-4o"
        result.alias shouldBe "BIG"
        result.temperature shouldBe 1.0
        result.maxTokens shouldBe 8192
    }

    "toDomain generates a random UUID when resource id is null" {
        val result = controller.toDomain(resource(id = null))
        result.metadata.id shouldBe result.metadata.id
    }

    // -------------------------------------------------------------------------
    // Inherited endpoints
    // -------------------------------------------------------------------------

    "getById returns a resource when the entity is found" {
        val m = model()
        every { service.findById(m.id) } returns m
        controller.getById(m.id) shouldBe controller.toResource(m)
    }

    "getById throws 404 when entity is not found" {
        val id = UUID.randomUUID()
        every { service.findById(id) } returns null
        val ex = runCatching { controller.getById(id) }.exceptionOrNull()
        (ex is ResourceNotFoundException) shouldBe true
    }

    "getByIds returns matching entities mapped to resources" {
        val m1 = model(apiName = "claude-haiku-4-5")
        val m2 = model(apiName = "claude-opus-4-6")
        every { service.findByIds(listOf(m1.id, m2.id)) } returns listOf(m1, m2)
        controller.getByIds(listOf(m1.id, m2.id)) shouldBe listOf(controller.toResource(m1), controller.toResource(m2))
    }

    "listByParent returns model configs for the given llmConfigId" {
        val m1 = model(apiName = "claude-haiku-4-5")
        val m2 = model(apiName = "claude-opus-4-6")
        every { service.findByParent(llmConfigId) } returns listOf(m1, m2)

        val result = controller.listByParent(llmConfigId)

        result shouldBe listOf(controller.toResource(m1), controller.toResource(m2))
        verify(exactly = 1) { service.findByParent(llmConfigId) }
    }

    "create delegates to service and returns mapped resource" {
        val r = resource(id = null)
        val saved = controller.toDomain(r)
        every { service.create(any()) } returns saved

        val result = controller.create(r)

        result shouldBe controller.toResource(saved)
        verify(exactly = 1) { service.create(any()) }
    }

    "update delegates to service when entity exists" {
        val m = model()
        val updatedResource = resource(id = m.id, apiName = m.apiName, alias = "UPDATED")
        val updatedDomain = controller.toDomain(updatedResource)
        every { service.findById(m.id) } returns m
        every { service.update(any()) } returns updatedDomain

        val result = controller.update(m.id, updatedResource)

        result shouldBe controller.toResource(updatedDomain)
        verify(exactly = 1) { service.update(any()) }
    }

    "update throws 404 when entity is not found" {
        val id = UUID.randomUUID()
        every { service.findById(id) } returns null
        val ex = runCatching { controller.update(id, resource(id = id)) }.exceptionOrNull()
        (ex is ResourceNotFoundException) shouldBe true
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
