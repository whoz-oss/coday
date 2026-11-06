package io.whozoss.agentos.chat

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.ModelPricing
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.usage.LlmUsage
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.metadata.ChatResponseMetadata
import org.springframework.ai.chat.metadata.DefaultUsage
import org.springframework.ai.chat.metadata.Usage
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import java.util.UUID

/** Provider prompt-cache counts, as carried by Spring AI's [DefaultUsage]. */
private data class Cache(
    val read: Long? = null,
    val write: Long? = null,
)

/** Anthropic: `input_tokens` excludes the cache; reads and writes are reported separately. */
private fun anthropicCache(
    read: Long = 0,
    write: Long = 0,
) = Cache(read = read, write = write)

/** OpenAI: `prompt_tokens` includes the cached tokens; there is no cache-write count. */
private fun openAiCache(cached: Long = 0) = Cache(read = cached.takeIf { it > 0 })

/**
 * Wraps the counts in the Spring AI [Usage] + [ChatResponse] structure that [CostCalculator.extract]
 * expects. A `null` [cache] means "no provider usage" (no native usage object).
 */
private fun chatResponse(
    promptTokens: Int,
    completionTokens: Int,
    cache: Cache? = null,
): ChatResponse =
    chatResponse(
        DefaultUsage(
            promptTokens,
            completionTokens,
            promptTokens + completionTokens,
            cache?.let {
                Any()
            },
            cache?.read,
            cache?.write,
        ),
    )

private fun chatResponse(usage: Usage?): ChatResponse {
    val metadata = ChatResponseMetadata.builder().apply { usage?.let { usage(it) } }.build()
    return ChatResponse(listOf(Generation(AssistantMessage(""))), metadata)
}

private fun modelWithPricing(
    inputRate: Double? = null,
    outputRate: Double? = null,
    cacheReadRate: Double? = null,
    cacheWriteRate: Double? = null,
): AiModel {
    val pricing =
        if (inputRate != null || outputRate != null || cacheReadRate != null || cacheWriteRate != null) {
            ModelPricing(
                inputMTokens = inputRate,
                outputMTokens = outputRate,
                cacheRead = cacheReadRate,
                cacheWrite = cacheWriteRate,
            )
        } else {
            null
        }
    return AiModel(
        metadata = EntityMetadata(id = UUID.randomUUID()),
        aiProviderId = UUID.randomUUID(),
        apiModelName = "test-model",
        pricing = pricing,
    )
}

/**
 * Unit tests for [CostCalculator].
 *
 * Usages are built as Spring AI [DefaultUsage] instances carrying the prompt-cache counts,
 * exactly as `AnthropicChatModel` and `OpenAiChatModel` (Spring AI 2.x) emit them.
 *
 * An anti-fallback assertion is included for each provider: cache-token values are
 * asserted to be non-zero when the usage carries them.
 *
 * Key invariants verified:
 * - Anthropic: inputTokens already excludes cache — no subtraction.
 * - OpenAI: inputTokens = max(0, promptTokens - cachedTokens) — no double-counting.
 * - No pricing config: estimatedCostUsd is null (not zero).
 * - Absent usage metadata: returns LlmUsage.ZERO.
 */
