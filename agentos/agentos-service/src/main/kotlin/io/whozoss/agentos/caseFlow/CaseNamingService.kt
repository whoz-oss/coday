package io.whozoss.agentos.caseFlow

import io.whozoss.agentos.aiModel.AiModelService
import io.whozoss.agentos.aiProvider.AiProviderService
import io.whozoss.agentos.chat.ChatClientProvider
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.CaseUpdatedEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KLogging
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Generates a short title for a [Case] from its first user message(s).
 *
 * Called directly by [CaseServiceImpl] at two points in the case lifecycle:
 * - after the first user message is added
 * - after the first agent turn completes (IDLE transition)
 *
 * In both cases [CaseServiceImpl] is responsible for checking the naming window
 * conditions before calling this service. This service only generates and persists
 * the title — it does not decide whether naming should happen.
 */
@Service
class CaseNamingService(
    private val aiModelService: AiModelService,
    private val aiProviderService: AiProviderService,
    private val chatClientProvider: ChatClientProvider,
    private val caseRepository: CaseRepository,
) {
    /**
     * Attempts to generate and persist a title for [case] from [events].
     *
     * Silently skips when no default AI model is configured for the namespace,
     * or when the LLM call fails. Emits a transient [CaseUpdatedEvent] via
     * [emitEvent] so connected SSE clients refresh immediately.
     */
    suspend fun nameCase(
        case: Case,
        events: List<CaseEvent>,
        emitEvent: (CaseEvent) -> Unit,
    ) {
        val model = aiModelService.findAiModel(case.namespaceId)
        if (model == null) {
            logger.debug { "[CaseNaming] No default model in namespace ${case.namespaceId}, skipping case ${case.id}" }
            return
        }
        val provider = runCatching { aiProviderService.getById(model.aiProviderId) }.getOrNull()
        if (provider == null) {
            logger.warn { "[CaseNaming] Provider ${model.aiProviderId} not found for model ${model.alias ?: model.apiModelName}, skipping case ${case.id}" }
            return
        }

        val transcript = events
            .filterIsInstance<MessageEvent>()
            .filter { it.actor.role == ActorRole.USER }
            .takeLast(MAX_USER_MESSAGES_FOR_NAMING)
            .joinToString("\n") { event ->
                event.content
                    .filterIsInstance<MessageContent.Text>()
                    .joinToString(" ") { it.content }
                    .trim()
            }.trim()

        if (transcript.isBlank()) {
            logger.debug { "[CaseNaming] No text content in user messages for case ${case.id}, skipping" }
            return
        }

        val title = generateTitle(transcript, case.id, model, provider) ?: return

        val updated = caseRepository.save(case.copy(title = title))
        logger.info { "[CaseNaming] Case ${case.id} titled: \"$title\"" }

        emitEvent(
            CaseUpdatedEvent(
                namespaceId = updated.namespaceId,
                caseId = updated.id,
                title = title,
            ),
        )
    }

    private suspend fun generateTitle(
        transcript: String,
        caseId: UUID,
        model: io.whozoss.agentos.sdk.aiProvider.AiModel,
        provider: io.whozoss.agentos.sdk.aiProvider.AiProvider,
    ): String? {
        val prompt = """
            Given the following user message(s) from a conversation, reply with ONLY a short title
            (maximum 60 characters, no quotes, no punctuation at the end).
            The title must be in the same language as the message(s).

            Message(s):
            $transcript

            Title:
        """.trimIndent()

        return runCatching {
            val chatClient = chatClientProvider.getChatClient(model, provider, caseId.toString())
            withContext(Dispatchers.IO) {
                chatClient
                    .prompt(Prompt(UserMessage(prompt)))
                    .call()
                    .content()
            }
                ?.trim()
                ?.trimEnd('.', '!', '?')
                ?.take(MAX_TITLE_LENGTH)
                ?.takeUnless { it.isBlank() }
                ?.also { logger.debug { "[CaseNaming] LLM generated title for case $caseId: \"$it\"" } }
                ?: run {
                    logger.warn { "[CaseNaming] LLM returned blank title for case $caseId" }
                    null
                }
        }.onFailure { e ->
            logger.error(e) { "[CaseNaming] LLM call failed for case $caseId" }
        }.getOrNull()
    }

    companion object : KLogging() {
        const val MAX_USER_MESSAGES_FOR_NAMING = 2
        private const val MAX_TITLE_LENGTH = 60
    }
}
