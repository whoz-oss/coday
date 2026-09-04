package io.whozoss.agentos.chat

import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.usage.LlmUsage
import mu.KLogging
import org.springframework.ai.chat.model.ChatResponse

/**
 * Extracts token usage from a [ChatResponse] and optionally estimates cost.
 *
 * ## Provider-specific normalisation
 *
 * Token counts in [LlmUsage] follow a **provider-normalised** convention: see
 * [LlmUsage] KDoc for the full definition. The key difference handled here:
 *
 * - **Anthropic** (`AnthropicApi.Usage`): `inputTokens()` already excludes cache
 *   tokens, so it maps directly to [LlmUsage.inputTokens]. Cache details are
 *   in `cacheCreationInputTokens()` and `cacheReadInputTokens()`.
 *
 * - **OpenAI / vLLM** (`OpenAiApi.Usage`): `promptTokens()` **includes** cached
 *   tokens. To avoid double-counting, [LlmUsage.inputTokens] is set to
 *   `max(0, promptTokens - cachedTokens)`. The cached count comes from
 *   `promptTokensDetails().cachedTokens()`. OpenAI has no cache-write count.
 *
 * ## Reflection
 *
 * Anthropic and OpenAI provider JARs are **transitive** dependencies in
 * `agentos-service` (not declared in `build.gradle.kts`). Adding them as explicit
 * `compileOnly` dependencies would tightly couple the service to provider-specific
 * classes that may be absent in a deployment using only Ollama or Gemini. Reflection
 * keeps the extraction decoupled: a failure falls back gracefully to 0 cache tokens,
 * but the first failure per JVM run is logged at **WARN** so it does not go unnoticed.
 *
 * Method names are verified indirectly via `CostCalculatorUnitSpec`, which constructs
 * real [org.springframework.ai.anthropic.api.AnthropicApi.Usage] and
 * [org.springframework.ai.openai.api.OpenAiApi.Usage] instances (available on the test
 * classpath as transitive dependencies) and asserts that cache tokens extracted via
 * reflection are non-zero. A test failure there means the method names no longer match.
 * The reflection fallback (WARN log on first failure, then 0 cache tokens) is the
 * runtime safety net if a Spring AI upgrade changes the native API.
 *
 * ## Pricing
 *
 * Pricing is configured on [AiModel] as four optional per-million-token rates.
 * When all four are absent (null), cost is not estimated and
 * [LlmUsage.estimatedCostUsd] is `null`.
 */
object CostCalculator : KLogging() {

    // Tracks whether a reflection warning has already been emitted per provider,
    // to avoid flooding the log on every call.
    @Volatile private var anthropicWarnEmitted = false
    @Volatile private var openAiWarnEmitted = false

