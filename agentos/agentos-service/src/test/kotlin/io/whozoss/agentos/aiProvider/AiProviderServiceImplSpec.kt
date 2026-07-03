package io.whozoss.agentos.aiProvider

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.whozoss.agentos.exception.ConfigNotFoundException
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.UserService
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

class AiProviderServiceImplSpec : StringSpec() {
    // PermissionService and UserService are only used by findFiltered, which is not
    // exercised in this spec (tested via AiProviderControllerSpec + integration tests).
    // Relaxed mocks satisfy the constructor without interfering with the tested methods.
    private fun newService() = AiProviderServiceImpl(
        InMemoryAiProviderRepository(),
        AiProviderMergeStrategy(),
        mockk<PermissionService>(relaxed = true),
        mockk<UserService>(relaxed = true),
    )

    private fun config(
        namespaceId: UUID? = UUID.randomUUID(),
        userId: UUID? = null,
        name: String = "anthropic",
        apiType: AiApiType = AiApiType.Anthropic,
        apiKey: String? = null,
    ): AiProvider =
        AiProvider(
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

        "create succeeds with both namespaceId and userId null (platform scope)" {
            val service = newService()
            // Platform-level entities have namespaceId=null AND userId=null.
            // The controller enforces the super-admin permission check; the service
            // itself allows all scope combinations.
            val saved = service.create(config(namespaceId = null, userId = null))
            saved.shouldNotBeNull()
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

            service.findByNamespaceId(nsA) shouldHaveSize 1 // namespace-shared only (userId IS NULL filter per AC14)
            service.findByNamespaceId(nsB) shouldHaveSize 1
            service.findByUserId(userId) shouldHaveSize 2 // user-only + namespace+user
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

        "update allows renaming to the same name (no false conflict with self)" {
            val service = newService()
            val nsId = UUID.randomUUID()
            val original = service.create(config(namespaceId = nsId, name = "anthropic"))

            // updating with same name should not throw
            val updated = service.update(original.copy(apiKey = "new-key"))
            updated.name shouldBe "anthropic"
        }

        "update throws 409 when renaming conflicts with another config in the same scope" {
            val service = newService()
            val nsId = UUID.randomUUID()
            service.create(config(namespaceId = nsId, name = "openai"))
            val toUpdate = service.create(config(namespaceId = nsId, name = "anthropic"))

            shouldThrow<ResponseStatusException> {
                service.update(toUpdate.copy(name = "openai"))
            }.statusCode.value() shouldBe 409
        }

        // -------------------------------------------------------------------------
        // Cross-layer apiType consistency (IG-3 equivalent)
        //
        // The 3-tier reconciliation merges layers param-by-param assuming all layers share the
        // same `apiType`. If they diverge, the merged provider silently switches the chat client
        // at runtime — failing opaquely deep in the agent run.
        // -------------------------------------------------------------------------

        "create user×ns rejects when NS-shared layer has same name with different apiType" {
            val service = newService()
            val nsId = UUID.randomUUID()
            val userId = UUID.randomUUID()
            service.create(config(namespaceId = nsId, userId = null, name = "primary", apiType = AiApiType.Anthropic))

            shouldThrow<ResponseStatusException> {
                service.create(config(namespaceId = nsId, userId = userId, name = "primary", apiType = AiApiType.OpenAI))
            }.statusCode.value() shouldBe 409
        }

        "create user-global rejects when same-user user×ns layer has same name with different apiType" {
            val service = newService()
            val nsId = UUID.randomUUID()
            val userId = UUID.randomUUID()
            service.create(config(namespaceId = nsId, userId = userId, name = "primary", apiType = AiApiType.Anthropic))

            shouldThrow<ResponseStatusException> {
                service.create(config(namespaceId = null, userId = userId, name = "primary", apiType = AiApiType.OpenAI))
            }.statusCode.value() shouldBe 409
        }

        "create user×ns rejects when same-user user-global layer has same name with different apiType" {
            val service = newService()
            val nsId = UUID.randomUUID()
            val userId = UUID.randomUUID()
            service.create(config(namespaceId = null, userId = userId, name = "primary", apiType = AiApiType.Anthropic))

            shouldThrow<ResponseStatusException> {
                service.create(config(namespaceId = nsId, userId = userId, name = "primary", apiType = AiApiType.OpenAI))
            }.statusCode.value() shouldBe 409
        }

        "create allows same name across layers when apiType matches" {
            val service = newService()
            val nsId = UUID.randomUUID()
            val userId = UUID.randomUUID()

            service.create(config(namespaceId = nsId, userId = null, name = "primary", apiType = AiApiType.Anthropic))
            // Same apiType — should merge cleanly at reconciliation, no 409.
            service.create(config(namespaceId = null, userId = userId, name = "primary", apiType = AiApiType.Anthropic))
            service.create(config(namespaceId = nsId, userId = userId, name = "primary", apiType = AiApiType.Anthropic))

            service.findByUserId(userId) shouldHaveSize 2
        }

        "create user-global allows same name as another user's user-global with different apiType (cross-user is by-design)" {
            val service = newService()
            val userA = UUID.randomUUID()
            val userB = UUID.randomUUID()
            service.create(config(namespaceId = null, userId = userA, name = "primary", apiType = AiApiType.Anthropic))

            // Different user → no overlap at reconciliation, no conflict.
            val saved = service.create(config(namespaceId = null, userId = userB, name = "primary", apiType = AiApiType.OpenAI))
            saved.shouldNotBeNull()
        }

        // -------------------------------------------------------------------------
        // resolveProvider — single-query fold replacing ConfigMergeService
        // -------------------------------------------------------------------------

        "resolveProvider returns platform layer when no other layer exists" {
            val service = newService()
            val nsId = UUID.randomUUID()
            val userId = UUID.randomUUID()
            service.create(config(namespaceId = null, userId = null, name = "anthropic", apiKey = "platform-key"))

            val resolved = service.resolveProvider(nsId, userId, "anthropic")
            resolved.apiKey shouldBe "platform-key"
            resolved.namespaceId shouldBe null
            resolved.userId shouldBe null
        }

        "resolveProvider namespace-shared overrides platform" {
            val service = newService()
            val nsId = UUID.randomUUID()
            val userId = UUID.randomUUID()
            service.create(config(namespaceId = null,  userId = null,  name = "anthropic", apiKey = "platform-key"))
            service.create(config(namespaceId = nsId,  userId = null,  name = "anthropic", apiKey = "ns-key"))

            val resolved = service.resolveProvider(nsId, userId, "anthropic")
            resolved.apiKey shouldBe "ns-key"
        }

        "resolveProvider user-global overrides platform but namespace-shared overrides user-global" {
            val service = newService()
            val nsId = UUID.randomUUID()
            val userId = UUID.randomUUID()
            service.create(config(namespaceId = null, userId = null,   name = "anthropic", apiKey = "platform-key"))
            service.create(config(namespaceId = null, userId = userId,  name = "anthropic", apiKey = "user-key"))
            service.create(config(namespaceId = nsId, userId = null,   name = "anthropic", apiKey = "ns-key"))

            // namespace-shared (rank 2) wins over user-global (rank 1)
            val resolved = service.resolveProvider(nsId, userId, "anthropic")
            resolved.apiKey shouldBe "ns-key"
        }

        "resolveProvider user×namespace is highest precedence" {
            val service = newService()
            val nsId = UUID.randomUUID()
            val userId = UUID.randomUUID()
            service.create(config(namespaceId = null, userId = null,   name = "anthropic", apiKey = "platform-key"))
            service.create(config(namespaceId = nsId, userId = null,   name = "anthropic", apiKey = "ns-key"))
            service.create(config(namespaceId = null, userId = userId,  name = "anthropic", apiKey = "user-key"))
            service.create(config(namespaceId = nsId, userId = userId,  name = "anthropic", apiKey = "user-ns-key"))

            val resolved = service.resolveProvider(nsId, userId, "anthropic")
            resolved.apiKey shouldBe "user-ns-key"
        }

        "resolveProvider merges fields — base fills keys absent from override" {
            val service = newService()
            val nsId = UUID.randomUUID()
            val userId = UUID.randomUUID()
            // Platform provides baseUrl; namespace-shared provides apiKey
            service.create(config(namespaceId = null, userId = null,  name = "anthropic",
                apiKey = null, apiType = AiApiType.Anthropic).copy(baseUrl = "https://platform.api"))
            service.create(config(namespaceId = nsId, userId = null,  name = "anthropic",
                apiKey = "ns-key", apiType = AiApiType.Anthropic))

            val resolved = service.resolveProvider(nsId, userId, "anthropic")
            resolved.apiKey shouldBe "ns-key"
            resolved.baseUrl shouldBe "https://platform.api"
        }

        "resolveProvider throws ConfigNotFoundException when no layer has the requested name" {
            val service = newService()
            val nsId = UUID.randomUUID()
            val userId = UUID.randomUUID()
            service.create(config(namespaceId = nsId, userId = null, name = "other-provider"))

            shouldThrow<ConfigNotFoundException> {
                service.resolveProvider(nsId, userId, "anthropic")
            }
        }

        "update rejects when renaming would collide with a different-apiType layer" {
            val service = newService()
            val nsId = UUID.randomUUID()
            val userId = UUID.randomUUID()
            service.create(config(namespaceId = nsId, userId = null, name = "primary", apiType = AiApiType.Anthropic))
            val mine = service.create(config(namespaceId = nsId, userId = userId, name = "secondary", apiType = AiApiType.OpenAI))

            shouldThrow<ResponseStatusException> {
                service.update(mine.copy(name = "primary"))
            }.statusCode.value() shouldBe 409
        }
    }
}
