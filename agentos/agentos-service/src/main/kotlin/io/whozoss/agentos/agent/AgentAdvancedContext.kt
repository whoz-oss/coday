package io.whozoss.agentos.agent

import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.IntentionGeneratedEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.SessionContextEvent
import io.whozoss.agentos.sdk.caseEvent.ToolRequestEvent
import io.whozoss.agentos.sdk.caseEvent.ToolResponseEvent
import io.whozoss.agentos.sdk.tool.StandardTool
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.messages.UserMessage
import java.util.UUID

data class AgentAdvancedContext(
    val chatClient: ChatClient,
    val tools: List<StandardTool<*>>,
    val instructions: String?,
    val agentId: UUID,
) {
    internal fun buildMessages(events: List<CaseEvent>): List<Message> {
        val history = convertEventsToMessages(events)
        return if (instructions != null) history + listOf(UserMessage(instructions)) else history
    }

    /**
     * Convert case events to Spring AI messages for the LLM prompt.
     *
     * The most recent [SessionContextEvent] preceding the last user [MessageEvent]
     * is injected as a [UserMessage] immediately before that message, as a synthetic
     * assistant-turn boundary to maintain the alternating user/assistant pattern
     * expected by most LLM APIs. All earlier [SessionContextEvent]s are ignored.
     */
    internal fun convertEventsToMessages(
        events: List<CaseEvent>,
        maxDetailedToolCalls: Int = 6,
        maxDetailedMessagesWithSteps: Int = 3,
    ): List<Message> {
        val responsesByRequestId = indexToolResponses(events)
        val detailedRequestIds =
            selectDetailedToolRequestIds(events, maxDetailedToolCalls, maxDetailedMessagesWithSteps)

        // Identify the most recent SessionContextEvent preceding the last user MessageEvent.
        val lastUserMsgIndex = events.indexOfLast {
            it is MessageEvent && (it as MessageEvent).actor.role == io.whozoss.agentos.sdk.actor.ActorRole.USER
        }
        val sessionContextToInject: SessionContextEvent? = if (lastUserMsgIndex > 0) {
            events.subList(0, lastUserMsgIndex).filterIsInstance<SessionContextEvent>().lastOrNull()
        } else null

        return events.flatMapIndexed { index, event ->
            when (event) {
                is MessageEvent -> {
                    // For Advanced, inject context as a UserMessage before the last user message.
                    // This preserves the user/assistant alternation expected by the LLM.
                    val prefix: List<Message> = if (index == lastUserMsgIndex && sessionContextToInject != null) {
                        listOf(UserMessage(sessionContextToInject.toPromptText()))
                    } else emptyList()
                    prefix + listOf(event.toSpringAiMessage(this.agentId.toString()))
                }
                is ToolRequestEvent -> toToolMessages(event, detailedRequestIds, responsesByRequestId)
                is IntentionGeneratedEvent -> toIntentionMessage(event)
                else -> emptyList()
            }
        }
    }

    private fun indexToolResponses(events: List<CaseEvent>): Map<String, ToolResponseEvent> =
        events.filterIsInstance<ToolResponseEvent>().associateBy { it.toolRequestId }

    private fun selectDetailedToolRequestIds(
        events: List<CaseEvent>,
        maxPairs: Int,
        maxTurns: Int,
    ): Set<String> {
        val result = mutableSetOf<String>()
        var pairsCollected = 0
        var turnsCollected = 0
        var inTurn = false

        for (event in events.reversed()) {
            if (pairsCollected >= maxPairs || turnsCollected >= maxTurns) break
            when (event) {
                is ToolRequestEvent -> {
                    if (!inTurn) {
                        turnsCollected++
                        inTurn = true
                    }
                    result.add(event.toolRequestId)
                    pairsCollected++
                }

                is MessageEvent, is IntentionGeneratedEvent -> {
                    inTurn = false
                }

                else -> {}
            }
        }
        return result
    }

    private fun toToolMessages(
        event: ToolRequestEvent,
        detailedRequestIds: Set<String>,
        responsesByRequestId: Map<String, ToolResponseEvent>,
    ): List<Message> =
        if (event.toolRequestId in detailedRequestIds) {
            toDetailedToolMessages(event, responsesByRequestId)
        } else {
            listOf(toToolSummaryMessage(event, responsesByRequestId))
        }

    private fun toDetailedToolMessages(
        event: ToolRequestEvent,
        responsesByRequestId: Map<String, ToolResponseEvent>,
    ): List<Message> {
        val toolCall =
            AssistantMessage.ToolCall(event.toolRequestId, "function", event.toolName, normalizeArgs(event.args))
        val messages = mutableListOf<Message>(AssistantMessage.builder().toolCalls(listOf(toolCall)).build())

        // Every AssistantMessage with tool_calls MUST be followed by a ToolResponseMessage
        // for each tool_call_id — OpenAI returns 400 otherwise. Use the real response if
        // available, or synthesize a placeholder so the message list stays well-formed.
        val responseText =
            responsesByRequestId[event.toolRequestId]?.let { extractText(it.output) }
                ?: "[No response recorded]"
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
    ): Message {
        val response = responsesByRequestId[event.toolRequestId]
        val status =
            when {
                response == null || response.success -> "Success"
                else -> "Failed: ${extractText(response.output)}"
            }
        return AssistantMessage("[Step summary] Tool: ${event.toolName} | $status")
    }

    private fun toIntentionMessage(event: IntentionGeneratedEvent): List<Message> =
        listOf(
            AssistantMessage("INTERNAL STEP: Tool Call: ${event.toolName}\nIntention: ${event.intention}"),
        )

    private fun normalizeArgs(args: String?): String = args?.takeIf { it.isNotBlank() } ?: "{}"

    private fun extractText(content: MessageContent): String =
        when (content) {
            is MessageContent.Text -> content.content
            else -> content.toString()
        }
}
