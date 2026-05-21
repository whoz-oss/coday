package io.whozoss.agentos.agent

import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.IntentionGeneratedEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.ToolRequestEvent
import io.whozoss.agentos.sdk.caseEvent.ToolResponseEvent
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

        val isFirstIteration = events.none { it is ToolRequestEvent }
        val lastToolResponse = events.filterIsInstance<ToolResponseEvent>().lastOrNull()
        val executionState =
            when {
                isFirstIteration -> "No tools have been called yet. This is the first iteration."
                lastToolResponse?.success == true -> "Last tool '${lastToolResponse.toolName}' succeeded."
                lastToolResponse?.success == false -> "Last tool '${lastToolResponse.toolName}' FAILED: ${(lastToolResponse.output as? MessageContent.Text)?.content}"
                else -> "Previous steps completed."
            }

        val prompt =
            """
You must reason in 4 steps, then select the next tool.

Available tools:
$toolsDescription
- $ANSWER_TOOL: produce the final answer to the user (use this when no more tool calls are needed)

## Step 1 — Execution state
$executionState

## Step 2 — Agent constraints
Review the instructions and ensure the next action stays within the agent's defined scope.

## Step 3 — Capability check
Does the required action fall within the available tools? If not, select $ANSWER_TOOL and explain why.

## Step 4 — Data prerequisites
Is all the information required to call the next tool already available in the conversation history?
If not, select $ANSWER_TOOL and ask the user for the missing information.
${repetitionWarning?.let { "\n## WARNING — Repetition detected\n$it\n" } ?: ""}
Now produce your response using EXACTLY these XML tags (no extra text outside the tags):
<intention>your concise reasoning from the 4 steps above</intention>
<toolName>one tool name from: $toolNames</toolName>
            """.trimIndent()

        var lastException: Exception? = null
        var lastResponse: String? = null

        logger.debug { "Intention generation: sending ${messages.size + 1} messages to LLM" }
        logger.debug { "Intention prompt:\n$prompt" }

        repeat(MAX_INTENTION_ATTEMPTS) { attempt ->
            try {
                val response =
                    context.chatClient
                        .prompt(Prompt(messages + UserMessage(prompt)))
                        .call()
                        .content() ?: throw AgentIntentionGenerationException("Null LLM response")

                logger.debug { "Intention generation response:\n$response" }
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
                "attempts: ${lastException?.message}, falling back to $ANSWER_TOOL"
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
