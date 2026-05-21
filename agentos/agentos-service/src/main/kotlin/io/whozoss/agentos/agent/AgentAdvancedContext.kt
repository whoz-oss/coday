package io.whozoss.agentos.agent

import com.fasterxml.jackson.databind.ObjectMapper
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
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.messages.UserMessage
import java.util.UUID

data class AgentAdvancedContext(
    val chatClient: ChatClient,
    val tools: List<StandardTool<*>>,
    val instructions: String?,
    val agentId: UUID,
    val confirmationManager: ConfirmationManager? = null,
    val objectMapper: ObjectMapper? = null,
) {
    internal fun buildMessages(events: List<CaseEvent>): List<Message> {
        val history = convertEventsToMessages(events)
        return if (instructions != null) history + listOf(UserMessage(instructions)) else history
    }

    internal fun convertEventsToMessages(
        events: List<CaseEvent>,
        maxDetailedToolCalls: Int = 6,
        maxDetailedMessagesWithSteps: Int = 3,
    ): List<Message> {
        val responsesByRequestId = indexToolResponses(events)
        val detailedRequestIds =
            selectDetailedToolRequestIds(events, maxDetailedToolCalls, maxDetailedMessagesWithSteps)

        return events.flatMap { event ->
            when (event) {
                is MessageEvent -> listOf(toMessage(event))
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

    private fun AgentAdvancedContext.toMessage(event: MessageEvent): Message {
        val textContent =
            event.content
                .filterIsInstance<MessageContent.Text>()
                .joinToString("\n") { it.content }

        return when {
            event.actor.role == ActorRole.USER -> UserMessage(textContent)
            event.actor.id == agentId.toString() -> AssistantMessage(textContent)
            else -> UserMessage("[${event.actor.displayName}]: $textContent")
        }
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

        responsesByRequestId[event.toolRequestId]?.let { response ->
            messages.add(
                ToolResponseMessage
                    .builder()
                    .responses(
                        listOf(
                            ToolResponseMessage.ToolResponse(
                                event.toolRequestId,
                                event.toolName,
                                extractText(response.output),
                            ),
                        ),
                    ).build(),
            )
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
        return AssistantMessage("[Step summary] Tool: ${event.toolName} | $status")
    }

    private fun toIntentionMessage(event: IntentionGeneratedEvent): List<Message> =
        listOf(AssistantMessage("[Intention] ${event.intention}\n[Selected tool] ${event.toolName}"))

    private fun normalizeArgs(args: String?): String = args?.takeIf { it.isNotBlank() } ?: "{}"

    private fun extractText(content: MessageContent): String =
        when (content) {
            is MessageContent.Text -> content.content
            else -> content.toString()
        }
}
