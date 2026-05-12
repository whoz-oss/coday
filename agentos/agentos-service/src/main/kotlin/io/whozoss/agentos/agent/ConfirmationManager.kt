package io.whozoss.agentos.agent

import com.fasterxml.jackson.databind.ObjectMapper
import mu.KLogging
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.stereotype.Service

/**
 * Thrown by [ConfirmationManager.analyzeConfirmation] when the LLM response cannot be
 * interpreted as a clear yes/no. The orchestrator handles this as a third state: keep
 * the [io.whozoss.agentos.sdk.caseEvent.PendingConfirmationEvent] alive and emit a
 * [io.whozoss.agentos.sdk.caseEvent.WarnEvent] asking the user to rephrase.
 *
 * Auto-rejecting silently would be poor UX (user re-types "yes" indefinitely);
 * auto-confirming would be unsafe on destructive actions.
 */
class AmbiguousConfirmationException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Backs the WZ-31596 confirmation flow ported from the Whoz Copilot back.
 *
 * Two operations:
 * - [shouldConfirm] is called BEFORE a tool's side-effect. Returns `true` when an
 *   explicit confirmation is needed. Returns `false` only when the LLM is confident
 *   the user has already authorized this exact action — letting the orchestrator
 *   execute directly and avoid a redundant prompt (this is the UX pillar Yannick
 *   highlighted in the 2026-05-12 meeting: "tu es largement moins souvent emmerdé").
 *   Fail-safe on any error = `true` (force confirmation).
 *
 * - [analyzeConfirmation] interprets the last user message against the
 *   [PendingConfirmationEvent]. Returns `true`/`false` for confirm/reject, throws
 *   [AmbiguousConfirmationException] when neither is clearly identifiable.
 *
 * Both methods use the same Spring AI [ChatClient] the agent is using for the main
 * turn, so the analysis runs on the same model.
 */
