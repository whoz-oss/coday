package io.whozoss.agentos.caseFlow.postprocessing

import io.whozoss.agentos.aiModel.AiModelService
import io.whozoss.agentos.aiProvider.AiProviderService
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.CaseRepository
import io.whozoss.agentos.chat.ChatClientProvider
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.CaseUpdatedEvent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KLogging
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.stereotype.Component

/**
 * Post-processor that automatically generates a short title for a [Case] from the
 * first user message(s).
 *
 * ## Trigger conditions (both must hold)
 * - The case title is still the generated default (`"Case <uuid>"`).
 * - The event history contains exactly 1 or 2 user [MessageEvent]s — the naming window.
 *   After 2 user messages the title should already be set; further turns are skipped.
 *
 * ## Model resolution
 * Uses the namespace-default model (`alias = "default"`) resolved via [AiModelService].
 * This is independent of any agent selection — the naming call can run before the
 * first agent response is emitted.
 *
 * ## Output
 * Persists the new title directly on the [Case] via [CaseRepository], then emits a
 * transient [CaseUpdatedEvent] so connected SSE clients refresh their UI immediately.
 */
@Component
class CaseNamingProcessor(
    private val aiModelService: AiModelService,
    private val aiProviderService: AiProviderService,
    private val chatClientProvider: ChatClientProvider,
    private val caseRepository: CaseRepository,
) : CasePostProcessor {
    override fun shouldProcess(
        case: Case,
        events: List<CaseEvent>,
    ): Boolean {
        if (!isTitleGenerated(case)) return false
        val userMessageCount = countUserMessages(events)
        return userMessageCount in 1..MAX_USER_MESSAGES_FOR_NAMING
    }

    override suspend fun process(
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

        val userMessages = events
            .filterIsInstance<MessageEvent>()
            .filter { it.actor.role == ActorRole.USER }
            .takeLast(MAX_USER_MESSAGES_FOR_NAMING)

        val transcript = userMessages.joinToString("\n") { event ->
            event.content
                .filterIsInstance<io.whozoss.agentos.sdk.caseEvent.MessageContent.Text>()
                .joinToString(" ") { it.content }
                .trim()
        }.trim()

        if (transcript.isBlank()) {
            logger.debug { "[CaseNaming] No text content in user messages for case ${case.id}, skipping" }
            return
        }

        val title = generateTitle(transcript, case, model, provider) ?: return

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
        case: Case,
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
            val chatClient = chatClientProvider.getChatClient(model, provider, case.id.toString())
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
                ?.also { logger.debug { "[CaseNaming] LLM generated title for case ${case.id}: \"$it\"" } }
                ?: run {
                    logger.warn { "[CaseNaming] LLM returned blank title for case ${case.id}" }
                    null
                }
        }.onFailure { e ->
            logger.error(e) { "[CaseNaming] LLM call failed for case ${case.id}" }
        }.getOrNull()
    }

    /**
     * Returns true when the title matches the auto-generated default pattern
     * (`"Case <uuid>"`). A user-set or previously generated title must not be
     * overwritten.
     */
    private fun isTitleGenerated(case: Case): Boolean = case.title == "Case ${case.id}"

    private fun countUserMessages(events: List<CaseEvent>): Int =
        events.count { it is MessageEvent && it.actor.role == ActorRole.USER }

    companion object : KLogging() {
        private const val MAX_USER_MESSAGES_FOR_NAMING = 2
        private const val MAX_TITLE_LENGTH = 60
    }
}
