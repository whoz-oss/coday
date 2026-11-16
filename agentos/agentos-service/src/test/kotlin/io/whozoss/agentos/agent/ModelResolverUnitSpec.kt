package io.whozoss.agentos.agent

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.aiModel.AiModelService
import io.whozoss.agentos.aiProvider.AiProviderService
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID

class ModelResolverUnitSpec :
    StringSpec({
        val namespaceId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val providerId = UUID.randomUUID()

        val baseProvider =
            AiProvider(
                metadata = EntityMetadata(id = providerId),
                namespaceId = namespaceId,
                name = "anthropic",
                apiType = AiApiType.Anthropic,
                apiKey = "base-key",
            )
        val overlaidProvider = baseProvider.copy(apiKey = "user-key")
        val model =
            AiModel(
                metadata = EntityMetadata(id = UUID.randomUUID()),
                aiProviderId = providerId,
                namespaceId = namespaceId,
                apiModelName = "claude-sonnet-4-5",
                alias = "default",
            )

        val aiModelService = mockk<AiModelService>()
        val aiProviderService = mockk<AiProviderService>()
        val resolver = ModelResolver(aiModelService, aiProviderService)

        beforeTest {
            every { aiProviderService.getById(providerId) } returns baseProvider
            every { aiProviderService.resolveProvider(namespaceId, userId, "anthropic") } returns overlaidProvider
        }

        "without a model name, resolves the namespace default model" {
            // findAiModel(namespaceId) is findAiModel(namespaceId, "default"): the default lives in the
            // parameter, so the mock only ever sees the two-argument call.
            every { aiModelService.findAiModel(namespaceId) } returns model

            resolver.resolve(namespaceId, userId = null)?.first shouldBe model

            verify(exactly = 1) { aiModelService.findAiModel(namespaceId, "default") }
            verify(exactly = 0) { aiModelService.findAiModel(namespaceId, neq("default")) }
        }

        "with a model name, looks that name up" {
            every { aiModelService.findAiModel(namespaceId, "opus") } returns model

            resolver.resolve(namespaceId, userId = null, modelName = "opus")?.first shouldBe model

            verify(exactly = 1) { aiModelService.findAiModel(namespaceId, "opus") }
        }

        "without a user, the provider is the stored one, with no overlay lookup" {
            every { aiModelService.findAiModel(namespaceId) } returns model

            val (resolvedModel, provider) = resolver.resolve(namespaceId, userId = null).shouldNotBeNull()

            resolvedModel shouldBe model
            provider shouldBe baseProvider
            verify(exactly = 0) { aiProviderService.resolveProvider(any(), any(), any()) }
        }

        "with a user, the provider carries the overlays of that user" {
            every { aiModelService.findAiModel(namespaceId) } returns model

            val (_, provider) = resolver.resolve(namespaceId, userId).shouldNotBeNull()

            provider shouldBe overlaidProvider
            verify(exactly = 1) { aiProviderService.resolveProvider(namespaceId, userId, "anthropic") }
        }

        "returns null when the namespace has no such model, and looks no provider up" {
            every { aiModelService.findAiModel(namespaceId) } returns null
            every { aiModelService.findAiModel(namespaceId, "ghost") } returns null

            resolver.resolve(namespaceId, userId).shouldBeNull()
            resolver.resolve(namespaceId, userId, "ghost").shouldBeNull()

            verify(exactly = 0) { aiProviderService.getById(any()) }
        }
    })
