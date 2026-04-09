package io.whozoss.agentos.llmConfig

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Unit tests for [LlmConfigServiceImpl].
 *
 * Uses an [InMemoryLlmConfigRepository] to keep tests fast and isolated.
 * Each test builds its own service instance to guarantee full isolation.
 */
class LlmConfigServiceImplSpec : StringSpec() {

    private fun newService(): LlmConfigServiceImpl =
        LlmConfigServiceImpl(InMemoryLlmConfigRepository())

    private fun config(
        namespaceId: UUID = UUID.randomUUID(),
        name: String = "anthropic",
        apiType: AiApiType = AiApiType.Anthropic,
        apiKey: String? = null,
        models: List<LlmModelEntry> = emptyList(),
    ): LlmConfig =
        LlmConfig(
            metadata = EntityMetadata(),
            namespaceId = namespaceId,
            name = name,
            apiType = apiType,
            apiKey = apiKey,
            models = models,
        )

    init {

        // -------------------------------------------------------------------------
        // Create / Read
        // -------------------------------------------------------------------------

        "create and findById returns the same config" {
            val service = newService()
            val cfg = config()

            val saved = service.create(cfg)
            val found = service.findById(saved.metadata.id)

            found.shouldNotBeNull()
            found.metadata.id shouldBe saved.metadata.id
            found.name shouldBe "anthropic"
            found.apiType shouldBe AiApiType.Anthropic
        }

        "findById returns null for unknown id" {
            val service = newService()
            service.findById(UUID.randomUUID()).shouldBeNull()
        }

        "findByParent returns only configs for the given namespace" {
            val service = newService()
            val nsA = UUID.randomUUID()
            val nsB = UUID.randomUUID()

            service.create(config(namespaceId = nsA, name = "anthropic"))
            service.create(config(namespaceId = nsA, name = "openai"))
            service.create(config(namespaceId = nsB, name = "gemini"))

            service.findByParent(nsA) shouldHaveSize 2
            service.findByParent(nsB) shouldHaveSize 1
            service.findByParent(UUID.randomUUID()).shouldBeEmpty()
        }

        "findByParent returns configs sorted by name" {
            val service = newService()
            val nsId = UUID.randomUUID()

            service.create(config(namespaceId = nsId, name = "openai"))
            service.create(config(namespaceId = nsId, name = "anthropic"))
            service.create(config(namespaceId = nsId, name = "gemini"))

            val names = service.findByParent(nsId).map { it.name }
            names shouldBe listOf("anthropic", "gemini", "openai")
        }

        // -------------------------------------------------------------------------
        // findByNamespaceAndName
        // -------------------------------------------------------------------------

        "findByNamespaceAndName returns the matching config" {
            val service = newService()
            val nsId = UUID.randomUUID()
            service.create(config(namespaceId = nsId, name = "anthropic"))

            val found = service.findByNamespaceAndName(nsId, "anthropic")
            found.shouldNotBeNull()
            found.name shouldBe "anthropic"
        }

        "findByNamespaceAndName returns null when name does not exist" {
            val service = newService()
            service.findByNamespaceAndName(UUID.randomUUID(), "anthropic").shouldBeNull()
        }

        "findByNamespaceAndName is namespace-isolated" {
            val service = newService()
            val nsA = UUID.randomUUID()
            val nsB = UUID.randomUUID()
            service.create(config(namespaceId = nsA, name = "anthropic"))

            service.findByNamespaceAndName(nsB, "anthropic").shouldBeNull()
        }

        // -------------------------------------------------------------------------
        // Uniqueness constraint
        // -------------------------------------------------------------------------

        "create throws 409 when (namespaceId, name) already exists" {
            val service = newService()
            val nsId = UUID.randomUUID()
            service.create(config(namespaceId = nsId, name = "anthropic"))

            shouldThrow<ResponseStatusException> {
                service.create(config(namespaceId = nsId, name = "anthropic"))
            }.statusCode.value() shouldBe 409
        }

        "create allows same name in different namespaces" {
            val service = newService()
            val nsA = UUID.randomUUID()
            val nsB = UUID.randomUUID()

            service.create(config(namespaceId = nsA, name = "anthropic"))
            service.create(config(namespaceId = nsB, name = "anthropic")) // must not throw

            service.findByParent(nsA) shouldHaveSize 1
            service.findByParent(nsB) shouldHaveSize 1
        }

        // -------------------------------------------------------------------------
        // Models list
        // -------------------------------------------------------------------------

        "config with models is persisted and retrieved correctly" {
            val service = newService()
            val models = listOf(
                LlmModelEntry(apiName = "claude-haiku-4-5", alias = "SMALL"),
                LlmModelEntry(apiName = "claude-opus-4-6", alias = "BIG", temperature = 0.7),
            )
            val cfg = config(models = models)
            val saved = service.create(cfg)

            val found = service.findById(saved.metadata.id)
            found.shouldNotBeNull()
            found.models shouldHaveSize 2
            found.models[0].apiName shouldBe "claude-haiku-4-5"
            found.models[0].alias shouldBe "SMALL"
            found.models[1].temperature shouldBe 0.7
        }

        "config with empty models list is valid" {
            val service = newService()
            val saved = service.create(config(models = emptyList()))

            val found = service.findById(saved.metadata.id)
            found.shouldNotBeNull()
            found.models.shouldBeEmpty()
        }

        // -------------------------------------------------------------------------
        // apiKey storage
        // -------------------------------------------------------------------------

        "apiKey is stored and retrieved in clear text" {
            val service = newService()
            val saved = service.create(config(apiKey = "sk-ant-api03-secret"))

            val found = service.findById(saved.metadata.id)
            found.shouldNotBeNull()
            found.apiKey shouldBe "sk-ant-api03-secret"
        }

        "apiKey null is preserved" {
            val service = newService()
            val saved = service.create(config(apiKey = null))

            service.findById(saved.metadata.id)?.apiKey.shouldBeNull()
        }

        // -------------------------------------------------------------------------
        // Delete
        // -------------------------------------------------------------------------

        "delete soft-deletes the config" {
            val service = newService()
            val nsId = UUID.randomUUID()
            val cfg = service.create(config(namespaceId = nsId))

            service.delete(cfg.metadata.id) shouldBe true

            service.findById(cfg.metadata.id).shouldBeNull()
            service.findByParent(nsId).shouldBeEmpty()
        }

        "delete returns false for unknown id" {
            val service = newService()
            service.delete(UUID.randomUUID()) shouldBe false
        }

        "deleteByParent removes all configs for a namespace" {
            val service = newService()
            val nsId = UUID.randomUUID()

            service.create(config(namespaceId = nsId, name = "anthropic"))
            service.create(config(namespaceId = nsId, name = "openai"))

            val count = service.deleteByParent(nsId)
            count shouldBe 2
            service.findByParent(nsId).shouldBeEmpty()
        }

        "deleteByParent does not affect other namespaces" {
            val service = newService()
            val nsA = UUID.randomUUID()
            val nsB = UUID.randomUUID()

            service.create(config(namespaceId = nsA, name = "anthropic"))
            service.create(config(namespaceId = nsB, name = "anthropic"))

            service.deleteByParent(nsA)

            service.findByParent(nsA).shouldBeEmpty()
            service.findByParent(nsB) shouldHaveSize 1
        }

        // -------------------------------------------------------------------------
        // Update
        // -------------------------------------------------------------------------

        "update replaces the config" {
            val service = newService()
            val nsId = UUID.randomUUID()
            val original = service.create(config(namespaceId = nsId, apiKey = "old-key"))

            val updated = service.update(original.copy(apiKey = "new-key"))

            updated.apiKey shouldBe "new-key"
            service.findById(original.metadata.id)?.apiKey shouldBe "new-key"
        }
    }
}
