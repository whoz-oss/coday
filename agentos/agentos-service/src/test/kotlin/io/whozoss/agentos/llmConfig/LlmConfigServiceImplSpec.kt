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

class LlmConfigServiceImplSpec : StringSpec() {

    private fun newService() = LlmConfigServiceImpl(InMemoryLlmConfigRepository())

    private fun config(
        namespaceId: UUID? = UUID.randomUUID(),
        userId: UUID? = null,
        name: String = "anthropic",
        apiType: AiApiType = AiApiType.Anthropic,
        apiKey: String? = null,
    ): LlmConfig =
        LlmConfig(
            metadata = EntityMetadata(),
            namespaceId = namespaceId,
            userId = userId,
            name = name,
            apiType = apiType,
            apiKey = apiKey,
        )

    init {

        // -------------------------------------------------------------------------
        // Scope invariant
        // -------------------------------------------------------------------------

        "create throws 400 when both namespaceId and userId are null" {
            val service = newService()
            shouldThrow<ResponseStatusException> {
                service.create(config(namespaceId = null, userId = null))
            }.statusCode.value() shouldBe 400
        }

        "create succeeds with namespaceId only" {
            val service = newService()
            val saved = service.create(config(namespaceId = UUID.randomUUID(), userId = null))
            saved.shouldNotBeNull()
        }

        "create succeeds with userId only" {
            val service = newService()
            val saved = service.create(config(namespaceId = null, userId = UUID.randomUUID()))
            saved.shouldNotBeNull()
        }

        "create succeeds with both namespaceId and userId" {
            val service = newService()
            val saved = service.create(config(namespaceId = UUID.randomUUID(), userId = UUID.randomUUID()))
            saved.shouldNotBeNull()
        }

        // -------------------------------------------------------------------------
        // Create / Read
        // -------------------------------------------------------------------------

        "create and findById returns the same config" {
            val service = newService()
            val saved = service.create(config())
            val found = service.findById(saved.metadata.id)

            found.shouldNotBeNull()
            found.name shouldBe "anthropic"
            found.apiType shouldBe AiApiType.Anthropic
        }

        "findById returns null for unknown id" {
            val service = newService()
            service.findById(UUID.randomUUID()).shouldBeNull()
        }

        // -------------------------------------------------------------------------
        // findByNamespaceId
        // -------------------------------------------------------------------------

        "findByNamespaceId returns only configs for the given namespace" {
            val service = newService()
            val nsA = UUID.randomUUID()
            val nsB = UUID.randomUUID()

            service.create(config(namespaceId = nsA, name = "anthropic"))
            service.create(config(namespaceId = nsA, name = "openai"))
            service.create(config(namespaceId = nsB, name = "gemini"))

            service.findByNamespaceId(nsA) shouldHaveSize 2
            service.findByNamespaceId(nsB) shouldHaveSize 1
            service.findByNamespaceId(UUID.randomUUID()).shouldBeEmpty()
        }

        "findByNamespaceId returns configs sorted by name" {
            val service = newService()
            val nsId = UUID.randomUUID()

            service.create(config(namespaceId = nsId, name = "openai"))
            service.create(config(namespaceId = nsId, name = "anthropic"))
            service.create(config(namespaceId = nsId, name = "gemini"))

            service.findByNamespaceId(nsId).map { it.name } shouldBe listOf("anthropic", "gemini", "openai")
        }

        // -------------------------------------------------------------------------
        // findByUserId
        // -------------------------------------------------------------------------

        "findByUserId returns only configs for the given user" {
            val service = newService()
            val userA = UUID.randomUUID()
            val userB = UUID.randomUUID()

            service.create(config(namespaceId = null, userId = userA, name = "anthropic"))
            service.create(config(namespaceId = null, userId = userA, name = "openai"))
            service.create(config(namespaceId = null, userId = userB, name = "gemini"))

            service.findByUserId(userA) shouldHaveSize 2
            service.findByUserId(userB) shouldHaveSize 1
            service.findByUserId(UUID.randomUUID()).shouldBeEmpty()
        }

        // -------------------------------------------------------------------------
        // Uniqueness constraint
        // -------------------------------------------------------------------------

        "create throws 409 when (namespaceId, userId, name) already exists" {
            val service = newService()
            val nsId = UUID.randomUUID()
            service.create(config(namespaceId = nsId, userId = null, name = "anthropic"))

            shouldThrow<ResponseStatusException> {
                service.create(config(namespaceId = nsId, userId = null, name = "anthropic"))
            }.statusCode.value() shouldBe 409
        }

        "create allows same name for different scopes" {
            val service = newService()
            val nsA = UUID.randomUUID()
            val nsB = UUID.randomUUID()
            val userId = UUID.randomUUID()

            service.create(config(namespaceId = nsA, userId = null, name = "anthropic"))
            service.create(config(namespaceId = nsB, userId = null, name = "anthropic"))
            service.create(config(namespaceId = null, userId = userId, name = "anthropic"))
            service.create(config(namespaceId = nsA, userId = userId, name = "anthropic"))

            service.findByNamespaceId(nsA) shouldHaveSize 2 // namespace-only + namespace+user
            service.findByNamespaceId(nsB) shouldHaveSize 1
            service.findByUserId(userId) shouldHaveSize 2  // user-only + namespace+user
        }

        // -------------------------------------------------------------------------
        // apiKey
        // -------------------------------------------------------------------------

        "apiKey is stored and retrieved in clear text" {
            val service = newService()
            val saved = service.create(config(apiKey = "sk-ant-api03-secret"))
            service.findById(saved.metadata.id)?.apiKey shouldBe "sk-ant-api03-secret"
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
            service.findByNamespaceId(nsId).shouldBeEmpty()
        }

        "delete returns false for unknown id" {
            val service = newService()
            service.delete(UUID.randomUUID()) shouldBe false
        }

        // -------------------------------------------------------------------------
        // Update
        // -------------------------------------------------------------------------

        "update replaces the config" {
            val service = newService()
            val original = service.create(config(apiKey = "old-key"))
            val updated = service.update(original.copy(apiKey = "new-key"))

            updated.apiKey shouldBe "new-key"
            service.findById(original.metadata.id)?.apiKey shouldBe "new-key"
        }
    }
}
