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
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import java.util.UUID

data class AgentAdvancedContext(
    val chatClient: ChatClient,
    val tools: List<StandardTool<*>>,
    val instructions: String?,
    val agentId: UUID,
)

/**
 * Build the full message list for an LLM call, prepending system instructions when present.
 */
internal fun AgentAdvancedContext.buildMessages(events: List<CaseEvent>): List<Message> {
    val history = convertEventsToMessages(events)
    return if (instructions != null) listOf(SystemMessage(instructions)) + history else history
}

/**
 * Convert CaseEvents to Spring AI Messages for LLM context.
 * Handles MessageEvent, ToolRequestEvent, ToolResponseEvent, and IntentionGeneratedEvent.
 * Converts other agents' messages to user role for LLM compatibility.
 */
internal fun AgentAdvancedContext.convertEventsToMessages(events: List<CaseEvent>): List<Message> {
    val messages = mutableListOf<Message>()
    val pendingToolCalls = mutableListOf<AssistantMessage.ToolCall>()
    val toolResponses = mutableMapOf<String, ToolResponseEvent>()

    events.filterIsInstance<ToolResponseEvent>().forEach { toolResponses[it.toolRequestId] = it }

    fun flushPendingToolCalls() {
        if (pendingToolCalls.isEmpty()) return
        messages.add(AssistantMessage.builder().toolCalls(pendingToolCalls.toList()).build())
        val responses = pendingToolCalls.mapNotNull { toolCall ->
            toolResponses[toolCall.id()]?.let { response ->
                val output = when (val content = response.output) {
                    is MessageContent.Text -> content.content
                    else -> content.toString()
                }
                ToolResponseMessage.ToolResponse(toolCall.id(), toolCall.name(), output)
            }
        }
        if (responses.isNotEmpty()) {
            messages.add(ToolResponseMessage.builder().responses(responses).build())
        }
        pendingToolCalls.clear()
    }

    for (event in events) {
        when (event) {
            is MessageEvent -> {
                flushPendingToolCalls()
                val textContent = event.content
                    .filterIsInstance<MessageContent.Text>()
                    .joinToString("\n") { it.content }
                when (event.actor.role) {
                    ActorRole.USER -> messages.add(UserMessage(textContent))
                    ActorRole.AGENT -> {
                        if (event.actor.id == agentId.toString()) {
                            messages.add(AssistantMessage(textContent))
                        } else {
                            messages.add(UserMessage("[${event.actor.displayName}]: $textContent"))
                        }
                    }
                }
            }
            is ToolRequestEvent -> {
                val safeArgs = event.args?.takeIf { it.isNotBlank() } ?: "{}"
                pendingToolCalls.add(AssistantMessage.ToolCall(event.toolRequestId, "function", event.toolName, safeArgs))
            }
            is IntentionGeneratedEvent -> {
                flushPendingToolCalls()
                messages.add(AssistantMessage("[Intention] ${event.intention}\n[Selected tool] ${event.toolName}"))
            }
            else -> {}
        }
    }

    flushPendingToolCalls()
    return messages
}
