package io.whozoss.agentos.chat

import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.ModelPricing
import io.whozoss.agentos.sdk.usage.LlmUsage
import mu.KLogging
import org.springframework.ai.chat.model.ChatResponse
import java.util.concurrent.ConcurrentHashMap

/**
 * Normalises native cache counts without introducing provider dependencies in the SDK.
 * Missing/unreadable native metadata is unknown cost, never a zero-input estimate.
 * Prices are snapshotted from the model used by the call; historical costs never change.
 */
object CostCalculator : KLogging() {
    private val warned = ConcurrentHashMap.newKeySet<AiApiType>()

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
        val input = nativeInput(usage.nativeUsage, apiType)
        // Anthropic cache-only responses have zero generic counts. Inspect native
        // cache usage before discarding an empty chunk (also important on cancellation).
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

    private fun nativeInput(
        native: Any?,
        apiType: AiApiType,
    ): Input? {
        if (native == null) return null
        return runCatching {
            when (apiType) {
                AiApiType.Anthropic ->
                    Input(
                        count(native, "inputTokens"),
                        count(native, "cacheReadInputTokens"),
                        count(native, "cacheCreationInputTokens"),
                    )
                AiApiType.OpenAI, AiApiType.vLLM -> {
                    val prompt = count(native, "promptTokens")
                    val details = native.javaClass.getMethod("promptTokensDetails").invoke(native)
                    val cached = details?.let { count(it, "cachedTokens") } ?: 0L
                    Input((prompt - cached).coerceAtLeast(0), cached, 0)
                }
                else -> null // Counts cannot be reliably normalised for these providers yet.
            }
        }.getOrElse {
            if (warned.add(apiType)) logger.warn { "Usage metadata for $apiType could not be read; cost remains unknown" }
            null
        }
    }

    private fun count(
        native: Any,
        method: String,
    ): Long = (native.javaClass.getMethod(method).invoke(native) as? Number)?.toLong()?.coerceAtLeast(0) ?: 0L

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

    internal fun resetWarnings() = warned.clear()
}
