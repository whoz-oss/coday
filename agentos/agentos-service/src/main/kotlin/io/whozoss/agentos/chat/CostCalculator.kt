package io.whozoss.agentos.chat

import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.ModelPricing
import io.whozoss.agentos.sdk.usage.LlmUsage
import org.springframework.ai.chat.metadata.Usage
import org.springframework.ai.chat.model.ChatResponse

/**
 * Normalises provider token counts into [LlmUsage] without introducing provider dependencies in the SDK.
 *
 * Since Spring AI 2.0 the portable [Usage] contract carries the prompt-cache counts
 * ([Usage.getCacheReadInputTokens], [Usage.getCacheWriteInputTokens]), so no provider-native type is read.
 * The providers still differ on what the prompt count means:
 * - Anthropic: `input_tokens` already excludes cache reads and writes.
 * - OpenAI / vLLM: `prompt_tokens` includes the cached tokens, which are subtracted to avoid double-counting.
 *
 * A response without provider usage (no native usage object) is unknown cost, never a zero-input estimate.
 * Prices are snapshotted from the model used by the call; historical costs never change.
 */
object CostCalculator {
    private data class Input(
        val tokens: Long,
        val read: Long,
        val write: Long,
    )

    fun extract(
        response: ChatResponse,
        apiType: AiApiType,
        modelConfig: AiModel,
    ): LlmUsage {
        val usage = response.metadata.usage ?: return LlmUsage.ZERO
        val output = usage.completionTokens?.toLong() ?: 0L
        val total = usage.totalTokens?.toLong() ?: 0L
        val input = input(usage, apiType)
        // Anthropic cache-only responses have zero generic counts. Inspect cache usage
        // before discarding an empty chunk (also important on cancellation).
        if (total == 0L && (usage.promptTokens ?: 0) == 0 && output == 0L &&
            (input == null || input.tokens + input.read + input.write == 0L)
        ) {
            return LlmUsage.ZERO
        }
        return LlmUsage(
            inputTokens = input?.tokens ?: 0,
            outputTokens = output,
            cacheReadTokens = input?.read ?: 0,
            cacheWriteTokens = input?.write ?: 0,
            totalTokens = total,
            estimatedCostUsd = input?.let { estimate(modelConfig.pricing, it, output) },
        )
    }

    private fun input(
        usage: Usage,
        apiType: AiApiType,
    ): Input? {
        if (usage.nativeUsage == null) return null
        val prompt = usage.promptTokens.nonNegative()
        val read = usage.cacheReadInputTokens.nonNegative()
        val write = usage.cacheWriteInputTokens.nonNegative()
        return when (apiType) {
            AiApiType.Anthropic -> Input(prompt, read, write)
            AiApiType.OpenAI, AiApiType.vLLM -> Input((prompt - read).coerceAtLeast(0), read, 0)
            else -> null // Counts cannot be reliably normalised for these providers yet.
        }
    }

    private fun Number?.nonNegative(): Long = this?.toLong()?.coerceAtLeast(0) ?: 0L

    private fun estimate(
        pricing: ModelPricing?,
        input: Input,
        output: Long,
    ): Double? {
        if (pricing == null || pricing.isEmpty) return null
        // ModelPricing's existing contract treats omitted individual rates as zero.
        val cost =
            (
                input.tokens * (pricing.inputMTokens ?: 0.0) + output * (pricing.outputMTokens ?: 0.0) +
                    input.read * (pricing.cacheRead ?: 0.0) + input.write * (pricing.cacheWrite ?: 0.0)
            ) / 1_000_000.0
        return cost.takeIf { it.isFinite() && it >= 0 }
    }
}