    /**
     * Extracts token usage from [response] and computes an estimated cost when
     * pricing is configured on [modelConfig].
     *
     * Returns [LlmUsage.ZERO] when the response carries no usage metadata (e.g. an
     * intermediate streaming chunk that has not yet accumulated totals).
     */
    fun extract(
        response: ChatResponse,
        apiType: AiApiType,
        modelConfig: AiModel,
    ): LlmUsage {
        val usage = response.metadata.usage ?: return LlmUsage.ZERO
        if (usage.totalTokens == null && usage.promptTokens == null && usage.completionTokens == null) {
            return LlmUsage.ZERO
        }

        val outputTokens = usage.completionTokens?.toLong() ?: 0L
        val totalTokens = usage.totalTokens?.toLong() ?: 0L

        val normalisedInput = extractNormalisedInputAndCache(usage.nativeUsage, apiType)

        return when (normalisedInput) {
            is NormalisedInput.Known -> {
                val estimatedCost = estimateCost(
                    modelConfig,
                    normalisedInput.inputTokens,
                    outputTokens,
                    normalisedInput.cacheRead,
                    normalisedInput.cacheWrite,
                )
                LlmUsage(
                    inputTokens = normalisedInput.inputTokens,
                    outputTokens = outputTokens,
                    cacheReadTokens = normalisedInput.cacheRead,
                    cacheWriteTokens = normalisedInput.cacheWrite,
                    totalTokens = totalTokens,
                    estimatedCostUsd = estimatedCost,
                )
            }
            // Input tokens are not extractable for this provider (e.g. Gemini, Ollama).
            // We still record output/total counts — they come from the generic Usage fields
            // and are accurate. But we do NOT compute a cost: a partial estimate
            // (inputCost = 0) would be a silent undercount, more dangerous than null.
            NormalisedInput.Unknown -> LlmUsage(
                inputTokens = 0L,
                outputTokens = outputTokens,
                cacheReadTokens = 0L,
                cacheWriteTokens = 0L,
                totalTokens = totalTokens,
                estimatedCostUsd = null,
            )
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Outcome of a provider-specific input-token normalisation attempt.
     *
     * [Known] means the normalisation succeeded and all three counts are reliable:
     * the caller can safely compute a cost estimate.
     *
     * [Unknown] means the [AiApiType] is not (yet) supported for input-token
     * extraction (e.g. Gemini, Ollama). Output and total tokens are still available
     * from the generic [Usage] fields, but the input count is unavailable. The caller
     * must not compute a cost estimate in this case — a partial cost (input = 0) is
     * more dangerous than no cost, because it inspires false confidence in aggregates.
     */
    private sealed interface NormalisedInput {
        data class Known(val inputTokens: Long, val cacheRead: Long, val cacheWrite: Long) : NormalisedInput
        data object Unknown : NormalisedInput
    }

    /**
     * Returns the normalised input-token counts from the provider-native usage object,
     * or [NormalisedInput.Unknown] when the [apiType] is not supported.
     *
     * **Normalisation contract** (see [LlmUsage] KDoc):
     * - Anthropic: `inputTokens` is already net-of-cache — used as-is.
     * - OpenAI/vLLM: `inputTokens = max(0, promptTokens - cachedTokens)` to avoid
     *   double-counting cached tokens that are also present in `promptTokens`.
     * - Gemini / Ollama / others: [NormalisedInput.Unknown] — regardless of whether
     *   [nativeUsage] is null or not, cost estimation is disabled for these providers
     *   because their native usage objects do not expose input tokens in a stable,
     *   reflection-accessible form.
     *
     * For Anthropic and OpenAI/vLLM, a null [nativeUsage] produces `Known(0, 0, 0)`
     * (no cache details available, but the provider IS supported). A non-null
     * [nativeUsage] is passed to the provider-specific extractor, which falls back to
     * `Known(0, 0, 0)` on reflection failure (logging WARN the first time).
     */
    private fun extractNormalisedInputAndCache(
        nativeUsage: Any?,
        apiType: AiApiType,
    ): NormalisedInput =
        when (apiType) {
            AiApiType.Anthropic -> if (nativeUsage != null) extractAnthropic(nativeUsage) else NormalisedInput.Known(0L, 0L, 0L)
            AiApiType.OpenAI, AiApiType.vLLM -> if (nativeUsage != null) extractOpenAi(nativeUsage) else NormalisedInput.Known(0L, 0L, 0L)
            // Gemini and Ollama do not expose cache-token details in a stable,
            // reflection-accessible form. Returning Unknown prevents a partial cost
            // estimate (input = 0, output = real) from being recorded as a known value.
            // This holds regardless of whether nativeUsage is null or not: even with a
            // non-null native object we cannot safely extract input tokens for these providers.
            else -> NormalisedInput.Unknown
        }

    /**
     * Anthropic: `AnthropicApi.Usage` — verified via `CostCalculatorUnitSpec` using the real class:
     * `inputTokens()`, `cacheCreationInputTokens()`, `cacheReadInputTokens()`
     *
     * `inputTokens()` already excludes cache tokens on Anthropic's side, so it
     * maps directly to the normalised [LlmUsage.inputTokens] without adjustment.
     *
     * On reflection failure, returns `Known(0, 0, 0)` rather than [NormalisedInput.Unknown]:
     * Anthropic IS a supported provider; only the cache details could not be read.
     * The WARN log is emitted once so the misconfiguration is visible.
     */
    private fun extractAnthropic(native: Any): NormalisedInput =
        runCatching {
            val cls = native.javaClass
            val inputTokens = (cls.getMethod("inputTokens").invoke(native) as? Int ?: 0).toLong()
            val cacheWrite = (cls.getMethod("cacheCreationInputTokens").invoke(native) as? Int ?: 0).toLong()
            val cacheRead = (cls.getMethod("cacheReadInputTokens").invoke(native) as? Int ?: 0).toLong()
            NormalisedInput.Known(inputTokens, cacheRead, cacheWrite)
        }.getOrElse { e ->
            if (!anthropicWarnEmitted) {
                anthropicWarnEmitted = true
                logger.warn {
                    "[CostCalculator] Failed to extract Anthropic cache tokens via reflection " +
                        "(native type: ${native.javaClass.name}): ${e.message}. " +
                        "Cache tokens will be 0 for this session."
                }
            }
            NormalisedInput.Known(0L, 0L, 0L)
        }

    /**
     * OpenAI: `OpenAiApi.Usage` — verified via `CostCalculatorUnitSpec` using the real class:
     * `promptTokens()`, `promptTokensDetails()` → `PromptTokensDetails.cachedTokens()`
     *
     * `promptTokens()` **includes** cached tokens on OpenAI's side. To normalise:
     * `inputTokens = max(0, promptTokens - cachedTokens)`
     * so that `inputTokens` means "tokens billed at full input rate" across all providers.
     *
     * On reflection failure, returns `Known(0, 0, 0)` rather than [NormalisedInput.Unknown]:
     * same rationale as [extractAnthropic] — the provider is supported, only cache
     * details could not be read.
     */
    private fun extractOpenAi(native: Any): NormalisedInput =
        runCatching {
            val cls = native.javaClass
            val promptTokens = (cls.getMethod("promptTokens").invoke(native) as? Int ?: 0).toLong()
            val details = cls.getMethod("promptTokensDetails").invoke(native)
            val cachedTokens =
                if (details != null) {
                    (details.javaClass.getMethod("cachedTokens").invoke(details) as? Int ?: 0).toLong()
                } else {
                    0L
                }
            // Normalise: subtract cached tokens from prompt total to avoid double-counting.
            val inputTokens = maxOf(0L, promptTokens - cachedTokens)
            NormalisedInput.Known(inputTokens, cachedTokens, 0L) // OpenAI has no cache-write token count
        }.getOrElse { e ->
            if (!openAiWarnEmitted) {
                openAiWarnEmitted = true
                logger.warn {
                    "[CostCalculator] Failed to extract OpenAI cache tokens via reflection " +
                        "(native type: ${native.javaClass.name}): ${e.message}. " +
                        "Cache tokens will be 0 for this session."
                }
            }
            NormalisedInput.Known(0L, 0L, 0L)
        }

    /**
     * Estimates cost in USD from normalised token counts and the model's per-million-token rates.
     * Returns `null` when no pricing is configured (all four rates are absent).
     *
     * Each count is billed at its own rate:
     * - [inputTokens]      → [AiModel.pricingInputMTokens]   (full input rate)
     * - [outputTokens]     → [AiModel.pricingOutputMTokens]  (generation rate)
     * - [cacheReadTokens]  → [AiModel.pricingCacheRead]      (cache-read rate, cheaper than input)
     * - [cacheWriteTokens] → [AiModel.pricingCacheWrite]     (cache-write rate, pricier than input)
     *
     * Missing rates default to 0.0 for that component so a partial pricing config
     * still produces a meaningful estimate.
     */
    private fun estimateCost(
        model: AiModel,
        inputTokens: Long,
        outputTokens: Long,
        cacheReadTokens: Long,
        cacheWriteTokens: Long,
    ): Double? {
        val inputRate = model.pricingInputMTokens
        val outputRate = model.pricingOutputMTokens
        val cacheReadRate = model.pricingCacheRead
        val cacheWriteRate = model.pricingCacheWrite

        if (inputRate == null && outputRate == null && cacheReadRate == null && cacheWriteRate == null) {
            return null
        }

        val inputCost = (inputTokens / 1_000_000.0) * (inputRate ?: 0.0)
        val outputCost = (outputTokens / 1_000_000.0) * (outputRate ?: 0.0)
        val cacheReadCost = (cacheReadTokens / 1_000_000.0) * (cacheReadRate ?: 0.0)
        val cacheWriteCost = (cacheWriteTokens / 1_000_000.0) * (cacheWriteRate ?: 0.0)

        return inputCost + outputCost + cacheReadCost + cacheWriteCost
    }

    /** Resets the warn-emitted flags. Exposed for testing only. */
    internal fun resetWarnings() {
        anthropicWarnEmitted = false
        openAiWarnEmitted = false
    }
}
