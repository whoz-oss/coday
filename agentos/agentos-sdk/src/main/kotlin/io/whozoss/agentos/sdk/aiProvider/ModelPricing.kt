package io.whozoss.agentos.sdk.aiProvider

/**
 * Per-million-token pricing rates (USD) for a specific AI model.
 *
 * All four rates are optional. When the instance is `null` on [AiModel] (no pricing
 * configured at all), cost estimation is skipped and
 * [io.whozoss.agentos.sdk.usage.LlmUsage.estimatedCostUsd] is `null` for every call
 * using that model. When the instance is present but individual rates are `null`, those
 * components contribute 0 to the estimate — a partial config still produces a
 * meaningful (though incomplete) estimate.
 *
 * Semantics match [io.whozoss.agentos.chat.CostCalculator] exactly:
 * - [inputMTokens]   base input tokens billed at full rate (prompt, excluding cache
 *                    hits for Anthropic; `promptTokens - cachedTokens` for OpenAI)
 * - [outputMTokens]  generated output tokens
 * - [cacheRead]      cache-read tokens (Anthropic `cacheReadInputTokens` /
 *                    OpenAI `PromptTokensDetails.cachedTokens`)
 * - [cacheWrite]     cache-write (creation) tokens — Anthropic only;
 *                    OpenAI does not expose a write rate
 */
data class ModelPricing(
    /** Per-million-token rate for base input tokens (USD). */
    val inputMTokens: Double? = null,
    /** Per-million-token rate for generated output tokens (USD). */
    val outputMTokens: Double? = null,
    /** Per-million-token rate for cache-read tokens (USD). Cheaper than [inputMTokens]. */
    val cacheRead: Double? = null,
    /**
     * Per-million-token rate for cache-write (creation) tokens (USD).
     * Anthropic only — pricier than [inputMTokens]. OpenAI has no write rate.
     */
    val cacheWrite: Double? = null,
) {
    /** True when no rate is configured — cost estimation will be skipped entirely. */
    val isEmpty: Boolean
        get() = inputMTokens == null && outputMTokens == null && cacheRead == null && cacheWrite == null
}
