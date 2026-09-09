package io.whozoss.agentos.agent

import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.AnswerEvent
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.IntentionGeneratedEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.QuestionEvent
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
    /**
     * Char-equivalent cost of one attached image against the detailed-tool budget.
     * Default mirrors [AgentConfigProperties.imageCharCost].
     */
    val imageCharCost: Int = 6_000,
    /** Maximum images attached as Media across the whole prompt, newest first. Default mirrors [AgentConfigProperties.maxAttachedImages]. */
    val maxAttachedImages: Int = 20,
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
        val plan =
            ToolReplayPlanner(
                maxDetailedChars = maxDetailedChars,
                maxAttachedImages = maxAttachedImages,
                imageCharCost = imageCharCost,
            ).plan(events)

        val lastUserMsgIndex =
            events.indexOfLast {
                it is MessageEvent && it.actor.role == ActorRole.USER
            }

        val responsesByRequestId = events.filterIsInstance<ToolResponseEvent>().associateBy { it.toolRequestId }

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
                    toToolMessages(
                        event = event,
                        plan = plan,
                        responsesByRequestId = responsesByRequestId,
                    )
                }

                is IntentionGeneratedEvent -> {
                    toIntentionMessage(event)
                }

                // QuestionEvent: the agent asked the user a question and terminated its run.
                // Rendered as AssistantMessage (the agent's own voice) so the LLM sees its
                // own question in the history when it resumes after the answer.
                // No XML balisage is applied — the question is plain agent speech, not a
                // "foreign" message, so the same convention as other AssistantMessages holds.
                is QuestionEvent -> {
                    listOf(AssistantMessage(event.toPromptText()))
                }

                // AnswerEvent: the user's reply to a QuestionEvent.
                // Rendered as UserMessage so the LLM sees it as the user speaking,
                // consistent with MessageEvent USER rendering in toSpringAiMessage.
                is AnswerEvent -> {
                    listOf(UserMessage(event.answer))
                }

                else -> {
                    emptyList()
                }
            }
        }
    }

    private fun toToolMessages(
        event: ToolRequestEvent,
        plan: ToolReplayPlan,
        responsesByRequestId: Map<String, ToolResponseEvent>,
    ): List<Message> =
        if (plan.isDetailed(event.toolRequestId)) {
            toDetailedToolMessages(event, plan, responsesByRequestId)
        } else {
            listOf(toToolSummaryMessage(event, responsesByRequestId))
        }

    private fun toDetailedToolMessages(
        event: ToolRequestEvent,
        plan: ToolReplayPlan,
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
        val responseText = response?.let { toolResponseText(it, plan) } ?: "[No response recorded]"

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
        if (response != null && plan.hasAttachedMedia(event.toolRequestId)) {
            messages.add(toolImagesUserMessage(event.toolName, response.images))
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
}
