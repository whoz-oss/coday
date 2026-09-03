package io.whozoss.agentos.chat

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.usage.LlmUsage
import org.springframework.ai.anthropic.api.AnthropicApi
import org.springframework.ai.chat.metadata.ChatResponseMetadata
import org.springframework.ai.chat.metadata.Usage
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.openai.api.OpenAiApi
import java.util.UUID

/**
 * Unit tests for [CostCalculator].
 *
 * Provider-native usage objects use the real [AnthropicApi.Usage] and [OpenAiApi.Usage]
 * classes (available on the test classpath as transitive dependencies of
 * spring-ai-starter-model-anthropic / spring-ai-starter-model-openai). This verifies
 * the actual method names and return types used by [CostCalculator] via reflection,
 * making the tests sensitive to any API change in the Spring AI provider JARs.
 *
 * An anti-fallback assertion is included for each provider: cache-token values are
 * asserted to be non-zero when the native object carries them. These assertions fail
 * if reflection silently falls back to the zero-triple.
 *
 * Key invariants verified:
 * - Anthropic: inputTokens already excludes cache — no subtraction.
 * - OpenAI: inputTokens = max(0, promptTokens - cachedTokens) — no double-counting.
 * - No pricing config: estimatedCostUsd is null (not zero).
 * - Absent usage metadata: returns LlmUsage.ZERO.
 */
