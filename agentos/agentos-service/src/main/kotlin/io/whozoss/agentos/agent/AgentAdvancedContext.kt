package io.whozoss.agentos.agent

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.tool.StandardTool
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.UserMessage
import java.util.UUID

data class AgentAdvancedContext(
    val chatClient: ChatClient,
    val tools: List<StandardTool<*>>,
    val instructions: String?,
    val agentId: UUID,
    val confirmationManager: ConfirmationManager? = null,
    val objectMapper: ObjectMapper? = null,
)

/**
 * Build the full message list for an LLM call, prepending system instructions when present.
 */
internal fun AgentAdvancedContext.buildMessages(events: List<CaseEvent>): List<Message> {
    val history = convertEventsToMessages(events)
    return if (instructions != null) listOf(UserMessage(instructions)) + history else history
}

/**
 * Convert CaseEvents to Spring AI Messages for LLM context.
 * Only keeps MessageEvents. Converts other agents' messages to user role for LLM compatibility.
 */
internal fun AgentAdvancedContext.convertEventsToMessages(events: List<CaseEvent>): List<Message> =
    events
        .filterIsInstance<MessageEvent>()
        .map { messageEvent ->
            val textContent =
                messageEvent.content
                    .filterIsInstance<MessageContent.Text>()
                    .joinToString("\n") { it.content }

            when (messageEvent.actor.role) {
                ActorRole.USER -> UserMessage(textContent)
                ActorRole.AGENT -> {
                    if (messageEvent.actor.id == agentId.toString()) {
                        AssistantMessage(textContent)
                    } else {
                        UserMessage("[${messageEvent.actor.displayName}]: $textContent")
                    }
                }
            }
        }
