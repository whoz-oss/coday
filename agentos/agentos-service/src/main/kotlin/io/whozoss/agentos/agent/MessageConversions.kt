package io.whozoss.agentos.agent

import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.UserMessage

/**
 * Convert a [MessageEvent] to a Spring AI [Message], as seen from the perspective of [currentAgentId].
 *
 * Role assignment:
 * - USER messages → [UserMessage] wrapped in `<user name="...">` XML tag so the LLM can
 *   distinguish human messages from agent messages in multi-agent conversations.
 * - AGENT messages from the current agent → [AssistantMessage] (unchanged — this is the LLM's own voice).
 * - AGENT messages from other agents → [UserMessage] wrapped in `<agent=Name>` XML tag,
 *   following the same convention as Coday's `convertAgentMessages`. The LLM sees them as
 *   user-role messages but can identify their origin from the tag.
 *
 * The XML tagging convention (`<agent=Name>`, `<user name="...">`) is deliberately kept
 * consistent with the Coday `AiClient.convertAgentMessages` implementation.
 */
internal fun MessageEvent.toSpringAiMessage(currentAgentId: String): Message {
    val textContent =
        content
            .filterIsInstance<MessageContent.Text>()
            .joinToString("\n") { it.content }

    return when (actor.role) {
        ActorRole.USER ->
            UserMessage("<user name=\"${actor.displayName}\">$textContent</user>")

        ActorRole.AGENT ->
            if (actor.id == currentAgentId) {
                AssistantMessage(textContent)
            } else {
                UserMessage("<agent=${actor.displayName}>$textContent</agent>")
            }
    }
}
