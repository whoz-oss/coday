package io.whozoss.agentos.prompt

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import io.whozoss.agentos.aiModel.AiModelService
import io.whozoss.agentos.aiProvider.AiProviderService
import io.whozoss.agentos.chat.ChatClientProvider
import io.whozoss.agentos.exception.BadRequestException
import mu.KLogging
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.stereotype.Service
import java.util.UUID
import org.springframework.ai.chat.prompt.Prompt as AiPrompt

/**
 * Translates [Prompt.content] and [Prompt.title] via the namespace's default AI model.
 *
 * Model resolution follows the same pattern as [io.whozoss.agentos.caseFlow.CaseNamingService]:
 * [AiModelService.findAiModel] with the namespace's default model. When no model is configured
 * for the namespace, a [BadRequestException] is thrown -- translation is impossible without
 * an AI model.
 *
 * Each content element is translated individually in a separate LLM call so that index
 * alignment between source and translated lists is guaranteed regardless of how the model
 * formats multi-item responses.
 *
 * **Cross-namespace LLM cache**: identical text translated with the same model is cached
 * in-memory by `(text, sourceLanguage, targetLanguage, modelId)`. This avoids redundant
 * LLM calls when the same platform-level prompt is translated for multiple federations
 * that share the same AI model. The cache is process-scoped and unbounded; it is
 * appropriate here because the key space is naturally small (number of distinct prompt
 * texts × language pairs × distinct models).
 */
@Service
class PromptTranslationServiceImpl(
    private val aiModelService: AiModelService,
    private val aiProviderService: AiProviderService,
    private val chatClientProvider: ChatClientProvider,
    private val cacheProperties: PromptTranslationCacheProperties,
) : PromptTranslationService {
    /** Cache key: model-agnostic translation unit. */
    private data class CacheKey(
        val text: String,
        val sourceLanguage: String,
        val targetLanguage: String,
        val modelId: UUID,
        val kind: TextKind,
    )

    private val translationCache: Cache<CacheKey, String> =
        Caffeine
            .newBuilder()
            .maximumSize(cacheProperties.maxSize)
            .expireAfterWrite(cacheProperties.ttl)
            .build()

    override fun translateContent(
        content: List<String>,
        sourceLanguage: String,
        targetLanguage: String,
        namespaceId: UUID,
    ): List<String> {
        val (model, provider) = resolveModelAndProvider(namespaceId)
        val chatClient = chatClientProvider.getChatClient(model, provider)
        return content.map { element ->
            translateText(
                text = element,
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
                chatClient = chatClient,
                kind = TextKind.CONTENT,
                modelId = model.metadata.id,
            )
        }
    }

    override fun translateTitle(
        title: String,
        sourceLanguage: String,
        targetLanguage: String,
        namespaceId: UUID,
    ): String {
        val (model, provider) = resolveModelAndProvider(namespaceId)
        val chatClient = chatClientProvider.getChatClient(model, provider)
        return translateText(
            text = title,
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
            chatClient = chatClient,
            kind = TextKind.TITLE,
            modelId = model.metadata.id,
        )
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private fun resolveModelAndProvider(
        namespaceId: UUID,
    ): Pair<io.whozoss.agentos.sdk.aiProvider.AiModel, io.whozoss.agentos.sdk.aiProvider.AiProvider> {
        val model =
            aiModelService.findAiModel(namespaceId)
                ?: throw BadRequestException(
                    "No default AI model configured for namespace $namespaceId -- cannot translate prompt",
                )
        val provider = aiProviderService.getById(model.aiProviderId)
        return model to provider
    }

    /**
     * Calls the LLM to translate [text] from [sourceLanguage] into [targetLanguage],
     * returning a cached result when the same `(text, sourceLanguage, targetLanguage,
     * modelId, kind)` tuple has been translated before.
     *
     * [kind] drives the context paragraph so the model understands what it is
     * translating: a user-facing button label ([TextKind.TITLE]) or an agent
     * instruction ([TextKind.CONTENT]).
     *
     * Falls back to the original [text] when the LLM returns blank or throws.
     * [sourceLanguage] is included explicitly to remove ambiguity for short strings
     * that could plausibly belong to multiple languages.
     */
    private fun translateText(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
        chatClient: org.springframework.ai.chat.client.ChatClient,
        kind: TextKind,
        modelId: UUID,
    ): String {
        val key = CacheKey(text, sourceLanguage, targetLanguage, modelId, kind)
        return translationCache.get(key) {
            val promptText =
                """
                ${kind.context}

                Your goal is to translate the following text from $sourceLanguage to $targetLanguage.

                Text:
                <text>
                $text
                </text>

                ### Translation Guidelines
                - Translate the text naturally to the target language
                - Keep the meaning and intent
                - Use appropriate terminology for the target language
                - Be concise and preserve the tone (action-oriented for titles, directive for content)

                Now give me the text translated to the specified target language.
                Output only the translated string, with no XML tags or formatting.
                """.trimIndent()

            runCatching {
                chatClient
                    .prompt(AiPrompt(UserMessage(promptText)))
                    .call()
                    .content()
                    ?.trim()
                    ?.takeUnless { it.isBlank() }
                    ?: run {
                        logger.warn { "[PromptTranslation] LLM returned blank for text: ${text.take(80)}" }
                        text
                    }
            }.onFailure { e ->
                logger.error(e) { "[PromptTranslation] LLM call failed translating text: ${text.take(80)}" }
            }.getOrElse { text }
        }
    }

    companion object : KLogging()
}
