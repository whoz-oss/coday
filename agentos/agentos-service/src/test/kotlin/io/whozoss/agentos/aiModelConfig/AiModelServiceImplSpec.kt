package io.whozoss.agentos.aiModelConfig

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.aiModel.AiModelServiceImpl
import io.whozoss.agentos.aiModel.InMemoryAiModelRepository
import io.whozoss.agentos.aiProvider.AiProviderService
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Unit tests for [io.whozoss.agentos.aiModel.AiModelServiceImpl].
 *
 * Uses an [io.whozoss.agentos.aiModel.InMemoryAiModelRepository] and a MockK [AiProviderService].
 * Each test builds its own service instance to guarantee full isolation.
 */
class AiModelServiceImplSpec : StringSpec() {
    private val namespaceId = UUID.randomUUID()

    private fun stubAiProvider(
        aiProviderId: UUID,
        nsId: UUID = namespaceId,
        userId: UUID? = null,
    ): AiProvider =
        AiProvider(
            metadata = EntityMetadata(id = aiProviderId),
            namespaceId = nsId,
            userId = userId,
            name = "anthropic",
            apiType = AiApiType.Anthropic,
        )

    private fun newService(aiProviderId: UUID = UUID.randomUUID()): Pair<AiModelServiceImpl, UUID> {
        val aiProviderService = mockk<AiProviderService>()
        every { aiProviderService.getById(any()) } answers {
            stubAiProvider(firstArg())
        }
        return AiModelServiceImpl(InMemoryAiModelRepository(), aiProviderService) to aiProviderId
    }

    private fun modelConfig(
        aiProviderId: UUID,
        nsId: UUID = namespaceId,
        apiName: String = "claude-haiku-4-5",
        alias: String? = null,
        priority: Int = 0,
        temperature: Double? = null,
        maxTokens: Int? = null,
    ): AiModel =
        AiModel(
            metadata = EntityMetadata(),
            aiProviderId = aiProviderId,
            namespaceId = nsId,
            apiModelName = apiName,
            alias = alias,
            priority = priority,
            temperature = temperature,
            maxTokens = maxTokens,
        )

