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
 * Outcome of [ConfirmationManager.analyzeConfirmation]. Distinguishes the three states
 * the LLM judge can land in:
 *  - [CONFIRMED]: the user clearly authorised the pending action.
 *  - [REJECTED]: the user clearly refused.
 *  - [AMBIGUOUS]: the user reply could not be interpreted as a clear yes/no, OR the LLM
 *    call itself failed. The orchestrator handles both identically: re-ask the user via
 *    [ConfirmationManager.formulateQuestion] IN-CHANNEL and keep the pending alive.
 *
 * Auto-rejecting silently would be poor UX (user re-types "yes" indefinitely);
 * auto-confirming would be unsafe on destructive actions.
 */
enum class ConfirmationDecision { CONFIRMED, REJECTED, AMBIGUOUS }

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
 *   [PendingConfirmationEvent]. Returns a [ConfirmationDecision] (CONFIRMED / REJECTED /
 *   AMBIGUOUS — the third state covers undecodable replies AND LLM call failures).
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
     * @param toolInstructions Optional tool-supplied guidance injected as a structured
     *   `<tool_guidance>` block alongside the general decision rules. The LLM is told to
     *   take it as additional context, not as overriding rules — so a poorly written
     *   guidance cannot bypass the general safety criteria.
     */
    fun shouldConfirm(
        chatClient: ChatClient,
        history: List<Message>,
        actionLabel: String,
        proposedData: Any,
        originalData: Any? = null,
        toolInstructions: String = "",
    ): Boolean {
        logger.info { "[ConfirmationManager] shouldConfirm? actionLabel='$actionLabel'" }
        return try {
            val dataSummary = serializeSafely(proposedData)
            val originalSection = buildOriginalObjectSection(originalData)
            val toolGuidanceSection = buildToolGuidanceSection(toolInstructions)
            val prompt =
                """
                $originalSection
                $toolGuidanceSection

                Current Situation:
                The agent is about to execute the following action:
                Description: $actionLabel

                Proposed changes/new data:
                $dataSummary

                Task:
                Analyze the end of the conversation. Has the user *already* explicitly agreed to or requested this specific action/update?
                Take any tool-specific guidance above as additional context, not as overriding rules — apply both the general criteria below and any specific considerations the tool provided.

                Return "$CHOICE_NO" if ANY of the following are true:
                - The assistant proposed a general suggestion without details and the user just agreed to the general suggestion but hasn't seen the specific details yet
                - There is any ambiguity about whether the user wants to proceed
                - The data content includes changes beyond what the user explicitly and specifically requested or beyond what the assistant has proposed to the user
                - The user's request matched multiple possible targets (e.g. several files match the user's description, a partial name, or a glob/wildcard) and the assistant had to pick one specific target — even a reasonable pick is NOT implicit authorisation, the user must explicitly confirm WHICH target
                - The action is irreversible or destructive (deletion, overwrite, irreversible state change) and the user's last explicit confirmation in the conversation does not name the EXACT same target as the proposed data

                Else return "$CHOICE_YES"

                Has the user *already* explicitly agreed to or requested this specific action/update? put it between <$TAG_DECISION></$TAG_DECISION>:
                <$TAG_DECISION>

                Also give me the reasoning behind yor decision. put it between <$TAG_REASONING></$TAG_REASONING>:
                <$TAG_REASONING>
                """.trimIndent()

            val hasAlreadyConfirmed =
                callDecision(chatClient = chatClient, prompt = prompt, history = history).startsWith(CHOICE_YES)
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

    private fun buildToolGuidanceSection(toolInstructions: String): String =
        if (toolInstructions.isBlank()) {
            ""
        } else {
            """
            |Tool-specific confirmation guidance:
            |<tool_guidance>
            |$toolInstructions
            |</tool_guidance>
            """.trimMargin()
        }

    /**
     * @param chatClient The agent's ChatClient.
     * @param history The Spring AI conversation history that includes the latest user reply.
     * @param pendingPayload The payload that was emitted with the [PendingConfirmationEvent].
     * @param specificInstructions Optional tool-supplied analysis instructions
     *   (e.g. "Be strict: bare 'ok' is not enough for destructive actions").
     * @return [ConfirmationDecision] — CONFIRMED on a clear yes, REJECTED on a clear no,
     *   AMBIGUOUS otherwise (undecodable LLM reply OR LLM call failure).
     */
    fun analyzeConfirmation(
        chatClient: ChatClient,
        history: List<Message>,
        pendingPayload: Any,
        specificInstructions: String,
    ): ConfirmationDecision {
        logger.info { "[ConfirmationManager] Analyze confirmation." }
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
            Based on the given conversation.

            The agent was awaiting confirmation for this pending action:
            $payloadSummary

            And especially based on the last user message, I need to identify if the user confirms the validation (without any modification) or not.
            $specificBlock

            Decide between three outcomes and put exactly one of "$CHOICE_YES", "$CHOICE_NO", "$CHOICE_UNCLEAR" between <$TAG_DECISION></$TAG_DECISION> tags:
            - "$CHOICE_YES" — the user clearly confirms the validation without modification.
            - "$CHOICE_NO" — the user clearly refuses.
            - "$CHOICE_UNCLEAR" — the reply is genuinely ambiguous (off-topic, sarcastic, evasive, or an idiomatic expression that could plausibly be read as either yes or no). Use this when you have to guess.

            <$TAG_DECISION>
            """.trimIndent()

        val decision =
            try {
                callDecision(chatClient = chatClient, prompt = prompt, history = history)
            } catch (e: Exception) {
                logger.warn(e) {
                    "[ConfirmationManager] analyzeConfirmation LLM call failed — treating as AMBIGUOUS"
                }
                return ConfirmationDecision.AMBIGUOUS
            }
        // Lenient matching — the LLM often emits "yes.", "yes please", "no, cancel", etc.
        // `unclear` lands the user reply in AMBIGUOUS (re-ask via formulateQuestion). The
        // `else` catch-all also maps to AMBIGUOUS for gibberish / no-tag responses.
        return when {
            decision.startsWith(CHOICE_YES) -> ConfirmationDecision.CONFIRMED
            decision.startsWith(CHOICE_NO) -> ConfirmationDecision.REJECTED
            decision.startsWith(CHOICE_UNCLEAR) -> ConfirmationDecision.AMBIGUOUS
            else -> ConfirmationDecision.AMBIGUOUS
        }
    }

    /**
     * Formulates a short, user-facing confirmation prompt in the conversation's natural
     * language and register. Out-of-LLM-channel: the result populates [QuestionEvent.question]
     * but never appears as an assistant message in the main turn's history, so it cannot
     * trigger the LLM-re-call after-confirm regression that the v1 placeholder caused.
     *
     * Fails safely to [fallbackLabel] on any error — same philosophy as [shouldConfirm].
     *
     * @param chatClient The agent's ChatClient (model parity with the main turn).
     * @param history The conversation history the LLM should match in tone/language.
     * @param fallbackLabel Deterministic tool-supplied label, used if extraction fails.
     * @param pendingData Structured action data, serialised into the prompt for grounding.
     */
    fun formulateQuestion(
        chatClient: ChatClient,
        history: List<Message>,
        fallbackLabel: String,
        pendingData: Any,
    ): String =
        try {
            logger.info { "[ConfirmationManager] Formulate question." }

            val payloadSummary = serializeSafely(pendingData)
            val prompt =
                """
                You are formulating a short, user-facing confirmation prompt for an action the agent is about to perform.

                You have the conversation context.

                Action label (deterministic, for reference): $fallbackLabel
                Pending data: $payloadSummary

                Write ONE short sentence asking the user to confirm. Match the language and register of the conversation. Do not add alternatives, explanations, or speculation. Reply between <$TAG_QUESTION></$TAG_QUESTION> tags.

                <$TAG_QUESTION>
                """.trimIndent()

            val raw =
                chatClient
                    .prompt(Prompt(history + UserMessage(prompt)))
                    .call()
                    .content()
                    .orEmpty()
            val extracted = sanitizeQuestion(extractTag(raw, QUESTION_TAG_REGEX))
            if (extracted.isBlank()) {
                logger.warn { "[ConfirmationManager] formulateQuestion returned blank, falling back to label" }
                fallbackLabel
            } else {
                logger.info { "[ConfirmationManager] formulated question='$extracted'" }
                extracted
            }
        } catch (e: Exception) {
            logger.warn(e) { "[ConfirmationManager] formulateQuestion failed, falling back to label" }
            fallbackLabel
        }

    private fun callDecision(
        chatClient: ChatClient,
        prompt: String,
        history: List<Message>,
    ): String {
        logger.info { "[ConfirmationManager] Calling LLM to make a decision." }
        logger.debug { "[ConfirmationManager] decision prompt=$prompt" }
        val raw =
            chatClient
                .prompt(Prompt(history + UserMessage(prompt)))
                .call()
                .content()
                .orEmpty()
        logger.debug { "[ConfirmationManager] raw return of call decision=$raw" }
        return extractTag(raw, DECISION_TAG_REGEX).trim().lowercase()
    }

    private fun extractTag(
        raw: String,
        regex: Regex,
    ): String {
        val match = regex.find(raw)
        return match?.groupValues?.get(1) ?: raw
    }

    private fun sanitizeQuestion(s: String): String =
        s
            // Strip orphan opening / closing question tags that leak when the LLM omits one side.
            .replace(Regex("</?$TAG_QUESTION>"), "")
            .replace(Regex("\\p{Cntrl}"), "")
            .trim()
            .take(300)

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

    companion object : KLogging() {
        private const val TAG_DECISION = "decision"
        private const val TAG_QUESTION = "question"
        private const val TAG_REASONING = "reasoning"
        private const val CHOICE_YES = "yes"
        private const val CHOICE_NO = "no"
        private const val CHOICE_UNCLEAR = "unclear"
        private val DECISION_TAG_REGEX = Regex("<decision>(.*?)</decision>", RegexOption.DOT_MATCHES_ALL)
        private val QUESTION_TAG_REGEX = Regex("<question>(.*?)</question>", RegexOption.DOT_MATCHES_ALL)
    }
}
