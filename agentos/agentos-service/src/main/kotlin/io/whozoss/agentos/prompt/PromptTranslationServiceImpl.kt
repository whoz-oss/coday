package io.whozoss.agentos.prompt

import io.whozoss.agentos.aiModel.AiModelService
import io.whozoss.agentos.aiProvider.AiProviderService
import io.whozoss.agentos.chat.ChatClientProvider
import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.namespace.NamespaceService
import mu.KLogging
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt as AiPrompt
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Translates [Prompt.content] and [Prompt.title] via the namespace's default AI model.
 *
 * Model resolution follows the same pattern as [io.whozoss.agentos.caseFlow.CaseNamingService]:
 * [AiModelService.findAiModel] with the namespace's default model. When neither [namespaceId]
 * nor [namespaceExternalId] resolves to a namespace, or when no model is configured, a
 * [BadRequestException] is thrown -- translation is impossible without an AI model.
 *
 * Each content element is translated individually in a separate LLM call so that
 * index alignment between source and translated lists is guaranteed regardless of
 * how the model formats multi-item responses.
 */
@Service
class PromptTranslationServiceImpl(
    private val aiModelService: AiModelService,
    private val aiProviderService: AiProviderService,
    private val chatClientProvider: ChatClientProvider,
    private val namespaceService: NamespaceService,
) : PromptTranslationService {

    override fun translateContent(
        content: List<String>,
        sourceLanguage: String,
        targetLanguage: String,
        namespaceId: UUID?,
        namespaceExternalId: String?,
    ): List<String> {
        val (model, provider) = resolveModelAndProvider(namespaceId, namespaceExternalId)
        val chatClient = chatClientProvider.getChatClient(model, provider)
        return content.map { element -> translateText(element, sourceLanguage, targetLanguage, chatClient, TextKind.CONTENT) }
    }

    override fun translateTitle(
        title: String,
        sourceLanguage: String,
        targetLanguage: String,
        namespaceId: UUID?,
        namespaceExternalId: String?,
    ): String {
        val (model, provider) = resolveModelAndProvider(namespaceId, namespaceExternalId)
        val chatClient = chatClientProvider.getChatClient(model, provider)
        return translateText(title, sourceLanguage, targetLanguage, chatClient, TextKind.TITLE)
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    /**
     * Distinguishes the two kinds of text a [Prompt] exposes, so the LLM receives
     * accurate context about what it is translating.
     *
     * - [TITLE] -- a short user-facing label shown on a button that starts a case.
     *   It summarises the prompt's purpose for the user, not for the agent.
     * - [CONTENT] -- one instruction element sent to the agent when the case starts.
     *   It is directive in tone and may be technical or detailed.
     */
    private enum class TextKind { TITLE, CONTENT }

    private fun resolveModelAndProvider(
        namespaceId: UUID?,
        namespaceExternalId: String?,
    ): Pair<io.whozoss.agentos.sdk.aiProvider.AiModel, io.whozoss.agentos.sdk.aiProvider.AiProvider> {
        val resolvedNamespaceId = resolveNamespaceId(namespaceId, namespaceExternalId)
        val model = aiModelService.findAiModel(resolvedNamespaceId)
            ?: throw BadRequestException(
                "No default AI model configured for namespace $resolvedNamespaceId -- cannot translate prompt",
            )
        val provider = aiProviderService.getById(model.aiProviderId)
        return model to provider
    }

    private fun resolveNamespaceId(namespaceId: UUID?, namespaceExternalId: String?): UUID =
        when {
            namespaceId != null -> namespaceId
            namespaceExternalId != null ->
                namespaceService.findByExternalId(namespaceExternalId)?.metadata?.id
                    ?: throw BadRequestException("Namespace not found for externalId '$namespaceExternalId'")
            else -> throw BadRequestException(
                "At least one of namespaceId or namespaceExternalId must be provided for prompt translation",
            )
        }

    /**
     * Calls the LLM to translate [text] from [sourceLanguage] into [targetLanguage].
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
    ): String {
        val context = when (kind) {
            TextKind.TITLE ->
                """A prompt title is a short label shown on a button that users click to start a conversation with an AI agent.
                |It summarises the prompt's purpose for the user in a few words.""".trimMargin()
            TextKind.CONTENT ->
                """A prompt content element is an instruction sent to an AI agent when a user starts a conversation.
                |It is directive in tone and tells the agent what to do.""".trimMargin()
        }

        val promptText = """
            $context

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

        return runCatching {
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

    companion object : KLogging()
}
