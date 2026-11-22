package io.whozoss.agentos.chat

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.micrometer.observation.ObservationRegistry
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.agent.LastLlmRequest
import io.whozoss.agentos.agent.LastLlmRequestRegistry
import io.whozoss.agentos.agent.MovingClock
import io.whozoss.agentos.aiModel.AiModelService
import io.whozoss.agentos.config.UsageConfigProperties
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.ModelPricing
import io.whozoss.agentos.sdk.api.user.PromptCacheTtl
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.ai.chat.messages.UserMessage
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** How [PromptCacheStatusService] derives the warm / cold status of a case from its last request. */
class PromptCacheStatusServiceUnitSpec :
    StringSpec({
        val now: Instant = Instant.parse("2026-01-01T10:00:00Z")
        val sentAt: Instant = now.minusSeconds(30)
        val requesty = "https://router.requesty.ai/v1"
        val anthropic = "https://api.anthropic.com"
        val claudePricing = ModelPricing(inputMTokens = 3.0, outputMTokens = 15.0, cacheRead = 0.3, cacheWrite = 3.75)

        class Fixture(
            pricing: ModelPricing? = claudePricing,
            anthropicCaching: Boolean = true,
        ) {
            val clock = MovingClock(now)
            val registry = LastLlmRequestRegistry(clock)
            val model = AiModel(metadata = EntityMetadata(id = UUID.randomUUID()), aiProviderId = UUID.randomUUID(), apiModelName = "m", pricing = pricing)
            val aiModelService = mockk<AiModelService> { every { findById(model.id) } returns model }
            val service =
                PromptCacheStatusService(
                    registry = registry,
                    aiModelService = aiModelService,
                    chatModelFactory =
                        ChatModelFactory(
                            ObservationRegistry.NOOP,
                            AnthropicProperties(promptCachingEnabled = true),
                            UsageConfigProperties(enabled = true),
                            RequestyPromptCacheProperties(),
                        ),
                    anthropicProperties = AnthropicProperties(promptCachingEnabled = anthropicCaching),
                    requestyPromptCache = RequestyPromptCacheProperties(),
                )
            val caseId: UUID = UUID.randomUUID()

            /** Records the case's last request: [apiModelName] via [baseUrl], sent at [sentAt], of [prefixTokens]. */
            fun lastRequest(
                apiModelName: String = "anthropic/claude-sonnet-4-5",
                ttl: PromptCacheTtl = PromptCacheTtl.STANDARD,
                apiType: AiApiType = AiApiType.OpenAI,
                baseUrl: String = requesty,
                prefixTokens: Long? = 100_000,
            ) {
                registry.put(
                    LastLlmRequest(
                        caseId = caseId,
                        namespaceId = UUID.randomUUID(),
                        userId = UUID.randomUUID(),
                        messages = listOf(UserMessage("question")),
                        toolDefinitions = emptyList(),
                        modelId = model.id,
                        providerId = model.aiProviderId,
                        requestOptions = LlmRequestOptions(apiType, baseUrl, apiModelName, null, null, null, null, "digest"),
                        finalText = "Done",
                        agentName = "planner",
                        agentConfigId = null,
                        coveredEventCount = 2,
                        lastCoveredEventId = UUID.randomUUID(),
                        promptCacheTtl = ttl,
                        requestSentAt = sentAt,
                        prefixTokens = prefixTokens,
                    ),
                    registry.invalidate(caseId),
                )
            }

            fun status() = service.statusOf(caseId)
        }

        "no last request for the case: nothing is known" {
            Fixture().status() shouldBe PromptCacheStatus(expiresAt = null, coldCostEstimate = null)
        }

        "Claude via Requesty with the standard choice: 5 minutes, re-written at the cache write rate" {
            val fixture = Fixture().apply { lastRequest() }

            val status = fixture.status()

            status.expiresAt shouldBe sentAt.plus(Duration.ofMinutes(5))
            status.coldCostEstimate.shouldNotBeNull() shouldBe (0.375 plusOrMinus 1e-9)
        }

        "Claude via Requesty with the extended choice: 1 hour, re-written at twice the input rate" {
            val fixture = Fixture().apply { lastRequest(ttl = PromptCacheTtl.EXTENDED) }

            val status = fixture.status()

            status.expiresAt shouldBe sentAt.plus(Duration.ofHours(1))
            status.coldCostEstimate.shouldNotBeNull() shouldBe (0.6 plusOrMinus 1e-9)
        }

        "Claude through the Anthropic API with prompt caching on: 5 minutes, the extended choice included" {
            val fixture =
                Fixture().apply {
                    lastRequest(apiModelName = "claude-sonnet-4-5", ttl = PromptCacheTtl.EXTENDED, apiType = AiApiType.Anthropic, baseUrl = anthropic)
                }

            val status = fixture.status()

            status.expiresAt shouldBe sentAt.plus(Duration.ofMinutes(5))
            status.coldCostEstimate.shouldNotBeNull() shouldBe (0.375 plusOrMinus 1e-9)
        }

        "Claude through the Anthropic API with prompt caching off: nothing is cached, nothing is known" {
            val fixture =
                Fixture(anthropicCaching = false).apply {
                    lastRequest(apiModelName = "claude-sonnet-4-5", apiType = AiApiType.Anthropic, baseUrl = anthropic)
                }

            fixture.status() shouldBe PromptCacheStatus(expiresAt = null, coldCostEstimate = null)
        }

        "Claude through another OpenAI-compatible gateway than Requesty: nothing is known" {
            val fixture = Fixture().apply { lastRequest(baseUrl = "https://llm-gateway.example.com/v1") }

            fixture.status() shouldBe PromptCacheStatus(expiresAt = null, coldCostEstimate = null)
        }

        "GPT-5.6 and later, or a Requesty policy named after GPT: 30 minutes, whatever the choice" {
            for (name in listOf("openai/gpt-5.6", "gpt-5.10", "openai/gpt-6.1-mini", "gpt-7", "policy/gpt-sol", "policy/gpt-terra")) {
                val fixture = Fixture().apply { lastRequest(apiModelName = name, ttl = PromptCacheTtl.EXTENDED) }

                withClue(name) { fixture.status().expiresAt shouldBe sentAt.plus(Duration.ofMinutes(30)) }
            }
        }

        "older GPT models and gpt-oss: nothing is known" {
            for (name in listOf("openai/gpt-4o", "gpt-4.1", "gpt-35-turbo", "gpt-5", "openai/gpt-5.5", "openai/gpt-oss-120b", "policy/gpt-oss")) {
                val fixture = Fixture().apply { lastRequest(apiModelName = name) }

                withClue(name) { fixture.status().expiresAt.shouldBeNull() }
            }
        }

        "without a cache write rate, the input rate prices the re-write" {
            val fixture = Fixture(pricing = ModelPricing(inputMTokens = 1.25, cacheRead = 0.125)).apply { lastRequest(apiModelName = "openai/gpt-6") }

            fixture.status().coldCostEstimate.shouldNotBeNull() shouldBe (0.125 plusOrMinus 1e-9)
        }

        "without pricing or a known prompt size, the lifetime is still given but not the cost" {
            Fixture(pricing = null).apply { lastRequest() }.status().let {
                it.expiresAt shouldBe sentAt.plus(Duration.ofMinutes(5))
                it.coldCostEstimate.shouldBeNull()
            }
            Fixture().apply { lastRequest(prefixTokens = null) }.status().let {
                it.expiresAt shouldBe sentAt.plus(Duration.ofMinutes(5))
                it.coldCostEstimate.shouldBeNull()
            }
        }

        "a model family with an unknown cache lifetime: nothing is known" {
            val fixture = Fixture().apply { lastRequest(apiModelName = "google/gemini-2.5-pro") }

            fixture.status() shouldBe PromptCacheStatus(expiresAt = null, coldCostEstimate = null)
        }

        "once the request itself has expired, its stamp still tells the cache is cold" {
            val fixture = Fixture().apply { lastRequest() }
            fixture.clock.now = now.plus(Duration.ofHours(3))
            fixture.registry.purgeExpired()
            fixture.registry.get(fixture.caseId).shouldBeNull()

            val status = fixture.status()

            status.expiresAt shouldBe sentAt.plus(Duration.ofMinutes(5))
            status.coldCostEstimate.shouldNotBeNull() shouldBe (0.375 plusOrMinus 1e-9)
        }

        "a later refresh of the prefix (a keep-alive ping, a side question) restarts the lifetime" {
            val fixture = Fixture().apply { lastRequest() }
            val pingedAt = now.plusSeconds(200)
            fixture.registry.touch(fixture.caseId, fixture.registry.get(fixture.caseId)!!.capturedAt, pingedAt)

            fixture.status().expiresAt shouldBe pingedAt.plus(Duration.ofMinutes(5))
        }
    })
