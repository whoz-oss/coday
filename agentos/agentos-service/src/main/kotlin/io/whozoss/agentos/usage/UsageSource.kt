package io.whozoss.agentos.usage

/**
 * The origin technology that generated a [UsageRecord].
 *
 * A single value for now — [LLM] covers every large-language-model call. Future values
 * (e.g. `EMBEDDING`, `IMAGE_GENERATION`, `STT`) will be added here without breaking
 * existing records, because [UsageRecord] stores the enum name as a plain string.
 */
enum class UsageSource {
    LLM,
}
