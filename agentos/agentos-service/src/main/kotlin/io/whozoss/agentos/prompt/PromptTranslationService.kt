package io.whozoss.agentos.prompt

import java.util.UUID

/**
 * Translates prompt content and title via the namespace's default AI model.
 *
 * The two methods are intentionally separate because [Prompt.content] is a `List<String>`
 * (translated element-by-element to preserve index alignment) while [Prompt.title] is a
 * single `String`. Keeping them apart avoids implicit shape assumptions and lets callers
 * invoke only what they need.
 *
 * Both methods require a namespace context for AI model resolution. Exactly one of
 * [namespaceId] / [namespaceExternalId] must be provided. Providing neither throws
 * [io.whozoss.agentos.exception.BadRequestException]. When no default model is configured
 * for the resolved namespace, a [io.whozoss.agentos.exception.BadRequestException] is thrown.
 *
 * On LLM failure the original text is returned unchanged — translation is best-effort and
 * must not break the caller's flow.
 */
interface PromptTranslationService {

    /**
     * Translates each element of [content] from [sourceLanguage] to [targetLanguage].
     *
     * Returns a list of the same size as [content], with each element independently
     * translated. Index alignment is guaranteed: `result[i]` is the translation of
     * `content[i]`.
     */
    fun translateContent(
        content: List<String>,
        sourceLanguage: String,
        targetLanguage: String,
        namespaceId: UUID?,
        namespaceExternalId: String?,
    ): List<String>

    /**
     * Translates a single [title] string from [sourceLanguage] to [targetLanguage].
     */
    fun translateTitle(
        title: String,
        sourceLanguage: String,
        targetLanguage: String,
        namespaceId: UUID?,
        namespaceExternalId: String?,
    ): String
}
