package io.whozoss.agentos.aiModelConfig

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.aiModel.AiModelController
import io.whozoss.agentos.aiModel.AiModelResource
import io.whozoss.agentos.aiModel.AiModelService
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID

/**
 * Unit tests for [io.whozoss.agentos.aiModel.AiModelController].
 */
class AiModelControllerSpec :
    StringSpec({
        timeout = 5000

        val service = mockk<AiModelService>()
        val controller = AiModelController(service)

        val aiProviderId = UUID.randomUUID()
        val namespaceId = UUID.randomUUID()

        fun model(
            id: UUID = UUID.randomUUID(),
            apiName: String = "claude-haiku-4-5",
            alias: String? = "SMALL",
            priority: Int = 0,
            temperature: Double? = 0.3,
            maxTokens: Int? = 1024,
        ) = AiModel(
            metadata = EntityMetadata(id = id),
            aiProviderId = aiProviderId,
            namespaceId = namespaceId,
            apiName = apiName,
            alias = alias,
            priority = priority,
            temperature = temperature,
            maxTokens = maxTokens,
        )

        fun resource(
            id: UUID? = UUID.randomUUID(),
            apiName: String = "claude-haiku-4-5",
            alias: String? = "SMALL",
            priority: Int = 0,
        ) = AiModelResource(
            id = id,
            aiProviderId = aiProviderId,
            namespaceId = namespaceId,
            apiName = apiName,
            alias = alias,
            priority = priority,
        )

        // -------------------------------------------------------------------------
        // toResource
        // -------------------------------------------------------------------------

        "toResource maps all fields correctly" {
            val id = UUID.randomUUID()
            val m = model(id = id, apiName = "claude-opus-4-6", alias = "BIG", priority = 5, temperature = 0.7, maxTokens = 4096)

            val result = controller.toResource(m)

            result.id shouldBe id
            result.aiProviderId shouldBe aiProviderId
            result.namespaceId shouldBe namespaceId
            result.apiName shouldBe "claude-opus-4-6"
            result.alias shouldBe "BIG"
            result.priority shouldBe 5
            result.temperature shouldBe 0.7
            result.maxTokens shouldBe 4096
        }

        // -------------------------------------------------------------------------
        // listByNamespaceId
        // -------------------------------------------------------------------------

        "listByNamespaceId returns all model configs for the namespace" {
            val m1 = model(apiName = "claude-haiku-4-5")
            val m2 = model(apiName = "claude-opus-4-6")
            every { service.findByNamespaceId(namespaceId) } returns listOf(m1, m2)

            val result = controller.listByNamespaceId(namespaceId)

            result shouldBe listOf(controller.toResource(m1), controller.toResource(m2))
            verify(exactly = 1) { service.findByNamespaceId(namespaceId) }
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

        "listByParent returns model configs for the given aiProviderId" {
            val m1 = model(apiName = "claude-haiku-4-5")
            val m2 = model(apiName = "claude-opus-4-6")
            every { service.findByParent(aiProviderId) } returns listOf(m1, m2)

            val result = controller.listByParent(aiProviderId)

            result shouldBe listOf(controller.toResource(m1), controller.toResource(m2))
        }

        "create delegates to service and returns mapped resource" {
            val r = resource(id = null)
            val saved = model()
            every { service.create(any()) } returns saved

            val result = controller.create(r)

            result shouldBe controller.toResource(saved)
            verify(exactly = 1) { service.create(any()) }
        }

        "update delegates to service when entity exists" {
            val m = model()
            val updatedDomain = m.copy(alias = "UPDATED")
            every { service.findById(m.id) } returns m
            every { service.update(any()) } returns updatedDomain

            val result = controller.update(m.id, resource(id = m.id))

            result shouldBe controller.toResource(updatedDomain)
        }

        "update preserves server-owned fields (namespaceId, aiProviderId) regardless of client payload" {
            val m = model()
            // Client sends a resource with a different (or null) namespaceId — server must ignore it
            val clientResource = resource(id = m.id).copy(namespaceId = null)
            val updatedDomain = m.copy(alias = "UPDATED")
            every { service.findById(m.id) } returns m
            every { service.update(any()) } answers {
                val saved = firstArg<AiModel>()
                // namespaceId must come from the persisted entity, not the client payload
                saved.namespaceId shouldBe namespaceId
                saved.aiProviderId shouldBe aiProviderId
                updatedDomain
            }

            controller.update(m.id, clientResource)
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
