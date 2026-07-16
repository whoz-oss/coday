package io.whozoss.agentos.agent

import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.IntentionGeneratedEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.ToolRequestEvent
import io.whozoss.agentos.sdk.caseEvent.ToolResponseEvent
import io.whozoss.agentos.sdk.tool.StandardTool
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.messages.UserMessage
import java.util.UUID

data class AgentAdvancedContext(
    val chatClient: ChatClient,
    val tools: List<StandardTool<*>>,
    val instructions: String?,
    val agentId: UUID,
    val confirmationManager: ConfirmationManager,
    /** Namespace context block sent as a privileged system message, prepended before event history. */
    val systemPrompt: String? = null,
) {
    /**
     * Build the complete message list for a single LLM call.
     *
     * - [systemPrompt] (namespace context) is prepended as a [SystemMessage].
     * - [instructions] (agent instructions + integrations + user) and the caller's
     *   [prompt] are both merged into the **last** [UserMessage] of the history,
     *   avoiding consecutive user messages which many providers reject or mishandle.
     * - If the history contains no [UserMessage] yet, a new one is appended so that
     *   neither instructions nor prompt are silently dropped.
     *
     * @param events the accumulated case events to convert into history messages.
     * @param prompt the immediate task prompt for this LLM call (intention, parameter
     *   generation, final response, etc.). Null when the caller needs the base history
     *   without any additional prompt (e.g. confirmation manager calls).
     */
    internal fun buildMessages(
        events: List<CaseEvent>,
        prompt: String? = null,
    ): List<Message> {
        val history = convertEventsToMessages(events)
        val operationalMessage = listOfNotNull(instructions, prompt).joinToString("\n\n").takeUnless { it.isBlank() }
        val messages = if (operationalMessage != null) history + UserMessage(operationalMessage) else history
        return listOfNotNull(systemPrompt?.let { SystemMessage(it) }) + messages
    }

    /**
     * Convert case events to Spring AI messages for the LLM prompt.
     *
     * When the last user [MessageEvent] carries a non-null [MessageEvent.sessionContext],
     * it is injected as a [UserMessage] immediately before that message to maintain the
     * alternating user/assistant pattern expected by most LLM APIs. Session context on
     * earlier messages is ignored.
     */
    internal fun convertEventsToMessages(
        events: List<CaseEvent>,
        maxDetailedChars: Int = 300_000,
    ): List<Message> {
        val responsesByRequestId = indexToolResponses(events)
        val (detailedRequestIds, mediaRequestIds) =
            selectDetailedToolRequestIds(events, responsesByRequestId, maxDetailedChars)

        val lastUserMsgIndex =
            events.indexOfLast {
                it is MessageEvent && it.actor.role == ActorRole.USER
            }

        return events.flatMapIndexed { index, event ->
            when (event) {
                is MessageEvent -> {
                    val sessionContext = event.sessionContextPromptText().takeIf { index == lastUserMsgIndex }
                    val message = event.toSpringAiMessage(this.agentId.toString())
                    if (sessionContext != null && message is UserMessage) {
                        listOf(UserMessage(sessionContext + "\n\n" + message.text))
                    } else {
                        listOfNotNull(sessionContext?.let { UserMessage(it) }, message)
                    }
                }

                is ToolRequestEvent -> {
                    toToolMessages(event, detailedRequestIds, mediaRequestIds, responsesByRequestId)
                }

                is IntentionGeneratedEvent -> {
                    toIntentionMessage(event)
                }

                else -> {
                    emptyList()
                }
            }
        }
    }

    private fun indexToolResponses(events: List<CaseEvent>): Map<String, ToolResponseEvent> =
        events.filterIsInstance<ToolResponseEvent>().associateBy { it.toolRequestId }

    /**
     * Selection of the tool requests replayed in detail, and among them the ones whose
     * response images are actually attached as [Media].
     */
    private data class ToolReplaySelection(
        val detailedRequestIds: Set<String>,
        val mediaRequestIds: Set<String>,
    )

    /**
     * Single newest-first walk deciding both selections coherently:
     * - a request is detailed while its cumulated char cost fits [maxDetailedChars];
     * - its images are attached while they fit [MAX_ATTACHED_IMAGES], and only attached
     *   images are charged [IMAGE_CHAR_COST] against the budget. Responses whose images
     *   are not attached keep their text summary plus a marker (see
     *   [toDetailedToolMessages]) and cost only that text.
     */
    private fun selectDetailedToolRequestIds(
        events: List<CaseEvent>,
        responsesByRequestId: Map<String, ToolResponseEvent>,
        maxDetailedChars: Int,
    ): ToolReplaySelection {
        val detailed = mutableSetOf<String>()
        val withMedia = mutableSetOf<String>()
        var charsCollected = 0
        var imagesAttached = 0

        for (event in events.reversed()) {
            if (event !is ToolRequestEvent) continue

            val response = responsesByRequestId[event.toolRequestId]
            val images = response?.images ?: emptyList()
            val attachMedia = images.isNotEmpty() && imagesAttached + images.size <= MAX_ATTACHED_IMAGES

            val argsCost = event.args?.length ?: 0
            val responseCost = response?.let { extractText(it.output).length } ?: 0
            val imagesCost = if (attachMedia) images.size * IMAGE_CHAR_COST else 0
            val cost = argsCost + responseCost + imagesCost

            if (charsCollected + cost > maxDetailedChars) break

            detailed.add(event.toolRequestId)
            charsCollected += cost
            if (attachMedia) {
                withMedia.add(event.toolRequestId)
                imagesAttached += images.size
            }
        }
        return ToolReplaySelection(detailed, withMedia)
    }

    private fun toToolMessages(
        event: ToolRequestEvent,
        detailedRequestIds: Set<String>,
        mediaRequestIds: Set<String>,
        responsesByRequestId: Map<String, ToolResponseEvent>,
    ): List<Message> =
        if (event.toolRequestId in detailedRequestIds) {
            toDetailedToolMessages(event, mediaRequestIds, responsesByRequestId)
        } else {
            listOf(toToolSummaryMessage(event, responsesByRequestId))
        }

    private fun toDetailedToolMessages(
        event: ToolRequestEvent,
        mediaRequestIds: Set<String>,
        responsesByRequestId: Map<String, ToolResponseEvent>,
    ): List<Message> {
        val args = normalizeArgs(event.args)
        val toolCall =
            AssistantMessage.ToolCall(event.toolRequestId, "function", event.toolName, args)
        val messages = mutableListOf<Message>(AssistantMessage.builder().toolCalls(listOf(toolCall)).build())

        // Every AssistantMessage with tool_calls MUST be followed by a ToolResponseMessage
        // for each tool_call_id — OpenAI returns 400 otherwise. Use the real response if
        // available, or synthesize a placeholder so the message list stays well-formed.
        val response = responsesByRequestId[event.toolRequestId]
        val attachMedia = response != null && response.images.isNotEmpty() && event.toolRequestId in mediaRequestIds
        var responseText = response?.let { extractText(it.output) } ?: "[No response recorded]"
        if (response != null && response.images.isNotEmpty() && !attachMedia) {
            responseText += "\n[${response.images.size} image(s) no longer attached, call the tool again if needed]"
        }

        messages.add(
            ToolResponseMessage
                .builder()
                .responses(
                    listOf(
                        ToolResponseMessage.ToolResponse(
                            event.toolRequestId,
                            event.toolName,
                            responseText,
                        ),
                    ),
                ).build(),
        )
        // Provider tool responses are text-only: the images ride in a follow-up user
        // message with Media attachments right after the tool response.
        if (attachMedia) {
            messages.add(toolImagesUserMessage(event.toolName, response!!.images))
        }
        return messages
    }

    private fun toToolSummaryMessage(
        event: ToolRequestEvent,
        responsesByRequestId: Map<String, ToolResponseEvent>,
    ): Message {
        val response = responsesByRequestId[event.toolRequestId]
        val status =
            when {
                response == null || response.success -> "Success"
                else -> "Failed: ${extractText(response.output)}"
            }
        val imagesSuffix =
            response?.images?.size?.takeIf { it > 0 }?.let { " | $it image(s) (not shown)" } ?: ""
        return AssistantMessage("[Step summary] Tool: ${event.toolName} | $status$imagesSuffix")
    }

    private fun toIntentionMessage(event: IntentionGeneratedEvent): List<Message> =
        listOf(
            AssistantMessage("INTERNAL STEP: Tool Call: ${event.toolName}\nIntention: ${event.intention}"),
        )

    private fun normalizeArgs(args: String?): String = args?.takeIf { it.isNotBlank() } ?: "{}"

    private fun extractText(content: MessageContent): String =
        when (content) {
            is MessageContent.Text -> content.content
            is MessageContent.Image -> "[image ${content.mimeType} ${content.width}x${content.height}]"
        }

    companion object {
        /**
         * Char-equivalent cost of one attached image against the detailed-tool budget.
         * Derived from the legacy Coday estimate: (width * height) / 750 tokens at
         * ~3.5 chars per token, ~4 900 chars for a full-size 1024x1024 image,
         * rounded up to 6 000.
         */
        internal const val IMAGE_CHAR_COST = 6_000

        /** Maximum images attached as Media across the whole prompt, newest first. */
        internal const val MAX_ATTACHED_IMAGES = 20
    }
}
