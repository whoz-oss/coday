package io.whozoss.agentos.llmModelConfig

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Unit tests for [LlmModelConfigServiceImpl].
 *
 * Uses an [InMemoryLlmModelConfigRepository] to keep tests fast and isolated.
 * Each test builds its own service instance to guarantee full isolation.
 */
class LlmModelConfigServiceImplSpec : StringSpec() {

    private fun newService(): LlmModelConfigServiceImpl =
        LlmModelConfigServiceImpl(InMemoryLlmModelConfigRepository())

    private fun modelConfig(
        llmConfigId: UUID = UUID.randomUUID(),
        apiName: String = "claude-haiku-4-5",
        alias: String? = null,
        temperature: Double? = null,
        maxTokens: Int? = null,
    ): LlmModelConfig =
        LlmModelConfig(
            metadata = EntityMetadata(),
            llmConfigId = llmConfigId,
            apiName = apiName,
            alias = alias,
            temperature = temperature,
            maxTokens = maxTokens,
        )

    init {

        // -------------------------------------------------------------------------
        // Create / Read
        // -------------------------------------------------------------------------

        "create and findById returns the same model config" {
            val service = newService()
            val m = modelConfig(alias = "SMALL", temperature = 0.3)

            val saved = service.create(m)
            val found = service.findById(saved.metadata.id)

            found.shouldNotBeNull()
            found.apiName shouldBe "claude-haiku-4-5"
            found.alias shouldBe "SMALL"
            found.temperature shouldBe 0.3
        }

        "findById returns null for unknown id" {
            val service = newService()
            service.findById(UUID.randomUUID()).shouldBeNull()
        }

        "findByParent returns only model configs for the given llmConfigId" {
            val service = newService()
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
            val service = newService()
            val providerId = UUID.randomUUID()

            service.create(modelConfig(llmConfigId = providerId, apiName = "claude-opus-4-6"))
            service.create(modelConfig(llmConfigId = providerId, apiName = "claude-haiku-4-5"))
            service.create(modelConfig(llmConfigId = providerId, apiName = "claude-sonnet-4-6"))

            val names = service.findByParent(providerId).map { it.apiName }
            names shouldBe listOf("claude-haiku-4-5", "claude-opus-4-6", "claude-sonnet-4-6")
        }

        // -------------------------------------------------------------------------
        // findByLlmConfigAndApiName
        // -------------------------------------------------------------------------

        "findByLlmConfigAndApiName returns the matching model config" {
            val service = newService()
            val providerId = UUID.randomUUID()
            service.create(modelConfig(llmConfigId = providerId, apiName = "claude-haiku-4-5"))

            val found = service.findByLlmConfigAndApiName(providerId, "claude-haiku-4-5")
            found.shouldNotBeNull()
            found.apiName shouldBe "claude-haiku-4-5"
        }

        "findByLlmConfigAndApiName returns null when apiName does not exist" {
            val service = newService()
            service.findByLlmConfigAndApiName(UUID.randomUUID(), "claude-haiku-4-5").shouldBeNull()
        }

        "findByLlmConfigAndApiName is provider-isolated" {
            val service = newService()
            val providerA = UUID.randomUUID()
            val providerB = UUID.randomUUID()
            service.create(modelConfig(llmConfigId = providerA, apiName = "claude-haiku-4-5"))

            service.findByLlmConfigAndApiName(providerB, "claude-haiku-4-5").shouldBeNull()
        }

        // -------------------------------------------------------------------------
        // findByLlmConfigAndAlias
        // -------------------------------------------------------------------------

        "findByLlmConfigAndAlias returns the matching model config" {
            val service = newService()
            val providerId = UUID.randomUUID()
            service.create(modelConfig(llmConfigId = providerId, apiName = "claude-haiku-4-5", alias = "SMALL"))

            val found = service.findByLlmConfigAndAlias(providerId, "SMALL")
            found.shouldNotBeNull()
            found.alias shouldBe "SMALL"
        }

        "findByLlmConfigAndAlias returns null when alias does not exist" {
            val service = newService()
            service.findByLlmConfigAndAlias(UUID.randomUUID(), "SMALL").shouldBeNull()
        }

        "findByLlmConfigAndAlias is provider-isolated" {
            val service = newService()
            val providerA = UUID.randomUUID()
            val providerB = UUID.randomUUID()
            service.create(modelConfig(llmConfigId = providerA, apiName = "claude-haiku-4-5", alias = "SMALL"))

            service.findByLlmConfigAndAlias(providerB, "SMALL").shouldBeNull()
        }

        // -------------------------------------------------------------------------
        // Uniqueness constraints
        // -------------------------------------------------------------------------

        "create throws 409 when (llmConfigId, apiName) already exists" {
            val service = newService()
            val providerId = UUID.randomUUID()
            service.create(modelConfig(llmConfigId = providerId, apiName = "claude-haiku-4-5"))

            shouldThrow<ResponseStatusException> {
                service.create(modelConfig(llmConfigId = providerId, apiName = "claude-haiku-4-5"))
            }.statusCode.value() shouldBe 409
        }

        "create allows same apiName under different provider configs" {
            val service = newService()
            val providerA = UUID.randomUUID()
            val providerB = UUID.randomUUID()

            service.create(modelConfig(llmConfigId = providerA, apiName = "gpt-4o"))
            service.create(modelConfig(llmConfigId = providerB, apiName = "gpt-4o"))

            service.findByParent(providerA) shouldHaveSize 1
            service.findByParent(providerB) shouldHaveSize 1
        }

        "create throws 409 when (llmConfigId, alias) already exists" {
            val service = newService()
            val providerId = UUID.randomUUID()
            service.create(modelConfig(llmConfigId = providerId, apiName = "claude-haiku-4-5", alias = "SMALL"))

            shouldThrow<ResponseStatusException> {
                service.create(modelConfig(llmConfigId = providerId, apiName = "claude-sonnet-4-6", alias = "SMALL"))
            }.statusCode.value() shouldBe 409
        }

        "create allows same alias under different provider configs" {
            val service = newService()
            val providerA = UUID.randomUUID()
            val providerB = UUID.randomUUID()

            service.create(modelConfig(llmConfigId = providerA, apiName = "claude-haiku-4-5", alias = "SMALL"))
            service.create(modelConfig(llmConfigId = providerB, apiName = "gpt-4o-mini", alias = "SMALL"))

            service.findByParent(providerA) shouldHaveSize 1
            service.findByParent(providerB) shouldHaveSize 1
        }

        "create allows null alias even when another model has no alias" {
            val service = newService()
            val providerId = UUID.randomUUID()

            service.create(modelConfig(llmConfigId = providerId, apiName = "claude-haiku-4-5", alias = null))
            service.create(modelConfig(llmConfigId = providerId, apiName = "claude-opus-4-6", alias = null))

            service.findByParent(providerId) shouldHaveSize 2
        }

        // -------------------------------------------------------------------------
        // Delete
        // -------------------------------------------------------------------------

        "delete soft-deletes the model config" {
            val service = newService()
            val providerId = UUID.randomUUID()
            val m = service.create(modelConfig(llmConfigId = providerId))

            service.delete(m.metadata.id) shouldBe true

            service.findById(m.metadata.id).shouldBeNull()
            service.findByParent(providerId).shouldBeEmpty()
        }

        "delete returns false for unknown id" {
            val service = newService()
            service.delete(UUID.randomUUID()) shouldBe false
        }

        "deleteByParent removes all model configs for a provider" {
            val service = newService()
            val providerId = UUID.randomUUID()

            service.create(modelConfig(llmConfigId = providerId, apiName = "claude-haiku-4-5"))
            service.create(modelConfig(llmConfigId = providerId, apiName = "claude-opus-4-6"))

            service.deleteByParent(providerId) shouldBe 2
            service.findByParent(providerId).shouldBeEmpty()
        }

        "deleteByParent does not affect other provider configs" {
            val service = newService()
            val providerA = UUID.randomUUID()
            val providerB = UUID.randomUUID()

            service.create(modelConfig(llmConfigId = providerA, apiName = "claude-haiku-4-5"))
            service.create(modelConfig(llmConfigId = providerB, apiName = "gpt-4o"))

            service.deleteByParent(providerA)

            service.findByParent(providerA).shouldBeEmpty()
            service.findByParent(providerB) shouldHaveSize 1
        }

        // -------------------------------------------------------------------------
        // Update
        // -------------------------------------------------------------------------

        "update replaces the model config" {
            val service = newService()
            val providerId = UUID.randomUUID()
            val original = service.create(modelConfig(llmConfigId = providerId, apiName = "claude-haiku-4-5"))

            val updated = service.update(original.copy(alias = "SMALL", temperature = 0.5))

            updated.alias shouldBe "SMALL"
            updated.temperature shouldBe 0.5
            service.findById(original.metadata.id)?.alias shouldBe "SMALL"
        }
    }
}