    init {

        // -------------------------------------------------------------------------
        // Create / Read
        // -------------------------------------------------------------------------

        "create resolves namespaceId from parent AiProvider" {
            val (service, aiProviderId) = newService()
            val m = modelConfig(aiProviderId = aiProviderId)

            val saved = service.create(m)

            saved.namespaceId shouldBe namespaceId
        }

        "create and findById returns the same model config" {
            val (service, aiProviderId) = newService()
            val m = modelConfig(aiProviderId = aiProviderId, alias = "SMALL", temperature = 0.3)

            val saved = service.create(m)
            val found = service.findById(saved.metadata.id)

            found.shouldNotBeNull()
            found.apiModelName shouldBe "claude-haiku-4-5"
            found.alias shouldBe "SMALL"
            found.temperature shouldBe 0.3
        }

        "findById returns null for unknown id" {
            val (service, _) = newService()
            service.findById(UUID.randomUUID()).shouldBeNull()
        }

        "findByParent returns only model configs for the given aiProviderId" {
            val aiProviderService = mockk<AiProviderService>()
            every { aiProviderService.getById(any()) } answers { stubAiProvider(firstArg()) }
            val service = AiModelServiceImpl(InMemoryAiModelRepository(), aiProviderService)
            val providerA = UUID.randomUUID()
            val providerB = UUID.randomUUID()

            service.create(modelConfig(aiProviderId = providerA, apiName = "claude-haiku-4-5"))
            service.create(modelConfig(aiProviderId = providerA, apiName = "claude-opus-4-6"))
            service.create(modelConfig(aiProviderId = providerB, apiName = "gpt-4o"))

            service.findByParent(providerA) shouldHaveSize 2
            service.findByParent(providerB) shouldHaveSize 1
            service.findByParent(UUID.randomUUID()).shouldBeEmpty()
        }

        "findByParent returns model configs sorted by apiName" {
            val (service, aiProviderId) = newService()

            service.create(modelConfig(aiProviderId = aiProviderId, apiName = "claude-opus-4-6"))
            service.create(modelConfig(aiProviderId = aiProviderId, apiName = "claude-haiku-4-5"))
            service.create(modelConfig(aiProviderId = aiProviderId, apiName = "claude-sonnet-4-6"))

            val names = service.findByParent(aiProviderId).map { it.apiModelName }
            names shouldBe listOf("claude-haiku-4-5", "claude-opus-4-6", "claude-sonnet-4-6")
        }

        // -------------------------------------------------------------------------
        // findByAiProviderAndApiName
        // -------------------------------------------------------------------------

        "findByAiProviderAndApiName returns the matching model config" {
            val (service, aiProviderId) = newService()
            service.create(modelConfig(aiProviderId = aiProviderId, apiName = "claude-haiku-4-5"))

            val found = service.findByAiProviderAndApiName(aiProviderId, "claude-haiku-4-5")
            found.shouldNotBeNull()
            found.apiModelName shouldBe "claude-haiku-4-5"
        }

        "findByAiProviderAndApiName returns null when apiName does not exist" {
            val (service, _) = newService()
            service.findByAiProviderAndApiName(UUID.randomUUID(), "claude-haiku-4-5").shouldBeNull()
        }

        // -------------------------------------------------------------------------
        // findByAiProviderAndAlias
        // -------------------------------------------------------------------------

        "findByAiProviderAndAlias returns the matching model config" {
            val (service, aiProviderId) = newService()
            service.create(modelConfig(aiProviderId = aiProviderId, apiName = "claude-haiku-4-5", alias = "SMALL"))

            val found = service.findByAiProviderAndAlias(aiProviderId, "SMALL")
            found.shouldNotBeNull()
            found.alias shouldBe "SMALL"
        }

        "findByAiProviderAndAlias returns null when alias does not exist" {
            val (service, _) = newService()
            service.findByAiProviderAndAlias(UUID.randomUUID(), "SMALL").shouldBeNull()
        }

        // -------------------------------------------------------------------------
        // Uniqueness constraints
        // -------------------------------------------------------------------------

        "create allows two models with the same apiName under the same provider when aliases differ" {
            // Uniqueness is on alias only — same apiName with different params (e.g. temperature)
            // is a valid use-case (e.g. 'haiku-fast' vs 'haiku-careful' using the same model).
            val (service, aiProviderId) = newService()
            service.create(modelConfig(aiProviderId = aiProviderId, apiName = "claude-haiku-4-5", alias = "haiku-fast", temperature = 1.0))
            service.create(modelConfig(aiProviderId = aiProviderId, apiName = "claude-haiku-4-5", alias = "haiku-careful", temperature = 0.2))

            service.findByParent(aiProviderId) shouldHaveSize 2
        }

        "create throws 409 when (aiProviderId, alias) already exists" {
            val (service, aiProviderId) = newService()
            service.create(modelConfig(aiProviderId = aiProviderId, apiName = "claude-haiku-4-5", alias = "SMALL"))

            shouldThrow<ResponseStatusException> {
                service.create(modelConfig(aiProviderId = aiProviderId, apiName = "claude-sonnet-4-6", alias = "SMALL"))
            }.statusCode.value() shouldBe 409
        }

        "create allows null alias even when another model has no alias" {
            val (service, aiProviderId) = newService()

            service.create(modelConfig(aiProviderId = aiProviderId, apiName = "claude-haiku-4-5", alias = null))
            service.create(modelConfig(aiProviderId = aiProviderId, apiName = "claude-opus-4-6", alias = null))

            service.findByParent(aiProviderId) shouldHaveSize 2
        }

        // -------------------------------------------------------------------------
        // Delete
        // -------------------------------------------------------------------------

        "delete soft-deletes the model config" {
            val (service, aiProviderId) = newService()
            val m = service.create(modelConfig(aiProviderId = aiProviderId))

            service.delete(m.metadata.id) shouldBe true

            service.findById(m.metadata.id).shouldBeNull()
            service.findByParent(aiProviderId).shouldBeEmpty()
        }

        "delete returns false for unknown id" {
            val (service, _) = newService()
            service.delete(UUID.randomUUID()) shouldBe false
        }

        // -------------------------------------------------------------------------
        // findModelConfig — namespace-scoped resolution
        // -------------------------------------------------------------------------

        "findModelConfig returns the config aliased 'default'" {
            val (service, aiProviderId) = newService()
            service.create(modelConfig(aiProviderId = aiProviderId, apiName = "claude-sonnet-4-5", alias = "default"))

            val found = service.findAiModel(namespaceId)
            found.shouldNotBeNull()
            found.alias shouldBe "default"
        }

        "findModelConfig returns the highest-priority config when multiple are named 'default' across different providers" {
            // Two different aiProviderIds in the same namespace can each have a "default" alias —
            // the uniqueness constraint is per (aiProviderId, alias), not per namespace.
            val aiProviderService = mockk<AiProviderService>()
            val providerA = UUID.randomUUID()
            val providerB = UUID.randomUUID()
            every { aiProviderService.getById(providerA) } answers { stubAiProvider(providerA) }
            every { aiProviderService.getById(providerB) } answers { stubAiProvider(providerB) }
            val service = AiModelServiceImpl(InMemoryAiModelRepository(), aiProviderService)

            service.create(modelConfig(aiProviderId = providerA, apiName = "claude-haiku-4-5", alias = "default", priority = 0))
            service.create(modelConfig(aiProviderId = providerB, apiName = "claude-sonnet-4-5", alias = "default", priority = 10))

            val found = service.findAiModel(namespaceId)
            found.shouldNotBeNull()
            found.apiModelName shouldBe "claude-sonnet-4-5"
        }

        "findModelConfig is case-insensitive on the alias" {
            val (service, aiProviderId) = newService()
            service.create(modelConfig(aiProviderId = aiProviderId, apiName = "claude-sonnet-4-5", alias = "Default"))

            service.findAiModel(namespaceId).shouldNotBeNull()
        }

        "findModelConfig returns null when no config carries the requested alias" {
            val (service, aiProviderId) = newService()
            service.create(modelConfig(aiProviderId = aiProviderId, apiName = "claude-sonnet-4-5", alias = "sonnet", priority = 100))

            service.findAiModel(namespaceId).shouldBeNull()
        }

        "findModelConfig ignores high-priority configs with a different alias" {
            val (service, aiProviderId) = newService()
            service.create(modelConfig(aiProviderId = aiProviderId, apiName = "gpt-4o", alias = "big", priority = 100))
            service.create(modelConfig(aiProviderId = aiProviderId, apiName = "claude-sonnet-4-5", alias = "default", priority = 1))

            val found = service.findAiModel(namespaceId)
            found.shouldNotBeNull()
            found.apiModelName shouldBe "claude-sonnet-4-5"
        }

        "findModelConfig accepts a custom name" {
            val (service, aiProviderId) = newService()
            service.create(modelConfig(aiProviderId = aiProviderId, apiName = "claude-haiku-4-5", alias = "small"))

            service.findAiModel(namespaceId, "small").shouldNotBeNull()
            service.findAiModel(namespaceId, "default").shouldBeNull()
        }

        "findModelConfig returns null for an empty namespace" {
            val (service, _) = newService()
            service.findAiModel(namespaceId).shouldBeNull()
        }

        "findModelConfig falls back to apiName when no alias matches" {
            val (service, aiProviderId) = newService()
            service.create(modelConfig(aiProviderId = aiProviderId, apiName = "claude-sonnet-4-5", alias = null))

            val found = service.findAiModel(namespaceId, "claude-sonnet-4-5")
            found.shouldNotBeNull()
            found.apiModelName shouldBe "claude-sonnet-4-5"
        }

        "findModelConfig prefers alias over apiName when both could match" {
            // alias match wins even if the apiName match has higher priority
            val aiProviderService3 = mockk<AiProviderService>()
            every { aiProviderService3.getById(any()) } answers { stubAiProvider(firstArg()) }
            val svc = AiModelServiceImpl(InMemoryAiModelRepository(), aiProviderService3)
            val providerA = UUID.randomUUID()
            val providerB = UUID.randomUUID()
            svc.create(modelConfig(aiProviderId = providerA, apiName = "claude-haiku-4-5", alias = "sonnet", priority = 0))
            svc.create(modelConfig(aiProviderId = providerB, apiName = "sonnet", alias = null, priority = 100))

            val found = svc.findAiModel(namespaceId, "sonnet")
            found.shouldNotBeNull()
            found.alias shouldBe "sonnet"
            found.apiModelName shouldBe "claude-haiku-4-5"
        }

        "findModelConfig apiName fallback is case-insensitive" {
            val (service, aiProviderId) = newService()
            service.create(modelConfig(aiProviderId = aiProviderId, apiName = "Claude-Sonnet-4-5", alias = null))

            service.findAiModel(namespaceId, "claude-sonnet-4-5").shouldNotBeNull()
        }

        "findModelConfig returns null when neither alias nor apiName matches" {
            val (service, aiProviderId) = newService()
            service.create(modelConfig(aiProviderId = aiProviderId, apiName = "claude-haiku-4-5", alias = "small"))

            service.findAiModel(namespaceId, "unknown").shouldBeNull()
        }

        // -------------------------------------------------------------------------
        // findModelConfig — platform-level fallback (namespaceId=null on the model)
        //
        // Platform models are children of platform-level AiProviders (namespaceId=null,
        // userId=null). They must surface in findAiModel for any namespace when no
        // namespace-scoped model matches the requested alias/apiName.
        // -------------------------------------------------------------------------

        "findModelConfig returns a platform-level model when no namespace model matches" {
            val platformProviderService = mockk<AiProviderService>()
            val platformProviderId = UUID.randomUUID()
            every { platformProviderService.getById(platformProviderId) } answers {
                AiProvider(
                    metadata = EntityMetadata(id = platformProviderId),
                    namespaceId = null, userId = null,
                    name = "anthropic-platform", apiType = AiApiType.Anthropic,
                )
            }
            val service = AiModelServiceImpl(InMemoryAiModelRepository(), platformProviderService)
            service.create(
                AiModel(
                    metadata = EntityMetadata(),
                    aiProviderId = platformProviderId,
                    namespaceId = null,
                    apiModelName = "claude-sonnet-4-5",
                    alias = "default",
                    priority = 0,
                ),
            )

            val found = service.findAiModel(UUID.randomUUID(), "default")
            found.shouldNotBeNull()
            found.alias shouldBe "default"
            found.namespaceId shouldBe null
        }

        "findModelConfig namespace-scoped model wins over platform model at equal priority" {
            val mixedProviderService = mockk<AiProviderService>()
            val platformProviderId = UUID.randomUUID()
            val nsProviderId = UUID.randomUUID()
            val targetNs = UUID.randomUUID()
            every { mixedProviderService.getById(platformProviderId) } answers {
                AiProvider(
                    metadata = EntityMetadata(id = platformProviderId),
                    namespaceId = null, userId = null,
                    name = "anthropic-platform", apiType = AiApiType.Anthropic,
                )
            }
            every { mixedProviderService.getById(nsProviderId) } answers {
                AiProvider(
                    metadata = EntityMetadata(id = nsProviderId),
                    namespaceId = targetNs, userId = null,
                    name = "anthropic-ns", apiType = AiApiType.Anthropic,
                )
            }
            val service = AiModelServiceImpl(InMemoryAiModelRepository(), mixedProviderService)
            service.create(
                AiModel(
                    metadata = EntityMetadata(), aiProviderId = platformProviderId,
                    namespaceId = null, apiModelName = "claude-haiku-4-5",
                    alias = "default", priority = 0,
                ),
            )
            service.create(
                AiModel(
                    metadata = EntityMetadata(), aiProviderId = nsProviderId,
                    namespaceId = targetNs, apiModelName = "claude-sonnet-4-5",
                    alias = "default", priority = 0,
                ),
            )

            val found = service.findAiModel(targetNs, "default")
            found.shouldNotBeNull()
            found.apiModelName shouldBe "claude-sonnet-4-5"
        }

        "findModelConfig namespace-scoped model wins over platform model even when platform has higher priority" {
            // Scope always wins over priority: a more-specific namespace model at priority=0
            // must beat a platform model at priority=100. Priority only competes within the
            // same scope level.
            val mixedProviderService = mockk<AiProviderService>()
            val platformProviderId = UUID.randomUUID()
            val nsProviderId = UUID.randomUUID()
            val targetNs = UUID.randomUUID()
            every { mixedProviderService.getById(platformProviderId) } answers {
                AiProvider(
                    metadata = EntityMetadata(id = platformProviderId),
                    namespaceId = null, userId = null,
                    name = "anthropic-platform", apiType = AiApiType.Anthropic,
                )
            }
            every { mixedProviderService.getById(nsProviderId) } answers {
                AiProvider(
                    metadata = EntityMetadata(id = nsProviderId),
                    namespaceId = targetNs, userId = null,
                    name = "anthropic-ns", apiType = AiApiType.Anthropic,
                )
            }
            val service = AiModelServiceImpl(InMemoryAiModelRepository(), mixedProviderService)
            service.create(
                AiModel(
                    metadata = EntityMetadata(), aiProviderId = platformProviderId,
                    namespaceId = null, apiModelName = "claude-opus-4-6",
                    alias = "default", priority = 100,
                ),
            )
            service.create(
                AiModel(
                    metadata = EntityMetadata(), aiProviderId = nsProviderId,
                    namespaceId = targetNs, apiModelName = "claude-haiku-4-5",
                    alias = "default", priority = 0,
                ),
            )

            val found = service.findAiModel(targetNs, "default")
            found.shouldNotBeNull()
            found.apiModelName shouldBe "claude-haiku-4-5"
        }

        "findModelConfig priority breaks ties between platform-level models from different providers" {
            // Two platform-level providers can each expose the same alias — priority decides which wins.
            val platformProviderService = mockk<AiProviderService>()
            val platformProviderA = UUID.randomUUID()
            val platformProviderB = UUID.randomUUID()
            every { platformProviderService.getById(platformProviderA) } answers {
                AiProvider(
                    metadata = EntityMetadata(id = platformProviderA),
                    namespaceId = null, userId = null,
                    name = "openai-platform", apiType = AiApiType.Anthropic,
                )
            }
            every { platformProviderService.getById(platformProviderB) } answers {
                AiProvider(
                    metadata = EntityMetadata(id = platformProviderB),
                    namespaceId = null, userId = null,
                    name = "anthropic-platform", apiType = AiApiType.Anthropic,
                )
            }
            val service = AiModelServiceImpl(InMemoryAiModelRepository(), platformProviderService)
            service.create(
                AiModel(
                    metadata = EntityMetadata(), aiProviderId = platformProviderA,
                    namespaceId = null, apiModelName = "gpt-4o",
                    alias = "default", priority = 0,
                ),
            )
            service.create(
                AiModel(
                    metadata = EntityMetadata(), aiProviderId = platformProviderB,
                    namespaceId = null, apiModelName = "claude-sonnet-4-5",
                    alias = "default", priority = 10,
                ),
            )

            val found = service.findAiModel(UUID.randomUUID(), "default")
            found.shouldNotBeNull()
            found.apiModelName shouldBe "claude-sonnet-4-5"
        }

        "findModelConfig returns null when neither namespace nor platform has a matching alias" {
            val platformProviderService = mockk<AiProviderService>()
            val platformProviderId = UUID.randomUUID()
            every { platformProviderService.getById(platformProviderId) } answers {
                AiProvider(
                    metadata = EntityMetadata(id = platformProviderId),
                    namespaceId = null, userId = null,
                    name = "anthropic-platform", apiType = AiApiType.Anthropic,
                )
            }
            val service = AiModelServiceImpl(InMemoryAiModelRepository(), platformProviderService)
            service.create(
                AiModel(
                    metadata = EntityMetadata(), aiProviderId = platformProviderId,
                    namespaceId = null, apiModelName = "claude-sonnet-4-5",
                    alias = "platform-only", priority = 0,
                ),
            )

            service.findAiModel(UUID.randomUUID(), "default").shouldBeNull()
        }

        // -------------------------------------------------------------------------
        // Delete
        // -------------------------------------------------------------------------

        "deleteByParent removes all model configs for a provider" {
            val (service, aiProviderId) = newService()

            service.create(modelConfig(aiProviderId = aiProviderId, apiName = "claude-haiku-4-5"))
            service.create(modelConfig(aiProviderId = aiProviderId, apiName = "claude-opus-4-6"))

            service.deleteByParent(aiProviderId) shouldBe 2
            service.findByParent(aiProviderId).shouldBeEmpty()
        }

        // -------------------------------------------------------------------------
        // Update
        // -------------------------------------------------------------------------

        "update replaces the model config" {
            val (service, aiProviderId) = newService()
            val original = service.create(modelConfig(aiProviderId = aiProviderId, apiName = "claude-haiku-4-5"))

            val updated = service.update(original.copy(alias = "SMALL", temperature = 0.5))

            updated.alias shouldBe "SMALL"
            updated.temperature shouldBe 0.5
            service.findById(original.metadata.id)?.alias shouldBe "SMALL"
        }

        "update does not conflict with itself when changing inference parameters" {
            val (service, aiProviderId) = newService()
            val original = service.create(modelConfig(aiProviderId = aiProviderId, apiName = "claude-haiku-4-5"))

            // updating temperature on the same entity must not trigger a self-conflict on alias
            val updated = service.update(original.copy(temperature = 0.7))
            updated.temperature shouldBe 0.7
        }

        "update does not conflict with itself on alias" {
            val (service, aiProviderId) = newService()
            val original = service.create(modelConfig(aiProviderId = aiProviderId, apiName = "claude-haiku-4-5", alias = "small"))

            val updated = service.update(original.copy(temperature = 0.7))
            updated.alias shouldBe "small"
        }

        "update allows changing apiName to one already used by a sibling" {
            // apiName uniqueness is no longer enforced — only alias uniqueness is.
            val (service, aiProviderId) = newService()
            service.create(modelConfig(aiProviderId = aiProviderId, apiName = "claude-opus-4-6", alias = "big"))
            val toUpdate = service.create(modelConfig(aiProviderId = aiProviderId, apiName = "claude-haiku-4-5", alias = "small"))

            val updated = service.update(toUpdate.copy(apiModelName = "claude-opus-4-6"))
            updated.apiModelName shouldBe "claude-opus-4-6"
        }

        "update throws 409 when new alias conflicts with a sibling" {
            val (service, aiProviderId) = newService()
            service.create(modelConfig(aiProviderId = aiProviderId, apiName = "claude-opus-4-6", alias = "big"))
            val toUpdate = service.create(modelConfig(aiProviderId = aiProviderId, apiName = "claude-haiku-4-5", alias = "small"))

            shouldThrow<ResponseStatusException> {
                service.update(toUpdate.copy(alias = "big"))
            }.statusCode.value() shouldBe 409
        }
    }
}
