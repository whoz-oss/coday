package io.whozoss.agentos.sdk.api.user

import io.swagger.v3.oas.annotations.media.Schema

/**
 * How long the provider keeps a user's conversation prompt cached between two LLM calls.
 *
 * Only applied to Claude models via Requesty today; other models and providers keep their own caching
 * behaviour.
 *
 * Cost trade-off, Anthropic list prices relative to the base input token price: 5m writes 1.25x, 1h writes 2x
 * input; reads are much cheaper (0.05x-0.1x depending on model) and refresh the TTL.
 * - [FIVE_MINUTES]: the router's default automatic caching, unchanged.
 * - [ONE_HOUR]: each new prefix costs more to cache, but the conversation stays cached through pauses longer
 *   than five minutes.
 *
 * Since every read refreshes the entry's lifetime, the TTL only matters for the idle time between two calls.
 *
 * Published as one shared OpenAPI schema, so the generated clients have a single enum.
 */
@Schema(enumAsRef = true)
enum class PromptCacheTtl {
    FIVE_MINUTES,
    ONE_HOUR,
}
