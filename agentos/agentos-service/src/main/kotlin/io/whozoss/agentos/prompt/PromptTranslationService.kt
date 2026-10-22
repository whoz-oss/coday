package io.whozoss.agentos.prompt

import java.util.UUID

/**
 * Translates prompt content and title via a namespace's default AI model.
 *
 * Also exposes a text-level translation capability ([translateText]) that does not require
 * a persisted [Prompt] entity — for use by callers such as [io.whozoss.agentos.casePlugin.CaseStarterService]
 * that have free text, not a Prompt UUID.
 *
 * The two methods are intentionally separate because [Prompt.content] is a `List<String>`
 * (translated element-by-element to preserve index alignment) while [Prompt.title] is a
 * single `String`. Keeping them apart avoids implicit shape assumptions and lets callers
 * invoke only what they need.
 *
 * Both methods take an already-resolved [namespaceId]. Callers are responsible for resolving
 * namespace external identifiers before calling these methods — resolution belongs in the
 * controller or service layer, not here.
 *
 * When no default model is configured for the namespace, a
 * [io.whozoss.agentos.exception.BadRequestException] is thrown.
 *
 * On LLM failure the original text is returned unchanged — translation is best-effort and
 * must not break the caller's flow.
 */
interface PromptTranslationService {

    /**
     * Translates each element of [content] from [sourceLanguage] to [targetLanguage]
     * using the default model of [namespaceId].
     *
     * Returns a list of the same size as [content], with each element independently
     * translated. Index alignment is guaranteed: `result[i]` is the translation of
     * `content[i]`.
     */
    fun translateContent(
        content: List<String>,
        sourceLanguage: String,
        targetLanguage: String,
        namespaceId: UUID,
    ): List<String>

    /**
     * Translates a single [title] string from [sourceLanguage] to [targetLanguage]
     * using the default model of [namespaceId].
     */
    fun translateTitle(
        title: String,
        sourceLanguage: String,
        targetLanguage: String,
        namespaceId: UUID,
    ): String

    /**
     * Translates a single [text] string from [sourceLanguage] to [targetLanguage] using the
     * namespace's default AI model. [kind] drives the translation context prompt (TITLE vs CONTENT).
     *
     * Results are cached on `(text, sourceLanguage, targetLanguage, modelId, kind)`.
     *
     * **Never throws**: returns [text] unchanged when no model is configured, when the LLM call
     * fails, or on any other error. Translation is best-effort and must not break the caller's flow.
     */
    fun translateText(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
        namespaceId: UUID,
        kind: TextKind,
    ): String
}
