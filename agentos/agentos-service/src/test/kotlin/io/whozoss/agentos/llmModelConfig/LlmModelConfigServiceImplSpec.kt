package io.whozoss.agentos.llmModelConfig

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.llmConfig.LlmConfig
import io.whozoss.agentos.llmConfig.LlmConfigService
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Unit tests for [LlmModelConfigServiceImpl].
 *
 * Uses an [InMemoryLlmModelConfigRepository] and a MockK [LlmConfigService].
 * Each test builds its own service instance to guarantee full isolation.
 */
class LlmModelConfigServiceImplSpec : StringSpec() {

    private val namespaceId = UUID.randomUUID()

    private fun stubLlmConfig(
        llmConfigId: UUID,
        nsId: UUID = namespaceId,
        userId: UUID? = null,
    ): LlmConfig =
        LlmConfig(
            metadata = EntityMetadata(id = llmConfigId),
            namespaceId = nsId,
            userId = userId,
            name = "anthropic",
            apiType = AiApiType.Anthropic,
        )

    private fun newService(llmConfigId: UUID = UUID.randomUUID()): Pair<LlmModelConfigServiceImpl, UUID> {
        val llmConfigService = mockk<LlmConfigService>()
        every { llmConfigService.getById(any()) } answers {
            stubLlmConfig(firstArg())
        }
        return LlmModelConfigServiceImpl(InMemoryLlmModelConfigRepository(), llmConfigService) to llmConfigId
    }

    private fun modelConfig(
        llmConfigId: UUID,
        nsId: UUID = namespaceId,
        apiName: String = "claude-haiku-4-5",
        alias: String? = null,
        temperature: Double? = null,
        maxTokens: Int? = null,
    ): LlmModelConfig =
        LlmModelConfig(
            metadata = EntityMetadata(),
            llmConfigId = llmConfigId,
            namespaceId = nsId,
            apiName = apiName,
            alias = alias,
            temperature = temperature,
            maxTokens = maxTokens,
        )

