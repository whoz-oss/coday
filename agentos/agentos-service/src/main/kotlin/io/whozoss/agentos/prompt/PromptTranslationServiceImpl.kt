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
 *
 * The prompt treats every translatable string as a "conversation starter" -- a short,
 * action-oriented label shown on a clickable button. This framing matches both
 * [Prompt.content] elements (which are exactly that) and [Prompt.title] (which is also
 * a brief action label naming the prompt).
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
        return content.map { element -> translateText(element, sourceLanguage, targetLanguage, chatClient) }
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
        return translateText(title, sourceLanguage, targetLanguage, chatClient)
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

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
     * The prompt treats the text as a conversation starter -- a short, action-oriented
     * label -- and instructs the model to output only the translated string with no
     * surrounding markup. Falls back to the original [text] when the LLM returns blank
     * or throws, so a translation failure never breaks the caller.
     *
     * [sourceLanguage] is included explicitly in the prompt to remove ambiguity for
     * short strings that could plausibly belong to multiple languages.
     */
    private fun translateText(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
        chatClient: org.springframework.ai.chat.client.ChatClient,
    ): String {
        val promptText = """
            A conversation starter is a short action label shown on a clickable button in a chat interface.
            It represents an action the user can trigger, phrased as a brief, natural action phrase.

            Your goal is to translate the following starter text from $sourceLanguage to $targetLanguage.

            Starter text:
            <starter>
            $text
            </starter>

            ### Translation Guidelines
            - Translate the starter text naturally to the target language
            - Keep the meaning and intent of the action
            - Use appropriate terminology for the target language
            - Be concise and action-oriented

            Now give me the starter text translated to the specified target language.
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
