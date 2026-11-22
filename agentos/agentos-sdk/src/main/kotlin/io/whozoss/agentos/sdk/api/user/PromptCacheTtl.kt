package io.whozoss.agentos.sdk.api.user

import io.swagger.v3.oas.annotations.media.Schema

/**
 * How long a user wants their conversation prompt kept cached between two LLM calls, as an intent: the
 * effective lifetime depends on the model and provider.
 *
 * - [STANDARD]: the provider's default caching, nothing extra is sent (Claude via Requesty: 5 minutes; OpenAI
 *   GPT: about 30 minutes).
 * - [EXTENDED]: keep the cache through longer pauses. Today only Claude models via Requesty get it (explicit
 *   1-hour cache markers, each cache write costs 2x input instead of 1.25x); other models behave as [STANDARD].
 *
 * Since every cache read refreshes the entry's lifetime, the choice only matters for the idle time between two
 * calls.
 *
 * Published as one shared OpenAPI schema, so the generated clients have a single enum.
 */
@Schema(enumAsRef = true)
enum class PromptCacheTtl {
    STANDARD,
    EXTENDED,
}
