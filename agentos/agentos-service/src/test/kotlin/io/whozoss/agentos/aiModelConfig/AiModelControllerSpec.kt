package io.whozoss.agentos.aiModelConfig

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
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
 * Unit tests for [AiModelController].
 *
 * Permission checks moved to `@PreAuthorize` + [io.whozoss.agentos.aiModel.AiModelGuard].
 * See [io.whozoss.agentos.agentConfig.AgentConfigControllerUnitSpec] for the unit-vs-integration
 * coverage rationale. The guard bean is tested separately in [AiModelGuardSpec].
 */
class AiModelControllerSpec : StringSpec({

    val service = mockk<AiModelService>()
    val controller = AiModelController(service)

    val namespaceId = UUID.randomUUID()
    val aiProviderId = UUID.randomUUID()

    fun model(
        id: UUID = UUID.randomUUID(),
        nsId: UUID? = namespaceId,
        apiName: String = "claude-haiku-4-5",
    ) = AiModel(
        metadata = EntityMetadata(id = id),
        aiProviderId = aiProviderId,
        namespaceId = nsId,
        userId = null,
        apiModelName = apiName,
        alias = "SMALL",
        priority = 0,
    )

    fun resource(
        id: UUID? = UUID.randomUUID(),
        apiName: String = "claude-haiku-4-5",
    ) = AiModelResource(
        id = id,
        aiProviderId = aiProviderId,
        namespaceId = namespaceId,
        apiModelName = apiName,
        alias = "SMALL",
        priority = 0,
    )

    beforeTest { clearAllMocks() }

    // -------------------------------------------------------------------------
    // Mapping
    // -------------------------------------------------------------------------

    "toResource maps all fields correctly" {
        val id = UUID.randomUUID()
        val m = model(id = id, apiName = "claude-opus-4-6")
        val r = controller.toResource(m)
        r.id shouldBe id
        r.aiProviderId shouldBe aiProviderId
        r.namespaceId shouldBe namespaceId
        r.apiModelName shouldBe "claude-opus-4-6"
    }

    "toDomain leaves namespaceId null at create time (denormalised by service)" {
        val r = resource(id = null)
        val domain = controller.toDomain(r)
        domain.namespaceId shouldBe null
        domain.aiProviderId shouldBe aiProviderId
    }

    // -------------------------------------------------------------------------
    // update — mass-assignment guard
    // -------------------------------------------------------------------------

    "update preserves server-owned fields (namespaceId, aiProviderId) regardless of client payload" {
        val existing = model()
        val clientResource = resource(id = existing.metadata.id).copy(namespaceId = null)
        every { service.findById(existing.metadata.id) } returns existing
        every { service.update(any()) } answers {
            val saved = firstArg<AiModel>()
            saved.namespaceId shouldBe namespaceId
            saved.aiProviderId shouldBe aiProviderId
            saved
        }

        controller.update(existing.metadata.id, clientResource)

        verify(exactly = 1) { service.update(any()) }
    }

    "update throws 404 when the AiModel does not exist" {
        val id = UUID.randomUUID()
        every { service.findById(id) } returns null

        shouldThrow<ResourceNotFoundException> { controller.update(id, resource(id = id)) }
    }
})