@Service
class ConfirmationManager(
    private val objectMapper: ObjectMapper,
) {
    /**
     * @param chatClient The agent's ChatClient (same model parity as the main turn).
     * @param history The Spring AI conversation history to inspect. Stringified into the
     *   prompt — kept as `List<Message>` for caller convenience.
     * @param actionLabel Short human-readable description of the action ("Delete file X").
     * @param proposedData The structured data the tool is about to apply.
     * @param originalData Optional pre-change state (Update tools). When non-null, the
     *   LLM gets it so it can reason about the delta — matches the back Copilot signature.
     */
    fun shouldConfirm(
        chatClient: ChatClient,
        history: List<Message>,
        actionLabel: String,
        proposedData: Any,
        originalData: Any? = null,
    ): Boolean {
        logger.info { "[ConfirmationManager] shouldConfirm? actionLabel='$actionLabel'" }
        return try {
            val dataSummary = serializeSafely(proposedData)
            val originalSection = buildOriginalObjectSection(originalData)
            val prompt =
                """
                $originalSection

                Current Situation:
                The agent is about to execute the following action:
                Description: $actionLabel

                Proposed changes/new data:
                $dataSummary

                Task:
                Analyze the end of the conversation. Has the user *already* explicitly agreed to or requested this specific action/update?

                Conversation History:
                ${historyToString(history).putBetween(TAG_CONVERSATION)}

                Return "$CHOICE_NO" if ANY of the following are true:
                - The assistant proposed a general suggestion without details and the user just agreed to the general suggestion but hasn't seen the specific details yet
                - There is any ambiguity about whether the user wants to proceed
                - The data content includes changes beyond what the user explicitly and specifically requested or beyond what the assistant has proposed to the user

                Else return "$CHOICE_YES"

                Has the user *already* explicitly agreed to or requested this specific action/update? put it between <$TAG_DECISION></$TAG_DECISION>:
                <$TAG_DECISION>
                """.trimIndent()

            val hasAlreadyConfirmed = callDecision(chatClient, prompt) == CHOICE_YES
            if (hasAlreadyConfirmed) {
                logger.info { "[ConfirmationManager] LLM says user already confirmed — skipping prompt" }
            } else {
                logger.info { "[ConfirmationManager] LLM requires explicit confirmation" }
            }
            !hasAlreadyConfirmed
        } catch (e: Exception) {
            logger.warn(e) { "[ConfirmationManager] shouldConfirm failed, falling back to requiring confirmation" }
            true
        }
    }

    /**
     * @param chatClient The agent's ChatClient.
     * @param history The Spring AI conversation history that includes the latest user reply.
     * @param pendingPayload The payload that was emitted with the [PendingConfirmationEvent].
     * @param specificInstructions Optional tool-supplied analysis instructions
     *   (e.g. "Be strict: bare 'ok' is not enough for destructive actions").
     * @return `true` if confirmed, `false` if rejected.
     * @throws AmbiguousConfirmationException when the LLM reply cannot be decoded.
     */
    fun analyzeConfirmation(
        chatClient: ChatClient,
        history: List<Message>,
        pendingPayload: Any,
        specificInstructions: String,
    ): Boolean {
        val specificBlock =
            if (specificInstructions.isNotBlank()) {
                """
                |**Specific Context for this validation:**
                |$specificInstructions
                """.trimMargin()
            } else {
                ""
            }

        val payloadSummary = serializeSafely(pendingPayload)

        val prompt =
            """
            Based on the following conversation:
            ${historyToString(history).putBetween(TAG_CONVERSATION)}

            The agent was awaiting confirmation for this pending action:
            $payloadSummary

            And especially based on the last user message, I need to identify if the user confirms the validation (without any modification) or not.
            $specificBlock

            If the user confirms without requiring any modification, I'll put yes between <$TAG_DECISION></$TAG_DECISION> tags, if not then no:

            <$TAG_DECISION>
            """.trimIndent()

        val decision =
            try {
                callDecision(chatClient, prompt)
            } catch (e: Exception) {
                throw AmbiguousConfirmationException("Failed to interpret confirmation reply: ${e.message}", e)
            }
        return when (decision) {
            CHOICE_YES -> true
            CHOICE_NO -> false
            else -> throw AmbiguousConfirmationException("LLM returned ambiguous decision: '$decision'")
        }
    }

    private fun callDecision(
        chatClient: ChatClient,
        prompt: String,
    ): String {
        val raw =
            chatClient
                .prompt(Prompt(UserMessage(prompt)))
                .call()
                .content()
                .orEmpty()
        return extractDecision(raw).trim().lowercase()
    }

    private fun extractDecision(raw: String): String {
        val match = DECISION_TAG_REGEX.find(raw)
        return match?.groupValues?.get(1) ?: raw
    }

    private fun serializeSafely(data: Any): String =
        try {
            objectMapper.writeValueAsString(data)
        } catch (e: Exception) {
            logger.warn { "[ConfirmationManager] data ${data.javaClass.name} not serializable: ${e.message}" }
            "Data available but not serializable"
        }

    private fun buildOriginalObjectSection(originalObject: Any?): String {
        if (originalObject == null) return ""
        val originalSummary = serializeSafely(originalObject)
        return """
            |
            |Original object (before changes):
            |$originalSummary
            """.trimMargin()
    }

    /**
     * Flattens a Spring AI [Message] list into a plain-text transcript that the LLM can
     * scan as "conversation history". Keeps only Role + textual content; non-text content
     * is left as `toString()`.
     */
    private fun historyToString(history: List<Message>): String =
        history.joinToString(separator = "\n") { msg ->
            val role =
                when (msg) {
                    is UserMessage -> "user"
                    is AssistantMessage -> "assistant"
                    is SystemMessage -> "system"
                    else -> msg.messageType.value
                }
            "$role: ${msg.text ?: msg.toString()}"
        }

    private fun String.putBetween(tag: String): String = "\n<$tag>\n${this.trim()}\n</$tag>\n"

    companion object : KLogging() {
        private const val TAG_DECISION = "decision"
        private const val TAG_CONVERSATION = "conversation"
        private const val CHOICE_YES = "yes"
        private const val CHOICE_NO = "no"
        private val DECISION_TAG_REGEX = Regex("<decision>(.*?)</decision>", RegexOption.DOT_MATCHES_ALL)
    }
}