class CostCalculatorUnitSpec :
    StringSpec({

        "cache-only Anthropic metadata is retained when generic counters are zero" {
            val response = chatResponse(promptTokens = 0, completionTokens = 0, cache = anthropicCache(read = 100))
            val usage = CostCalculator.extract(response, AiApiType.Anthropic, modelWithPricing())
            usage.cacheReadTokens shouldBe 100L
        }

        // -------------------------------------------------------------------------
        // Anti-fallback guards
        // -------------------------------------------------------------------------

        "Anthropic anti-fallback: cache tokens are non-zero when the usage carries them" {
            val response = chatResponse(promptTokens = 800, completionTokens = 150, cache = anthropicCache(read = 200, write = 100))

            val usage = CostCalculator.extract(response, AiApiType.Anthropic, modelWithPricing(inputRate = 3.0))

            usage.cacheReadTokens shouldBe 200L
            usage.cacheWriteTokens shouldBe 100L
        }

        "OpenAI anti-fallback: inputTokens is subtracted and cacheReadTokens is non-zero" {
            val response = chatResponse(promptTokens = 1000, completionTokens = 200, cache = openAiCache(cached = 300))

            val usage = CostCalculator.extract(response, AiApiType.OpenAI, modelWithPricing(inputRate = 2.5))

            usage.inputTokens shouldBe 700L // 1000 - 300
            usage.cacheReadTokens shouldBe 300L
        }

        // -------------------------------------------------------------------------
        // Anthropic
        // -------------------------------------------------------------------------

        "Anthropic: inputTokens excludes cache — no subtraction applied" {
            val response = chatResponse(promptTokens = 800, completionTokens = 150, cache = anthropicCache(read = 200, write = 100))
            val model = modelWithPricing(inputRate = 3.0, outputRate = 15.0, cacheReadRate = 0.3, cacheWriteRate = 3.75)

            val usage = CostCalculator.extract(response, AiApiType.Anthropic, model)

            usage.inputTokens shouldBe 800L // Anthropic already excludes cache
            usage.outputTokens shouldBe 150L
            usage.cacheReadTokens shouldBe 200L
            usage.cacheWriteTokens shouldBe 100L
            usage.totalTokens shouldBe 950L
        }

        "Anthropic with cache read and write: cost is correct" {
            val response = chatResponse(promptTokens = 800, completionTokens = 150, cache = anthropicCache(read = 200, write = 100))
            val model = modelWithPricing(inputRate = 3.0, outputRate = 15.0, cacheReadRate = 0.3, cacheWriteRate = 3.75)

            val usage = CostCalculator.extract(response, AiApiType.Anthropic, model)

            val expectedCost =
                (800 / 1_000_000.0) * 3.0 +
                    (150 / 1_000_000.0) * 15.0 +
                    (200 / 1_000_000.0) * 0.3 +
                    (100 / 1_000_000.0) * 3.75
            usage.estimatedCostUsd.shouldNotBeNull() shouldBe (expectedCost plusOrMinus 1e-10)
        }

        "Anthropic without cache: cache tokens are zero" {
            val response = chatResponse(promptTokens = 500, completionTokens = 100, cache = anthropicCache())

            val usage = CostCalculator.extract(response, AiApiType.Anthropic, modelWithPricing(inputRate = 3.0, outputRate = 15.0))

            usage.cacheReadTokens shouldBe 0L
            usage.cacheWriteTokens shouldBe 0L
        }

        // -------------------------------------------------------------------------
        // OpenAI
        // -------------------------------------------------------------------------

        "OpenAI: inputTokens is promptTokens minus cachedTokens (no double-counting)" {
            val response = chatResponse(promptTokens = 1000, completionTokens = 200, cache = openAiCache(cached = 300))
            val model = modelWithPricing(inputRate = 2.5, outputRate = 10.0, cacheReadRate = 1.25)

            val usage = CostCalculator.extract(response, AiApiType.OpenAI, model)

            usage.inputTokens shouldBe 700L // 1000 - 300
            usage.cacheReadTokens shouldBe 300L
            usage.cacheWriteTokens shouldBe 0L // OpenAI has no cache-write count
            usage.outputTokens shouldBe 200L
        }

        "OpenAI with cached tokens: cost does not double-count cached tokens" {
            val response = chatResponse(promptTokens = 1000, completionTokens = 200, cache = openAiCache(cached = 300))
            val model = modelWithPricing(inputRate = 2.5, outputRate = 10.0, cacheReadRate = 1.25)

            val usage = CostCalculator.extract(response, AiApiType.OpenAI, model)

            // Normalised: 700 input + 300 cache read (not 1000 input + 300 cache read)
            val expectedCost =
                (700 / 1_000_000.0) * 2.5 +
                    (200 / 1_000_000.0) * 10.0 +
                    (300 / 1_000_000.0) * 1.25
            val doubleCounted =
                (1000 / 1_000_000.0) * 2.5 +
                    (200 / 1_000_000.0) * 10.0 +
                    (300 / 1_000_000.0) * 1.25

            usage.estimatedCostUsd.shouldNotBeNull() shouldBe (expectedCost plusOrMinus 1e-10)
            (usage.estimatedCostUsd!! - doubleCounted) shouldBe ((-300 / 1_000_000.0) * 2.5 plusOrMinus 1e-10)
        }

        "OpenAI without cached tokens: inputTokens equals promptTokens" {
            val response = chatResponse(promptTokens = 500, completionTokens = 100, cache = openAiCache())

            val usage = CostCalculator.extract(response, AiApiType.OpenAI, modelWithPricing(inputRate = 2.5, outputRate = 10.0))

            usage.inputTokens shouldBe 500L
            usage.cacheReadTokens shouldBe 0L
        }

        "OpenAI: max(0, promptTokens - cachedTokens) guards against negative inputTokens" {
            val response = chatResponse(promptTokens = 100, completionTokens = 50, cache = openAiCache(cached = 200))

            val usage = CostCalculator.extract(response, AiApiType.OpenAI, modelWithPricing(inputRate = 2.5))

            usage.inputTokens shouldBe 0L // floored at 0, not -100
        }

        "vLLM follows the OpenAI convention" {
            val response = chatResponse(promptTokens = 1000, completionTokens = 200, cache = openAiCache(cached = 300))

            val usage = CostCalculator.extract(response, AiApiType.vLLM, modelWithPricing(inputRate = 2.5))

            usage.inputTokens shouldBe 700L
            usage.cacheReadTokens shouldBe 300L
        }

        // -------------------------------------------------------------------------
        // No pricing config
        // -------------------------------------------------------------------------

        "model without any pricing: estimatedCostUsd is null" {
            val response = chatResponse(promptTokens = 500, completionTokens = 100, cache = anthropicCache())

            val usage = CostCalculator.extract(response, AiApiType.Anthropic, modelWithPricing())

            usage.estimatedCostUsd.shouldBeNull()
            usage.inputTokens shouldBe 500L
            usage.outputTokens shouldBe 100L
        }

        "model without pricing: estimatedCostUsd is null not 0.0 — null means pricing unknown" {
            val response = chatResponse(promptTokens = 300, completionTokens = 60, cache = openAiCache())

            val usage = CostCalculator.extract(response, AiApiType.OpenAI, modelWithPricing())

            usage.estimatedCostUsd shouldBe null
        }

        // -------------------------------------------------------------------------
        // Absent / empty usage metadata
        // -------------------------------------------------------------------------

        "response with no usage metadata returns LlmUsage.ZERO" {
            val usage = CostCalculator.extract(chatResponse(usage = null), AiApiType.Anthropic, modelWithPricing())

            usage shouldBe LlmUsage.ZERO
        }

        "response with all-zero token counts and no provider usage returns LlmUsage.ZERO" {
            val result = CostCalculator.extract(chatResponse(DefaultUsage(0, 0, 0)), AiApiType.Anthropic, modelWithPricing())

            result shouldBe LlmUsage.ZERO
        }

        "usage without a provider usage object is unknown cost, not a zero-input estimate" {
            val response = chatResponse(promptTokens = 500, completionTokens = 100, cache = null)

            val usage = CostCalculator.extract(response, AiApiType.OpenAI, modelWithPricing(inputRate = 2.5, outputRate = 10.0))

            usage.estimatedCostUsd shouldBe null
            usage.outputTokens shouldBe 100L
        }

        // -------------------------------------------------------------------------
        // Unsupported providers (Gemini, Ollama) — cost must be null even with pricing
        // -------------------------------------------------------------------------

        "Gemini with pricing configured: estimatedCostUsd is null (input tokens not normalisable)" {
            // A partial cost (input = 0) would be a silent undercount, so null is returned instead.
            val response = chatResponse(promptTokens = 500, completionTokens = 200, cache = Cache())

            val usage = CostCalculator.extract(response, AiApiType.Gemini, modelWithPricing(inputRate = 0.075, outputRate = 0.30))

            usage.estimatedCostUsd shouldBe null
            // Token counts available from generic Usage fields are still recorded
            usage.outputTokens shouldBe 200L
            usage.totalTokens shouldBe 700L
        }

        "Ollama with pricing configured: estimatedCostUsd is null (input tokens not normalisable)" {
            val response = chatResponse(promptTokens = 300, completionTokens = 100, cache = Cache())

            val usage = CostCalculator.extract(response, AiApiType.Ollama, modelWithPricing(inputRate = 0.1, outputRate = 0.2))

            usage.estimatedCostUsd shouldBe null
            usage.outputTokens shouldBe 100L
            usage.totalTokens shouldBe 400L
        }

        "Gemini without pricing: estimatedCostUsd is null" {
            val response = chatResponse(promptTokens = 100, completionTokens = 50)

            val usage = CostCalculator.extract(response, AiApiType.Gemini, modelWithPricing())

            usage.estimatedCostUsd shouldBe null
        }

        // -------------------------------------------------------------------------
        // LlmUsage.plus accumulation
        // -------------------------------------------------------------------------

        "LlmUsage.plus sums all token counts when both have known cost" {
            val a =
                LlmUsage(
                    inputTokens = 100,
                    outputTokens = 50,
                    cacheReadTokens = 10,
                    cacheWriteTokens = 5,
                    totalTokens = 165,
                    estimatedCostUsd = 0.001,
                )
            val b =
                LlmUsage(
                    inputTokens = 200,
                    outputTokens = 80,
                    cacheReadTokens = 20,
                    cacheWriteTokens = 0,
                    totalTokens = 300,
                    estimatedCostUsd = 0.002,
                )

            val sum = a + b

            sum.inputTokens shouldBe 300L
            sum.outputTokens shouldBe 130L
            sum.cacheReadTokens shouldBe 30L
            sum.cacheWriteTokens shouldBe 5L
            sum.totalTokens shouldBe 465L
            sum.estimatedCostUsd.shouldNotBeNull() shouldBe (0.003 plusOrMinus 1e-10)
        }

        "LlmUsage.plus: null cost on either operand propagates null" {
            // null means "pricing not configured" — the aggregate cost is then
            // an undercount and must not be reported as a known value.
            val withCost = LlmUsage(inputTokens = 100, estimatedCostUsd = 0.001)
            val withoutCost = LlmUsage(inputTokens = 200, estimatedCostUsd = null)

            (withCost + withoutCost).estimatedCostUsd shouldBe null
            (withoutCost + withCost).estimatedCostUsd shouldBe null
        }
    })
