package io.whozoss.agentos.agent

import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.IntentionGeneratedEvent
import mu.KLogging
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class AgentIntentionGenerator {
    fun generate(
        context: AgentAdvancedContext,
        events: List<CaseEvent>,
        namespaceId: UUID,
        caseId: UUID,
        repetitionWarning: String? = null,
    ): IntentionGeneratedEvent {
        val messages = context.buildMessages(events)
        val toolNames = context.tools.map { it.name } + ANSWER_TOOL
        val toolsDescription = context.tools.joinToString("\n") { "- ${it.name}: ${it.description}" }

        val prompt =
            """
Available tools:
$toolsDescription
- $ANSWER_TOOL: produce the final answer to the user (use this when no more tool calls are needed)

### Objective
Based on the full conversation history and current context, your objective is to determine the single most appropriate **next action**.

### Reasoning Guidelines
Before generating the output, analyze the situation using the following logic:

**1. Analyze Context & Execution State:**
*   Check the last tool execution. Did it succeed?
    *   **Yes:** What is the logical sequential step?
    *   **No/Missing Info:** If the tool failed and required more data, the next step is `${ANSWER_TOOL}` to ask for clarification.
    *   **Goal Met:** If the `userGoal` is fully satisfied, use `${ANSWER_TOOL}` to confirm completion.
    *   **Warning:** If there a warning, should it be passed on to the user 

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
    *   **NO:** The next action is `ReferencedXXX` to fetch the data.
    *   **YES:** You are ready to call the execution tool.


$repetitionWarning

### Output Instructions
You must output **only** the following XML block containing the results of your analysis:

<intention>[A brief and concise rationale justifying the action. Explain "Why" this specific step is necessary right now]</intention>
<toolName>[The exact name of the tool to be called]</toolName>
            """.trimIndent()

        var lastException: Exception? = null
        var lastResponse: String? = null

        logger.debug { "Intention generation: sending ${messages.size + 1} messages to LLM" }
        logger.trace { "Intention prompt:\n$prompt" }

        repeat(MAX_INTENTION_ATTEMPTS) { attempt ->
            try {
                val response =
                    context.chatClient
                        .prompt(Prompt(messages + UserMessage(prompt)))
                        .call()
                        .content() ?: throw AgentIntentionGenerationException("Null LLM response")

                logger.trace { "Intention generation response:\n$response" }
                lastResponse = response
                val (intention, toolName) = parseIntentionAndTool(response, toolNames)

                return IntentionGeneratedEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    agentId = context.agentId,
                    intention = intention,
                    toolName = toolName,
                )
            } catch (e: Exception) {
                lastException = e
                if (attempt < MAX_INTENTION_ATTEMPTS - 1) {
                    logger.warn { "Intention generation attempt ${attempt + 1} failed: ${e.message}, retrying..." }
                }
            }
        }

        logger.warn {
            "Intention generation failed after $MAX_INTENTION_ATTEMPTS " +
                "attempts: ${lastException?.message}, falling back to $ANSWER_TOOL" +
                (lastResponse?.let { "\nLast LLM response was:\n$it" } ?: "")
        }
        return IntentionGeneratedEvent(
            namespaceId = namespaceId,
            caseId = caseId,
            agentId = context.agentId,
            intention = lastResponse?.trim() ?: "Unable to generate intention",
            toolName = ANSWER_TOOL,
        )
    }

    internal fun parseIntentionAndTool(
        response: String,
        validToolNames: List<String>,
    ): Pair<String, String> {
        if (response.isBlank()) throw AgentIntentionGenerationException("Empty LLM response")
        val rawTool =
            extractFromTag(response, "toolName") ?: throw AgentIntentionGenerationException("Missing <toolName> tag in LLM response")
        val intention = extractFromTag(response, "intention") ?: response.trim()
        val toolName = validToolNames.firstOrNull { it.equals(rawTool.trim(), ignoreCase = true) } ?: ANSWER_TOOL
        return intention to toolName
    }

    private fun extractFromTag(
        input: String,
        tag: String,
    ): String? =
        Regex("""<$tag>(.*?)</$tag>""", RegexOption.DOT_MATCHES_ALL)
            .find(input)
            ?.groupValues
            ?.get(1)
            ?.trim()

    companion object : KLogging() {
        const val ANSWER_TOOL = "Answer"
        private const val MAX_INTENTION_ATTEMPTS = 2
    }
}