class CostCalculatorUnitSpec : StringSpec({

    beforeEach { CostCalculator.resetWarnings() }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    fun modelWithPricing(
        inputRate: Double? = null,
        outputRate: Double? = null,
        cacheReadRate: Double? = null,
        cacheWriteRate: Double? = null,
    ): AiModel =
        AiModel(
            metadata = EntityMetadata(id = UUID.randomUUID()),
            aiProviderId = UUID.randomUUID(),
            apiModelName = "test-model",
            pricingInputMTokens = inputRate,
            pricingOutputMTokens = outputRate,
            pricingCacheRead = cacheReadRate,
            pricingCacheWrite = cacheWriteRate,
        )

    /**
     * Wraps a provider-native usage object in the Spring AI [Usage] + [ChatResponse]
     * structure that [CostCalculator.extract] expects.
     */
    fun makeChatResponse(
        promptTokens: Int,
        completionTokens: Int,
        totalTokens: Int = promptTokens + completionTokens,
        nativeUsage: Any? = null,
    ): ChatResponse {
        val usage =
            object : Usage {
                override fun getPromptTokens() = promptTokens
                override fun getCompletionTokens() = completionTokens
                override fun getTotalTokens() = totalTokens
                override fun getNativeUsage(): Any? = nativeUsage
            }
        val metadata = ChatResponseMetadata.builder().usage(usage).build()
        return ChatResponse(
            listOf(Generation(org.springframework.ai.chat.messages.AssistantMessage(""))),
            metadata,
        )
    }

    /**
     * Builds a real [AnthropicApi.Usage] instance.
     * Using the actual class verifies that [CostCalculator]'s reflection calls
     * match the true method names on the resolved JAR.
     */
    fun anthropicNative(
        inputTokens: Int,
        outputTokens: Int = 0,
        cacheCreationInputTokens: Int = 0,
        cacheReadInputTokens: Int = 0,
    ): AnthropicApi.Usage =
        AnthropicApi.Usage(
            inputTokens,
            outputTokens,
            cacheCreationInputTokens,
            cacheReadInputTokens,
        )

    /**
     * Builds a real [OpenAiApi.Usage] instance.
     * Using the actual class verifies that [CostCalculator]'s reflection calls
     * match the true method names on the resolved JAR.
     *
     * IMPORTANT — Java record component order (Kotlin does not support named args on Java records):
     *   OpenAiApi.Usage(completionTokens, promptTokens, totalTokens, promptTokensDetails, completionTokenDetails)
     *   OpenAiApi.Usage.PromptTokensDetails(audioTokens, cachedTokens)
     *
     * Verified from Spring AI source: models/spring-ai-openai/.../OpenAiApi.java, branch 1.1.x.
     */
    fun openAiNative(
        promptTokens: Int,
        completionTokens: Int = 0,
        cachedTokens: Int = 0,
    ): OpenAiApi.Usage {
        val details =
            if (cachedTokens > 0) {
                // PromptTokensDetails(audioTokens, cachedTokens) — audio first, cached second
                OpenAiApi.Usage.PromptTokensDetails(0, cachedTokens)
            } else {
                null
            }
        // Usage(completionTokens, promptTokens, totalTokens, promptTokensDetails, completionTokenDetails)
        return OpenAiApi.Usage(
            completionTokens,
            promptTokens,
            promptTokens + completionTokens,
            details,
            null,
        )
    }

    // -------------------------------------------------------------------------
    // Anti-fallback guards
    // -------------------------------------------------------------------------

    "Anthropic anti-fallback: cache tokens are non-zero when native object carries them" {
        // If reflection falls back to Triple(0,0,0), cacheReadTokens and cacheWriteTokens
        // would both be 0 even though the native object carries real values.
        // This test fails on fallback, making the silent-fallback problem visible.
        val native = anthropicNative(inputTokens = 800, cacheReadInputTokens = 200, cacheCreationInputTokens = 100)
        val response = makeChatResponse(promptTokens = 800, completionTokens = 150, nativeUsage = native)
        val model = modelWithPricing(inputRate = 3.0)

        val usage = CostCalculator.extract(response, AiApiType.Anthropic, model)

        usage.cacheReadTokens shouldBe 200L
        usage.cacheWriteTokens shouldBe 100L
    }

    "OpenAI anti-fallback: inputTokens is subtracted and cacheReadTokens is non-zero" {
        // If reflection falls back to Triple(0,0,0):
        //   - inputTokens would equal promptTokens (1000, no subtraction)
        //   - cacheReadTokens would be 0
        // Both assertions below fail on fallback.
        val native = openAiNative(promptTokens = 1000, completionTokens = 200, cachedTokens = 300)
        val response = makeChatResponse(promptTokens = 1000, completionTokens = 200, nativeUsage = native)
        val model = modelWithPricing(inputRate = 2.5)

        val usage = CostCalculator.extract(response, AiApiType.OpenAI, model)

        usage.inputTokens shouldBe 700L      // 1000 - 300: fails if fallback returns 1000
        usage.cacheReadTokens shouldBe 300L  // fails if fallback returns 0
    }

    // -------------------------------------------------------------------------
    // Anthropic
    // -------------------------------------------------------------------------

    "Anthropic: inputTokens excludes cache — no subtraction applied" {
        val native = anthropicNative(inputTokens = 800, cacheReadInputTokens = 200, cacheCreationInputTokens = 100)
        val response = makeChatResponse(promptTokens = 800, completionTokens = 150, nativeUsage = native)
        val model = modelWithPricing(inputRate = 3.0, outputRate = 15.0, cacheReadRate = 0.3, cacheWriteRate = 3.75)

        val usage = CostCalculator.extract(response, AiApiType.Anthropic, model)

        usage.inputTokens shouldBe 800L  // Anthropic already excludes cache
        usage.outputTokens shouldBe 150L
        usage.cacheReadTokens shouldBe 200L
        usage.cacheWriteTokens shouldBe 100L
        usage.totalTokens shouldBe 950L
    }

    "Anthropic with cache read and write: cost is correct" {
        val native = anthropicNative(inputTokens = 800, cacheReadInputTokens = 200, cacheCreationInputTokens = 100)
        val response = makeChatResponse(promptTokens = 800, completionTokens = 150, nativeUsage = native)
        val model =
            modelWithPricing(
                inputRate = 3.0,
                outputRate = 15.0,
                cacheReadRate = 0.3,
                cacheWriteRate = 3.75,
            )

        val usage = CostCalculator.extract(response, AiApiType.Anthropic, model)

        val expectedCost =
            (800 / 1_000_000.0) * 3.0 +
                (150 / 1_000_000.0) * 15.0 +
                (200 / 1_000_000.0) * 0.3 +
                (100 / 1_000_000.0) * 3.75
        usage.estimatedCostUsd.shouldNotBeNull() shouldBe (expectedCost plusOrMinus 1e-10)
    }

    "Anthropic without cache: cache tokens are zero" {
        val native = anthropicNative(inputTokens = 500)
        val response = makeChatResponse(promptTokens = 500, completionTokens = 100, nativeUsage = native)
        val model = modelWithPricing(inputRate = 3.0, outputRate = 15.0)

        val usage = CostCalculator.extract(response, AiApiType.Anthropic, model)

        usage.cacheReadTokens shouldBe 0L
        usage.cacheWriteTokens shouldBe 0L
    }

    // -------------------------------------------------------------------------
    // OpenAI
    // -------------------------------------------------------------------------

    "OpenAI: inputTokens is promptTokens minus cachedTokens (no double-counting)" {
        val native = openAiNative(promptTokens = 1000, completionTokens = 200, cachedTokens = 300)
        val response = makeChatResponse(promptTokens = 1000, completionTokens = 200, nativeUsage = native)
        val model = modelWithPricing(inputRate = 2.5, outputRate = 10.0, cacheReadRate = 1.25)

        val usage = CostCalculator.extract(response, AiApiType.OpenAI, model)

        usage.inputTokens shouldBe 700L      // 1000 - 300
        usage.cacheReadTokens shouldBe 300L
        usage.cacheWriteTokens shouldBe 0L   // OpenAI has no cache-write count
        usage.outputTokens shouldBe 200L
    }

    "OpenAI with cached tokens: cost does not double-count cached tokens" {
        val native = openAiNative(promptTokens = 1000, completionTokens = 200, cachedTokens = 300)
        val response = makeChatResponse(promptTokens = 1000, completionTokens = 200, nativeUsage = native)
        val model =
            modelWithPricing(
                inputRate = 2.5,
                outputRate = 10.0,
                cacheReadRate = 1.25,
            )

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
        val native = openAiNative(promptTokens = 500, completionTokens = 100)
        val response = makeChatResponse(promptTokens = 500, completionTokens = 100, nativeUsage = native)
        val model = modelWithPricing(inputRate = 2.5, outputRate = 10.0)

        val usage = CostCalculator.extract(response, AiApiType.OpenAI, model)

        usage.inputTokens shouldBe 500L
        usage.cacheReadTokens shouldBe 0L
    }

    "OpenAI: max(0, promptTokens - cachedTokens) guards against negative inputTokens" {
        val native = openAiNative(promptTokens = 100, completionTokens = 50, cachedTokens = 200)
        val response = makeChatResponse(promptTokens = 100, completionTokens = 50, nativeUsage = native)
        val model = modelWithPricing(inputRate = 2.5)

        val usage = CostCalculator.extract(response, AiApiType.OpenAI, model)

        usage.inputTokens shouldBe 0L  // floored at 0, not -100
    }

    // -------------------------------------------------------------------------
    // No pricing config
    // -------------------------------------------------------------------------

    "model without any pricing: estimatedCostUsd is null" {
        val native = anthropicNative(inputTokens = 500)
        val response = makeChatResponse(promptTokens = 500, completionTokens = 100, nativeUsage = native)
        val model = modelWithPricing() // all rates null

        val usage = CostCalculator.extract(response, AiApiType.Anthropic, model)

        usage.estimatedCostUsd.shouldBeNull()
        usage.inputTokens shouldBe 500L
        usage.outputTokens shouldBe 100L
    }

    "model without pricing: estimatedCostUsd is null not 0.0 — null means pricing unknown" {
        val native = openAiNative(promptTokens = 300, completionTokens = 60)
        val response = makeChatResponse(promptTokens = 300, completionTokens = 60, nativeUsage = native)
        val model = modelWithPricing()

        val usage = CostCalculator.extract(response, AiApiType.OpenAI, model)

        usage.estimatedCostUsd shouldBe null
    }

    // -------------------------------------------------------------------------
    // Absent / empty usage metadata
    // -------------------------------------------------------------------------

    "response with no usage metadata returns LlmUsage.ZERO" {
        // ChatResponseMetadata.builder().build() returns null for usage.
        // No pricing configured to keep the ZERO comparison unambiguous.
        val metadata = ChatResponseMetadata.builder().build()
        val response =
            ChatResponse(
                listOf(Generation(org.springframework.ai.chat.messages.AssistantMessage(""))),
                metadata,
            )
        val model = modelWithPricing()

        val usage = CostCalculator.extract(response, AiApiType.Anthropic, model)

        usage shouldBe LlmUsage.ZERO
    }

    "response with all-null token counts returns LlmUsage.ZERO" {
        val zeroUsage =
            object : Usage {
                override fun getPromptTokens(): Int? = null
                override fun getCompletionTokens(): Int? = null
                override fun getTotalTokens(): Int? = null
                override fun getNativeUsage(): Any? = null
            }
        val metadata = ChatResponseMetadata.builder().usage(zeroUsage).build()
        val response =
            ChatResponse(
                listOf(Generation(org.springframework.ai.chat.messages.AssistantMessage(""))),
                metadata,
            )
        val model = modelWithPricing()

        val result = CostCalculator.extract(response, AiApiType.Anthropic, model)

        result shouldBe LlmUsage.ZERO
    }

    // -------------------------------------------------------------------------
    // Unsupported providers (Gemini, Ollama) — cost must be null even with pricing
    // -------------------------------------------------------------------------

    "Gemini with pricing configured: estimatedCostUsd is null (input tokens not extractable)" {
        // Gemini does not expose cache-token details via reflection.
        // The generic Usage fields give us outputTokens and totalTokens, but input
        // is unavailable. A partial cost (input = 0) would be a silent undercount,
        // so we return null instead.
        val response = makeChatResponse(promptTokens = 500, completionTokens = 200, nativeUsage = null)
        val model = modelWithPricing(inputRate = 0.075, outputRate = 0.30)

        val usage = CostCalculator.extract(response, AiApiType.Gemini, model)

        usage.estimatedCostUsd shouldBe null
        // Token counts available from generic Usage fields are still recorded
        usage.outputTokens shouldBe 200L
        usage.totalTokens shouldBe 700L
    }

    "Ollama with pricing configured: estimatedCostUsd is null (input tokens not extractable)" {
        val response = makeChatResponse(promptTokens = 300, completionTokens = 100, nativeUsage = null)
        val model = modelWithPricing(inputRate = 0.1, outputRate = 0.2)

        val usage = CostCalculator.extract(response, AiApiType.Ollama, model)

        usage.estimatedCostUsd shouldBe null
        usage.outputTokens shouldBe 100L
        usage.totalTokens shouldBe 400L
    }

    "Gemini without pricing: estimatedCostUsd is null" {
        val response = makeChatResponse(promptTokens = 100, completionTokens = 50, nativeUsage = null)
        val model = modelWithPricing() // all rates null

        val usage = CostCalculator.extract(response, AiApiType.Gemini, model)

        usage.estimatedCostUsd shouldBe null
    }

    // -------------------------------------------------------------------------
    // LlmUsage.plus accumulation
    // -------------------------------------------------------------------------

    "LlmUsage.plus sums all token counts when both have known cost" {
        val a = LlmUsage(inputTokens = 100, outputTokens = 50, cacheReadTokens = 10, cacheWriteTokens = 5, totalTokens = 165, estimatedCostUsd = 0.001)
        val b = LlmUsage(inputTokens = 200, outputTokens = 80, cacheReadTokens = 20, cacheWriteTokens = 0, totalTokens = 300, estimatedCostUsd = 0.002)

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
