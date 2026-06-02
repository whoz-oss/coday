package io.whozoss.agentos.agent

import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.IntentionGeneratedEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.ToolRequestEvent
import io.whozoss.agentos.sdk.caseEvent.ToolResponseEvent
import mu.KLogging
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
        val toolNames = context.tools.map { it.name } + ANSWER_TOOL
        val toolsDescription = context.tools.joinToString("\n") { "- ${it.name}: ${it.description}" }

        val isFirstIteration = events.none { it is ToolRequestEvent }
        val lastToolResponse = events.filterIsInstance<ToolResponseEvent>().lastOrNull()
        val executionState =
            when {
                isFirstIteration -> "No tools have been called yet. This is the first iteration."
                lastToolResponse?.success == true -> "Last tool '${lastToolResponse.toolName}' executed without technical issue."
                lastToolResponse?.success == false -> "Last tool '${lastToolResponse.toolName}' FAILED: ${(lastToolResponse.output as? MessageContent.Text)?.content}"
                else -> ""
            }
        val prompt =
            """
Available tools:
$toolsDescription
- $ANSWER_TOOL: produce the final answer to the user (use this when no more tool calls are needed)

$executionState

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


${repetitionWarning ?: ""}

### Output Instructions
You must output **only** the following XML block, (any deviation from the following expected xml would result in a error):

<intention>[A brief and concise rationale justifying the action. Explain "Why" this specific step is necessary right now]</intention>
<toolName>[The exact name of the tool to be called]</toolName>

Now generate the intention explaining your next step, then the toolName for that step.
            """.trimIndent()

        logger.debug { "Intention generation: building messages for LLM" }
        logger.trace { "Intention prompt:\n$prompt" }

        var lastFailure: Pair<String?, AgentIntentionGenerationException>? = null

        repeat(MAX_INTENTION_ATTEMPTS) { attempt ->
            val retryHint = lastFailure?.let { (previousResponse, e) ->
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
                                This does not match the expected XML output that should correspond to the following:
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
                val response = context.chatClient
                    .prompt(Prompt(messages))
                    .call()
                    .content() ?: throw AgentIntentionGenerationException.InvalidFormat("Null LLM response")

                logger.trace { "Intention generation response (attempt ${attempt + 1}):\n$response" }

                val (intention, toolName) = parseIntentionAndTool(response, toolNames)

                return IntentionGeneratedEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    agentId = context.agentId,
                    intention = intention,
                    toolName = toolName,
                )
            } catch (e: AgentIntentionGenerationException) {
                lastFailure = e.response to e
                logger.warn { "Intention generation attempt ${attempt + 1}/$MAX_INTENTION_ATTEMPTS failed: ${e.message}" }
            }
        }

        logger.error { "Intention generation failed after $MAX_INTENTION_ATTEMPTS attempts, falling back to $ANSWER_TOOL" }
        return IntentionGeneratedEvent(
            namespaceId = namespaceId,
            caseId = caseId,
            agentId = context.agentId,
            intention = "Failed to plan next step after $MAX_INTENTION_ATTEMPTS attempts: ${lastFailure?.second?.message}",
            toolName = ANSWER_TOOL,
        )
    }

    internal fun parseIntentionAndTool(
        response: String,
        validToolNames: List<String>,
    ): Pair<String, String> {
        if (response.isBlank()) throw AgentIntentionGenerationException.InvalidFormat("Empty LLM response")
        val rawTool = extractFromTag(response, "toolName")
            ?: throw AgentIntentionGenerationException.InvalidFormat("Missing <toolName> tag", response)
        val intention = extractFromTag(response, "intention")
            ?: throw AgentIntentionGenerationException.InvalidFormat("Missing <intention> tag", response)
        val toolName = validToolNames.firstOrNull { it.equals(rawTool.trim(), ignoreCase = true) }
            ?: throw AgentIntentionGenerationException.UnknownTool(rawTool.trim(), response)
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
        private const val MAX_INTENTION_ATTEMPTS = 3
    }
}