    init {

        // -------------------------------------------------------------------------
        // Create / Read
        // -------------------------------------------------------------------------

        "create resolves namespaceId from parent LlmConfig" {
            val (service, llmConfigId) = newService()
            val m = modelConfig(llmConfigId = llmConfigId)

            val saved = service.create(m)

            saved.namespaceId shouldBe namespaceId
        }

        "create and findById returns the same model config" {
            val (service, llmConfigId) = newService()
            val m = modelConfig(llmConfigId = llmConfigId, alias = "SMALL", temperature = 0.3)

            val saved = service.create(m)
            val found = service.findById(saved.metadata.id)

            found.shouldNotBeNull()
            found.apiName shouldBe "claude-haiku-4-5"
            found.alias shouldBe "SMALL"
            found.temperature shouldBe 0.3
        }

        "findById returns null for unknown id" {
            val (service, _) = newService()
            service.findById(UUID.randomUUID()).shouldBeNull()
        }

        "findByParent returns only model configs for the given llmConfigId" {
            val llmConfigService = mockk<LlmConfigService>()
            every { llmConfigService.getById(any()) } answers { stubLlmConfig(firstArg()) }
            val service = LlmModelConfigServiceImpl(InMemoryLlmModelConfigRepository(), llmConfigService)
            val providerA = UUID.randomUUID()
            val providerB = UUID.randomUUID()

            service.create(modelConfig(llmConfigId = providerA, apiName = "claude-haiku-4-5"))
            service.create(modelConfig(llmConfigId = providerA, apiName = "claude-opus-4-6"))
            service.create(modelConfig(llmConfigId = providerB, apiName = "gpt-4o"))

            service.findByParent(providerA) shouldHaveSize 2
            service.findByParent(providerB) shouldHaveSize 1
            service.findByParent(UUID.randomUUID()).shouldBeEmpty()
        }

        "findByParent returns model configs sorted by apiName" {
            val (service, llmConfigId) = newService()

            service.create(modelConfig(llmConfigId = llmConfigId, apiName = "claude-opus-4-6"))
            service.create(modelConfig(llmConfigId = llmConfigId, apiName = "claude-haiku-4-5"))
            service.create(modelConfig(llmConfigId = llmConfigId, apiName = "claude-sonnet-4-6"))

            val names = service.findByParent(llmConfigId).map { it.apiName }
            names shouldBe listOf("claude-haiku-4-5", "claude-opus-4-6", "claude-sonnet-4-6")
        }

        // -------------------------------------------------------------------------
        // findByLlmConfigAndApiName
        // -------------------------------------------------------------------------

        "findByLlmConfigAndApiName returns the matching model config" {
            val (service, llmConfigId) = newService()
            service.create(modelConfig(llmConfigId = llmConfigId, apiName = "claude-haiku-4-5"))

            val found = service.findByLlmConfigAndApiName(llmConfigId, "claude-haiku-4-5")
            found.shouldNotBeNull()
            found.apiName shouldBe "claude-haiku-4-5"
        }

        "findByLlmConfigAndApiName returns null when apiName does not exist" {
            val (service, _) = newService()
            service.findByLlmConfigAndApiName(UUID.randomUUID(), "claude-haiku-4-5").shouldBeNull()
        }

        // -------------------------------------------------------------------------
        // findByLlmConfigAndAlias
        // -------------------------------------------------------------------------

        "findByLlmConfigAndAlias returns the matching model config" {
            val (service, llmConfigId) = newService()
            service.create(modelConfig(llmConfigId = llmConfigId, apiName = "claude-haiku-4-5", alias = "SMALL"))

            val found = service.findByLlmConfigAndAlias(llmConfigId, "SMALL")
            found.shouldNotBeNull()
            found.alias shouldBe "SMALL"
        }

        "findByLlmConfigAndAlias returns null when alias does not exist" {
            val (service, _) = newService()
            service.findByLlmConfigAndAlias(UUID.randomUUID(), "SMALL").shouldBeNull()
        }

        // -------------------------------------------------------------------------
        // Uniqueness constraints
        // -------------------------------------------------------------------------

        "create throws 409 when (llmConfigId, apiName) already exists" {
            val (service, llmConfigId) = newService()
            service.create(modelConfig(llmConfigId = llmConfigId, apiName = "claude-haiku-4-5"))

            shouldThrow<ResponseStatusException> {
                service.create(modelConfig(llmConfigId = llmConfigId, apiName = "claude-haiku-4-5"))
            }.statusCode.value() shouldBe 409
        }

        "create throws 409 when (llmConfigId, alias) already exists" {
            val (service, llmConfigId) = newService()
            service.create(modelConfig(llmConfigId = llmConfigId, apiName = "claude-haiku-4-5", alias = "SMALL"))

            shouldThrow<ResponseStatusException> {
                service.create(modelConfig(llmConfigId = llmConfigId, apiName = "claude-sonnet-4-6", alias = "SMALL"))
            }.statusCode.value() shouldBe 409
        }

        "create allows null alias even when another model has no alias" {
            val (service, llmConfigId) = newService()

            service.create(modelConfig(llmConfigId = llmConfigId, apiName = "claude-haiku-4-5", alias = null))
            service.create(modelConfig(llmConfigId = llmConfigId, apiName = "claude-opus-4-6", alias = null))

            service.findByParent(llmConfigId) shouldHaveSize 2
        }

        // -------------------------------------------------------------------------
        // Delete
        // -------------------------------------------------------------------------

        "delete soft-deletes the model config" {
            val (service, llmConfigId) = newService()
            val m = service.create(modelConfig(llmConfigId = llmConfigId))

            service.delete(m.metadata.id) shouldBe true

            service.findById(m.metadata.id).shouldBeNull()
            service.findByParent(llmConfigId).shouldBeEmpty()
        }

        "delete returns false for unknown id" {
            val (service, _) = newService()
            service.delete(UUID.randomUUID()) shouldBe false
        }

        "deleteByParent removes all model configs for a provider" {
            val (service, llmConfigId) = newService()

            service.create(modelConfig(llmConfigId = llmConfigId, apiName = "claude-haiku-4-5"))
            service.create(modelConfig(llmConfigId = llmConfigId, apiName = "claude-opus-4-6"))

            service.deleteByParent(llmConfigId) shouldBe 2
            service.findByParent(llmConfigId).shouldBeEmpty()
        }

        // -------------------------------------------------------------------------
        // Update
        // -------------------------------------------------------------------------

        "update replaces the model config" {
            val (service, llmConfigId) = newService()
            val original = service.create(modelConfig(llmConfigId = llmConfigId, apiName = "claude-haiku-4-5"))

            val updated = service.update(original.copy(alias = "SMALL", temperature = 0.5))

            updated.alias shouldBe "SMALL"
            updated.temperature shouldBe 0.5
            service.findById(original.metadata.id)?.alias shouldBe "SMALL"
        }
    }
}
