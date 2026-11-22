package io.whozoss.agentos.chat

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

/** How [PromptCacheStatusService] derives the warm / cold status of a case from its last request and its model. */
class PromptCacheStatusServiceUnitSpec :
    StringSpec({
        val now: Instant = Instant.parse("2026-01-01T10:00:00Z")
        val sentAt: Instant = now.minusSeconds(30)
        val requesty = "https://router.requesty.ai/v1"
        val claudePricing = ModelPricing(inputMTokens = 3.0, outputMTokens = 15.0, cacheRead = 0.3, cacheWrite = 3.75)

        class Fixture(
            pricing: ModelPricing? = claudePricing,
            cacheTtlMinutes: Int? = null,
            extendedCacheTtlMinutes: Int? = null,
            modelGone: Boolean = false,
        ) {
            val clock = MovingClock(now)
            val registry = LastLlmRequestRegistry(clock)
            val model =
                AiModel(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    aiProviderId = UUID.randomUUID(),
                    apiModelName = "m",
                    pricing = pricing,
                    cacheTtlMinutes = cacheTtlMinutes,
                    extendedCacheTtlMinutes = extendedCacheTtlMinutes,
                )
            val aiModelService = mockk<AiModelService> { every { findById(model.id) } returns model.takeUnless { modelGone } }
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
                )
            val caseId: UUID = UUID.randomUUID()

            /** Records the case's last request: [apiModelName] via Requesty, sent at [sentAt], of [prefixTokens]. */
            fun lastRequest(
                apiModelName: String = "anthropic/claude-sonnet-4-5",
                ttl: PromptCacheTtl = PromptCacheTtl.STANDARD,
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
                        requestOptions = LlmRequestOptions(AiApiType.OpenAI, requesty, apiModelName, null, null, null, null, "digest"),
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

        "without a lifetime on the model: 5 minutes, re-written at the cache write rate" {
            val status = Fixture().apply { lastRequest() }.status()

            status.expiresAt shouldBe sentAt.plus(Duration.ofMinutes(5))
            status.coldCostEstimate.shouldNotBeNull() shouldBe (0.375 plusOrMinus 1e-9)
        }

        "the standard lifetime of the model, whatever the model name" {
            val status = Fixture(cacheTtlMinutes = 30).apply { lastRequest(apiModelName = "google/gemini-2.5-pro") }.status()

            status.expiresAt shouldBe sentAt.plus(Duration.ofMinutes(30))
        }

        "with the one-hour markers: the extended lifetime of the model, else 60 minutes, re-written at twice the input rate" {
            Fixture(cacheTtlMinutes = 5, extendedCacheTtlMinutes = 90).apply { lastRequest(ttl = PromptCacheTtl.EXTENDED) }.status().let {
                it.expiresAt shouldBe sentAt.plus(Duration.ofMinutes(90))
                it.coldCostEstimate.shouldNotBeNull() shouldBe (0.6 plusOrMinus 1e-9)
            }
            Fixture(cacheTtlMinutes = 5).apply { lastRequest(ttl = PromptCacheTtl.EXTENDED) }.status().let {
                it.expiresAt shouldBe sentAt.plus(Duration.ofHours(1))
            }
        }

        "the extended choice without the markers (a model they never reach) keeps the standard lifetime" {
            val fixture = Fixture(cacheTtlMinutes = 30, extendedCacheTtlMinutes = 60).apply { lastRequest(apiModelName = "openai/gpt-6", ttl = PromptCacheTtl.EXTENDED) }

            val status = fixture.status()

            status.expiresAt shouldBe sentAt.plus(Duration.ofMinutes(30))
            status.coldCostEstimate.shouldNotBeNull() shouldBe (0.375 plusOrMinus 1e-9)
        }

        "without a cache write rate, the input rate prices the re-write" {
            val fixture = Fixture(pricing = ModelPricing(inputMTokens = 1.25, cacheRead = 0.125)).apply { lastRequest() }

            fixture.status().coldCostEstimate.shouldNotBeNull() shouldBe (0.125 plusOrMinus 1e-9)
        }

        "without pricing, a known prompt size or the model itself, the lifetime is still given but not the cost" {
            for (fixture in listOf(Fixture(pricing = null).apply { lastRequest() }, Fixture().apply { lastRequest(prefixTokens = null) })) {
                fixture.status().expiresAt shouldBe sentAt.plus(Duration.ofMinutes(5))
                fixture.status().coldCostEstimate.shouldBeNull()
            }
            Fixture(cacheTtlMinutes = 30, modelGone = true).apply { lastRequest() }.status().let {
                it.expiresAt shouldBe sentAt.plus(Duration.ofMinutes(5))
                it.coldCostEstimate.shouldBeNull()
            }
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
