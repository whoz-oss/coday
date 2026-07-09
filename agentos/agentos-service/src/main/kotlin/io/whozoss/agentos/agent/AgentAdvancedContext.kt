package io.whozoss.agentos.agent

import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.IntentionGeneratedEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.ToolRequestEvent
import io.whozoss.agentos.sdk.caseEvent.ToolResponseEvent
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.util.IdCompressorService
import io.whozoss.agentos.util.MessageCompressorBuffer
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
        compressor: IdCompressorService? = null,
        buffer: MessageCompressorBuffer? = null
    ): List<Message> {
        val history = convertEventsToMessages(events, compressor = compressor, buffer = buffer)
        var operationalMessage = listOfNotNull(instructions, prompt).joinToString("\n\n").takeUnless { it.isBlank() }
        if (compressor != null && buffer != null) {
            operationalMessage = operationalMessage?.let { compressor.compress(it, buffer) }
        }
        val messages = if (operationalMessage != null) history + UserMessage(operationalMessage) else history
        val sysPrompt = if (compressor != null && buffer != null) {
            systemPrompt?.let { compressor.compress(it, buffer) }
        } else {
            systemPrompt
        }
        return listOfNotNull(sysPrompt?.let { SystemMessage(it) }) + messages
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
        compressor: IdCompressorService? = null,
        buffer: MessageCompressorBuffer? = null,
    ): List<Message> {
        val responsesByRequestId = indexToolResponses(events)
        val detailedRequestIds =
            selectDetailedToolRequestIds(events, responsesByRequestId, maxDetailedChars)

        val lastUserMsgIndex =
            events.indexOfLast {
                it is MessageEvent && it.actor.role == ActorRole.USER
            }

        return events.flatMapIndexed { index, event ->
            when (event) {
                is MessageEvent -> {
                    var sessionContext = event.sessionContextPromptText().takeIf { index == lastUserMsgIndex }
                    sessionContext = if (compressor != null && buffer != null && sessionContext != null) compressor.compress(sessionContext, buffer) else sessionContext
                    val message = event.toSpringAiMessage(this.agentId.toString(), compressor, buffer)
                    if (sessionContext != null && message is UserMessage) {
                        listOf(UserMessage(sessionContext + "\n\n" + message.text))
                    } else {
                        listOfNotNull(sessionContext?.let { UserMessage(it) }, message)
                    }
                }

                is ToolRequestEvent -> {
                    toToolMessages(event, detailedRequestIds, responsesByRequestId, compressor, buffer)
                }

                is IntentionGeneratedEvent -> {
                    toIntentionMessage(event, compressor, buffer)
                }

                else -> {
                    emptyList()
                }
            }
        }
    }

    private fun indexToolResponses(events: List<CaseEvent>): Map<String, ToolResponseEvent> =
        events.filterIsInstance<ToolResponseEvent>().associateBy { it.toolRequestId }

    private fun selectDetailedToolRequestIds(
        events: List<CaseEvent>,
        responsesByRequestId: Map<String, ToolResponseEvent>,
        maxDetailedChars: Int,
    ): Set<String> {
        val result = mutableSetOf<String>()
        var charsCollected = 0

        for (event in events.reversed()) {
            if (event !is ToolRequestEvent) continue

            val response = responsesByRequestId[event.toolRequestId]
            val cost = estimateDetailedCharCost(event, response)

            if (charsCollected + cost > maxDetailedChars) break

            result.add(event.toolRequestId)
            charsCollected += cost
        }
        return result
    }

    private fun estimateDetailedCharCost(
        request: ToolRequestEvent,
        response: ToolResponseEvent?,
    ): Int {
        val argsCost = request.args?.length ?: 0
        val responseCost = response?.let { extractText(it.output).length } ?: 0
        return argsCost + responseCost
    }

    private fun toToolMessages(
        event: ToolRequestEvent,
        detailedRequestIds: Set<String>,
        responsesByRequestId: Map<String, ToolResponseEvent>,
        compressor: IdCompressorService?,
        buffer: MessageCompressorBuffer?,
    ): List<Message> =
        if (event.toolRequestId in detailedRequestIds) {
            toDetailedToolMessages(event, responsesByRequestId, compressor, buffer)
        } else {
            listOf(toToolSummaryMessage(event, responsesByRequestId, compressor, buffer))
        }

    private fun toDetailedToolMessages(
        event: ToolRequestEvent,
        responsesByRequestId: Map<String, ToolResponseEvent>,
        compressor: IdCompressorService?,
        buffer: MessageCompressorBuffer?,
    ): List<Message> {
        val args = if (compressor != null && buffer != null) compressor.compress(normalizeArgs(event.args), buffer) else normalizeArgs(event.args)
        val toolCall =
            AssistantMessage.ToolCall(event.toolRequestId, "function", event.toolName, args)
        val messages = mutableListOf<Message>(AssistantMessage.builder().toolCalls(listOf(toolCall)).build())

        // Every AssistantMessage with tool_calls MUST be followed by a ToolResponseMessage
        // for each tool_call_id — OpenAI returns 400 otherwise. Use the real response if
        // available, or synthesize a placeholder so the message list stays well-formed.
        var responseText =
            responsesByRequestId[event.toolRequestId]?.let { extractText(it.output) }
                ?: "[No response recorded]"
        responseText = if (compressor != null && buffer != null) compressor.compress(responseText, buffer) else responseText

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
        return messages
    }

    private fun toToolSummaryMessage(
        event: ToolRequestEvent,
        responsesByRequestId: Map<String, ToolResponseEvent>,
        compressor: IdCompressorService?,
        buffer: MessageCompressorBuffer?,
    ): Message {
        val response = responsesByRequestId[event.toolRequestId]
        var status =
            when {
                response == null || response.success -> "Success"
                else -> "Failed: ${extractText(response.output)}"
            }
        status = if (compressor != null && buffer != null) compressor.compress(status, buffer) else status
        return AssistantMessage("[Step summary] Tool: ${event.toolName} | $status")
    }

    private fun toIntentionMessage(event: IntentionGeneratedEvent, compressor: IdCompressorService?, buffer: MessageCompressorBuffer?): List<Message> {
        val intention = if (compressor != null && buffer != null) compressor.compress(event.intention, buffer) else event.intention
        return listOf(
            AssistantMessage("INTERNAL STEP: Tool Call: ${event.toolName}\nIntention: $intention"),
        )
    }

    private fun normalizeArgs(args: String?): String = args?.takeIf { it.isNotBlank() } ?: "{}"

    private fun extractText(content: MessageContent): String =
        when (content) {
            is MessageContent.Text -> content.content
            else -> content.toString()
        }
}
