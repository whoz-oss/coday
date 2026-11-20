package io.whozoss.agentos.sdk.api.user

import io.swagger.v3.oas.annotations.media.Schema

/**
 * How long the provider keeps a user's conversation prompt cached between two LLM calls.
 *
 * Only applied to Claude models reached through the Requesty router today; other providers keep their own
 * caching behaviour.
 *
 * Cost trade-off (Anthropic pricing, relative to the base input token price):
 * - [FIVE_MINUTES]: cache writes cost 1.25x. This is the router's default automatic caching, unchanged.
 * - [ONE_HOUR]: cache writes cost 2x, so each new prefix costs more to cache, but the conversation stays
 *   cached through pauses longer than five minutes.
 *
 * With both, cache reads cost 0.1x and every read refreshes the entry's lifetime, so the TTL only matters
 * for the idle time between two calls.
 *
 * Published as one shared OpenAPI schema, so the generated clients have a single enum.
 */
@Schema(enumAsRef = true)
enum class PromptCacheTtl {
    FIVE_MINUTES,
    ONE_HOUR,
}
