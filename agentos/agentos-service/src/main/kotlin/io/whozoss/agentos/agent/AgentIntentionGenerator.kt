package io.whozoss.agentos.agent

import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.AnswerEvent
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.IntentionGeneratedEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.ToolRequestEvent
import io.whozoss.agentos.sdk.caseEvent.ToolResponseEvent
import io.whozoss.agentos.util.AttemptFailure
import io.whozoss.agentos.util.AttemptSuccess
import io.whozoss.agentos.util.retryWithFallback
import mu.KLogging
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class AgentIntentionGenerator {
    fun generate(
        context: AgentAdvancedContext,
        chatClient: ChatClient,
        events: List<CaseEvent>,
        namespaceId: UUID,
        caseId: UUID,
        repetitionWarning: String? = null,
    ): IntentionGeneratedEvent {
        val toolNames = context.tools.map { it.name } + ANSWER_TOOL
        val toolsDescription = context.tools.joinToString("\n") { "- ${it.name}: ${it.description}" }

        val isFirstIteration = events.none { it is ToolRequestEvent }
        val lastToolResponse = events.filterIsInstance<ToolResponseEvent>().lastOrNull()
        val lastToolRequestIndex = events.indexOfLast { it is ToolRequestEvent }
        val lastUserInteractionAfterLastToolCall = events
            .drop(lastToolRequestIndex + 1)
            .lastOrNull { event -> event is AnswerEvent || (event is MessageEvent && event.actor.role == ActorRole.USER) }
        val executionState =
            when {
                isFirstIteration -> "No tools have been called yet. This is the first iteration."
                lastUserInteractionAfterLastToolCall is AnswerEvent -> "The user has just answered a question from the agent. Determine the next action based on their answer."
                lastUserInteractionAfterLastToolCall is MessageEvent -> "The user has just sent a new message. Determine the next action based on their message."
                lastToolResponse?.success == true -> "Last tool '${lastToolResponse.toolName}' executed without technical issue."
                lastToolResponse?.success == false -> "Last tool '${lastToolResponse.toolName}' FAILED: ${(lastToolResponse.output as? MessageContent.Text)?.content}"
                else -> ""
            }
        val prompt =
            """
Available agents and tools:
$toolsDescription
- $ANSWER_TOOL: produce the final answer to the user (use this when no more tool calls are needed)

$executionState

### Objective
Based on the full conversation history and current context, your objective is to determine the single most appropriate **next action**.

### Reasoning Guidelines
Before generating the output, analyze the situation using the following logic:

**1. Analyze Context & Execution State:**
*   Determine the subject of the user's request: Something currently visible to them (provided via <session-context>), something mentioned in previous messages, something that needs to be retrieved or searched...
*   Check the last tool execution. Did it succeed?
    *   **Yes:** What is the logical sequential step?
    *   **No/Missing Info:** If the tool failed and required more data, the next step is `${ANSWER_TOOL}` to ask for clarification.
    *   **Goal Met:** If the `userGoal` is fully satisfied, use `${ANSWER_TOOL}` to confirm completion.
    *   **Warning:** If there a warning, should it be passed on to the user 
*   Verify you have the capabilities or the available agents (<AvailableAgents></AvailableAgents>) have the capabilities to execute the tool if not say so to the user.

**2. Validate Agent Constraints:**
*   Review the **Current Active Agent's** workflow and instructions for guidance on next step.
*   Check for prohibitions. If a restriction blocks the user's request, your action is `${ANSWER_TOOL}` to explain why.

**3. Verify Capabilities (Agent Handoff):**
*   Does the **Current Active Agent** possess the tool required for the next action?
    *   **NO:** The next action must be to switch to the correct agent and if none can do the action to use `${ANSWER_TOOL}`.
    *   **YES:** Proceed to the next check.

**4. Check Data Prerequisites:**
*   Does the intended tool require specific IDs or context ?
*   Have these entities been referenced previously?
    *   **NO:** The next action is `FindXXX` to reference the entity (`FindXXX` establishes a reference to an entity by identifying the corresponding id).
    *   **YES:** You are ready to call the execution tool.

**Non-discrimination safeguard:**
Do not plan steps that would discriminate based on gender, ethnicity, religion, age, physical appearance, or any other protected attribute. If the user's request implies such a step, your next action must be `Answer` — clarify with the user that this cannot be done.

${repetitionWarning ?: ""}

### Output Instructions
You must respond with **exactly** this XML structure and **nothing else** — no prose, no markdown fences, no preamble (any deviation from the following expected xml would result in a error):

<intention>[A brief and concise rationale justifying the action. Explain "Why" this specific step is necessary right now. Stay high-level and general — do not invent, assume, or include any technical details (field names, IDs, values, tool parameters) that are not explicitly present in the conversation history.]</intention>
<toolName>[The exact name of the tool to be called]</toolName>

You MUST start with the `<intention>` tag first, and THEN `<toolName>`. Do not reorder them.
Do not wrap in code blocks. Do not add any text before or after the XML.
            """.trimIndent()

        logger.debug { "Intention generation: building messages for LLM" }
        logger.trace { "Intention prompt:\n$prompt" }

        return retryWithFallback<Pair<String?, AgentIntentionGenerationException>, IntentionGeneratedEvent>(
            maxAttempts = MAX_INTENTION_ATTEMPTS,
            fallback = { (_, lastException) ->
                logger.error { "Intention generation failed after $MAX_INTENTION_ATTEMPTS attempts, falling back to $ANSWER_TOOL" }
                IntentionGeneratedEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    agentId = context.agentId,
                    intention = "Failed to plan next step after $MAX_INTENTION_ATTEMPTS attempts: ${lastException.message}",
                    toolName = ANSWER_TOOL,
                    isFailedIntention = true,
                )
            },
        ) { previousFailure ->
            val retryHint = previousFailure?.let { (previousResponse, e) ->
                buildString {
                    if (previousResponse != null) {
                        appendLine("You previously generated:")
                        appendLine(previousResponse)
                        appendLine()
                    }
                    when (e) {
                        is AgentIntentionGenerationException.InvalidFormat ->
                            append(
                                """
                                This does not match the expected XML output (${e.message}) that should correspond to the following:
                                <intention>[A brief and concise rationale justifying the action. Explain "Why" this specific step is necessary right now]</intention>
                                <toolName>[The exact name of the tool to be called]</toolName>
                                """.trimIndent()
                            )
                        is AgentIntentionGenerationException.UnknownTool ->
                            append("This does not match the expected XML output because the tool '${e.toolName}' does not exist. Valid tools are: ${toolNames.joinToString()}")
                    }
                    appendLine()
                    append("Correct the error and output only the valid XML block described above.")
                }.trim()
            }
            val fullPrompt = listOfNotNull(prompt, retryHint).joinToString("\n\n")

            try {
                val messages = context.buildMessages(events, fullPrompt)
                val response = chatClient.prompt(Prompt(messages)).call().content()
                    ?: throw AgentIntentionGenerationException.InvalidFormat("Null LLM response")

                logger.trace { "Intention generation response:\n$response" }

                val (intention, toolName) = parseIntentionAndTool(response, toolNames)
                AttemptSuccess(
                    IntentionGeneratedEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        agentId = context.agentId,
                        intention = intention,
                        toolName = toolName,
                    )
                )
            } catch (e: AgentIntentionGenerationException) {
                logger.warn { "Intention generation attempt failed: ${e.message}" }
                AttemptFailure(e.response to e)
            }
        }
    }

    internal fun parseIntentionAndTool(
        response: String,
        validToolNames: List<String>,
    ): Pair<String, String> {
        if (response.isBlank()) throw AgentIntentionGenerationException.InvalidFormat("Empty LLM response")
        try {
            val rawTool = extractFromUniqueTag(response, "toolName")
            val intention = extractFromUniqueTag(response, "intention")
            val toolName = validToolNames.firstOrNull { it.equals(rawTool.trim(), ignoreCase = true) }
                ?: throw AgentIntentionGenerationException.UnknownTool(rawTool.trim(), response)
            return intention to toolName
        } catch (e: IllegalArgumentException) {
            throw AgentIntentionGenerationException.InvalidFormat(e.message ?: "Invalid intention format", response)
        }
    }

    private fun extractFromUniqueTag(input: String, tag: String): String {
        val matches = Regex("""<$tag>(.*?)</$tag>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(input)
            .toList()
        if (matches.size > 1) throw IllegalArgumentException("Multiple <$tag> tags found")
        return matches.firstOrNull()?.groupValues?.get(1)?.trim() ?: throw IllegalArgumentException("Missing <$tag> tag")
    }

    companion object : KLogging() {
        const val ANSWER_TOOL = "Answer"
        private const val MAX_INTENTION_ATTEMPTS = 3
    }
}
