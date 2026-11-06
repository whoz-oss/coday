package io.whozoss.agentos.caseFlow

import io.whozoss.agentos.aiModel.AiModelService
import io.whozoss.agentos.aiProvider.AiProviderService
import io.whozoss.agentos.chat.ChatClientProvider
import io.whozoss.agentos.chat.forUtilityCall
import io.whozoss.agentos.namespace.NamespaceCaseListUpdateBroadcaster
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
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
 * Maintains the short summary of a [Case], which the case search matches (#1566).
 *
 * [CaseServiceImpl] decides when a refresh is warranted (see its `triggerSummaryIfNeeded`); this
 * service only generates and persists the summary.
 *
 * Cost is kept small on purpose: one utility call (the namespace default model, reasoning effort
 * capped at low, exactly like [CaseNamingService]) fed with the previous summary and at most the last
 * [MAX_MESSAGES] messages, each truncated, [MAX_INPUT_CHARS] characters overall. The summary is
 * meant for search and identification, not for the model context: it is unrelated to context
 * compaction.
 *
 * There is no fallback text: without a default model, a provider or an answer, nothing is written and
 * the previous summary (if any) stays.
 */
@Service
class CaseSummaryService(
    private val aiModelService: AiModelService,
    private val aiProviderService: AiProviderService,
    private val chatClientProvider: ChatClientProvider,
    private val caseRepository: CaseRepository,
    private val caseListUpdateBroadcaster: NamespaceCaseListUpdateBroadcaster,
) {
    /**
     * Generates a new summary of [case] from [events] and stores it, then tells the namespace
     * clients to reload their case list. Silently skips (with a log) when there is nothing to
     * summarize, no usable model, or the LLM call fails.
     */
    suspend fun summarizeCase(
        case: Case,
        events: List<CaseEvent>,
    ) {
        val transcript = buildTranscript(case.summary, events)
        if (transcript == null) {
            logger.debug { "[CaseSummary] No text messages for case ${case.id}, skipping" }
            return
        }

        val model = aiModelService.findAiModel(case.namespaceId)
        if (model == null) {
            logger.debug { "[CaseSummary] No default model in namespace ${case.namespaceId}, skipping case ${case.id}" }
            return
        }
        val provider = runCatching { aiProviderService.getById(model.aiProviderId) }.getOrNull()
        if (provider == null) {
            logger.warn { "[CaseSummary] Provider ${model.aiProviderId} not found, skipping case ${case.id}" }
            return
        }

        val summary = generateSummary(transcript, case.id, model, provider) ?: return
        val userMessageCount = countUserMessages(events)

        // Reload right before saving so that fields changed while the LLM call was in flight (status,
        // title, a deletion) are not overwritten. A case that no longer exists is not brought back.
        val fresh = caseRepository.findByIds(setOf(case.id)).firstOrNull()
        if (fresh == null) {
            logger.debug { "[CaseSummary] Case ${case.id} no longer exists, summary dropped" }
            return
        }
        val updated =
            caseRepository.saveChange(fresh) {
                it.copy(summary = summary, summaryMessageCount = userMessageCount)
            }
        logger.info { "[CaseSummary] Case ${case.id} summarized (${summary.length} chars, $userMessageCount user messages)" }
        caseListUpdateBroadcaster.publishCaseUpdated(updated.namespaceId)
    }

    private suspend fun generateSummary(
        transcript: String,
        caseId: UUID,
        model: AiModel,
        provider: AiProvider,
    ): String? {
        val prompt =
            """
            Summarize the conversation below so that its owner can recognise it later in a list of cases.
            Reply with ONLY the summary: 2 to 3 factual sentences, at most $SUMMARY_PROMPT_CHARS characters,
            no preamble, no title, no quotes. Say what the case is about and its current state or outcome.
            If a previous summary is given, update it with what happened since instead of starting over.
            Write in the same language as the conversation.

            <conversation>
            $transcript
            </conversation>

            Summary:
            """.trimIndent()

        return runCatching {
            val chatClient = chatClientProvider.getChatClient(model.forUtilityCall(), provider, caseId.toString())
            withContext(Dispatchers.IO) {
                chatClient
                    .prompt(Prompt(UserMessage(prompt)))
                    .call()
                    .content()
            }?.trim()
                ?.take(MAX_SUMMARY_LENGTH)
                ?.trim()
                ?.takeUnless { it.isBlank() }
                ?: run {
                    logger.warn { "[CaseSummary] LLM returned a blank summary for case $caseId" }
                    null
                }
        }.onFailure { e ->
            logger.error(e) { "[CaseSummary] LLM call failed for case $caseId" }
        }.getOrNull()
    }

    companion object : KLogging() {
        /** Most recent messages (user and agent) fed to the LLM. */
        const val MAX_MESSAGES = 12

        /** Each message is cut to this many characters before it joins the transcript. */
        const val MAX_MESSAGE_CHARS = 1_000

        /** Cap of the whole prompt input: previous summary plus transcript. The oldest messages go first. */
        const val MAX_INPUT_CHARS = 8_000

        /** Longest summary stored, whatever the model answered. */
        const val MAX_SUMMARY_LENGTH = 500

        /** Target length announced to the model, below the storage cap so that it is rarely cut. */
        private const val SUMMARY_PROMPT_CHARS = 400

        /** Number of user messages of a case: what the refresh rule counts. */
        fun countUserMessages(events: List<CaseEvent>): Int =
            events.count { it is MessageEvent && it.actor.role == ActorRole.USER }

        /**
         * Text of the previous summary and of the last messages, or null when there is no message text.
         * Messages are kept newest first until [MAX_INPUT_CHARS] is reached, then put back in order.
         */
        internal fun buildTranscript(
            previousSummary: String?,
            events: List<CaseEvent>,
        ): String? {
            val lines =
                events
                    .filterIsInstance<MessageEvent>()
                    .mapNotNull { event ->
                        val text =
                            event.content
                                .filterIsInstance<MessageContent.Text>()
                                .joinToString(" ") { it.content }
                                .trim()
                                .take(MAX_MESSAGE_CHARS)
                        if (text.isBlank()) {
                            null
                        } else {
                            val speaker = if (event.actor.role == ActorRole.USER) "User" else "Agent"
                            "$speaker: $text"
                        }
                    }.takeLast(MAX_MESSAGES)
            if (lines.isEmpty()) return null

            val header = previousSummary?.takeIf { it.isNotBlank() }?.let { "Previous summary: $it\n\n" } ?: ""
            var remaining = MAX_INPUT_CHARS - header.length
            val kept = ArrayDeque<String>()
            for (line in lines.asReversed()) {
                val cost = line.length + 1
                if (cost > remaining) break
                kept.addFirst(line)
                remaining -= cost
            }
            // A single line is at most MAX_MESSAGE_CHARS plus its prefix, far below the cap; an oversized
            // previous summary (never produced here) could still leave no room, in which case keep the latest line cut.
            if (kept.isEmpty()) kept.addFirst(lines.last().take(MAX_INPUT_CHARS))
            return header + kept.joinToString("\n")
        }
    }
}
