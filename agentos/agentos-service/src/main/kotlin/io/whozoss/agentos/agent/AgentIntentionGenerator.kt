package io.whozoss.agentos.agent

import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.AnswerEvent
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.IntentionGeneratedEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.ToolRequestEvent
import io.whozoss.agentos.sdk.caseEvent.ToolResponseEvent
import io.whozoss.agentos.redirect.RedirectTool
import io.whozoss.agentos.util.AttemptFailure
import io.whozoss.agentos.util.AttemptSuccess
import io.whozoss.agentos.util.retryWithFallback
import mu.KLogging
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class AgentIntentionGenerator {
    fun generate(
        agentName: String,
        context: AgentAdvancedContext,
        events: List<CaseEvent>,
        namespaceId: UUID,
        caseId: UUID,
        repetitionWarning: String? = null,
    ): IntentionGeneratedEvent {
        val toolNames = context.tools.map { it.name } + ANSWER_TOOL
        val toolsDescription = context.tools.joinToString("\n") { "- ${it.name}: ${it.description}" }

        val redirectableAgentNames: List<String> = context.tools
            .filterIsInstance<RedirectTool>()
            .flatMap { it.eligibleAgents }
            .map { it.name }
            .filter { it != agentName }
            .distinct()
        val redirectableAgents: String = redirectableAgentNames
            .takeIf { it.isNotEmpty() }
            ?.joinToString("\n") { "  - $it" }
            ?: "  No other agent is available. Do not attempt a handoff — handle the request yourself or inform the user directly with `${ANSWER_TOOL}`."

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
        val redirectGuideline = context.redirectGuideline.orEmpty()

        val redirectGuidelineBlock =
            if (redirectGuideline.isNotBlank()) {
                """
### Redirect Guideline
Use the following guideline to know how and when to redirect to another agent:
<redirect_guideline>
$redirectGuideline
</redirect_guideline>"""
            } else {
                ""
            }
        val handoffGuidelineReference =
            if (redirectGuideline.isNotBlank()) {
                "switch to the correct agent based on the guidelines inside <redirect_guideline> and if none can do the action use"
            } else {
                "switch to the correct agent and if none can do the action use"
            }

        val intentionDescription = "[A thorough rationale justifying the action. Identify the instructions and expectations that applies with what is available and have been found which are pertinent and relevant for this step. This situation requires careful reasoning: explain shortly the instructions from <instructions> that should apply here and explain step by step WHY this specific next action is the right one, considering alternatives you discarded and why. Stay grounded in the conversation history — do not invent or assume technical details not explicitly present.]"

        val prompt =
            """
Available agents and tools:
<${agentName}_tools>
$toolsDescription
- $ANSWER_TOOL: produce the final answer to the user (use this when no more tool calls are needed)
</${agentName}_tools>


$executionState
$redirectGuidelineBlock

### Objective
Based on the full conversation and current context, choose the **single best next action** to advance the user’s goal. Follow all applicable guidance in `<instruction>`, resolving conflicts by priority and relevance. Applying one instruction does not excuse you from applying the others. If an intermediate step is needed to decide what to do or to produce a high-quality result, choose that step as the next action.

### Reasoning Guidelines
Before generating the output, analyze the situation using the following logic:

**1. Analyze Context & Execution State:**
*   Determine the subject of the user's request: Something currently visible to them (provided via <session-context>), something mentioned in previous messages, something that needs to be retrieved or searched, if there is doubts ask...
*   Check the last tool execution. Did it succeed?
    *   **Yes:** What is the logical sequential step?
    *   **No/Missing Info:** If the tool failed and required more data, the next step is `${ANSWER_TOOL}` to ask for clarification.
    *   **Goal Met:** If the `userGoal` is fully satisfied, use `${ANSWER_TOOL}` to confirm completion.
    *   **Warning:** If there a warning, should it be passed on to the user 
*   Verify you have the capabilities or the available agents (<AvailableAgents></AvailableAgents>) have the capabilities to execute the tool if not say so to the user.

**2. Gather the necessary information:**
* Identify what information is needed to understand the user's request and carry it out correctly, before choosing an action tool.
* Distinguish entity references from entity contents: having an ID identifies the object, but does not tell you its current state.
* Using the entity contents and conversation context, identify what information is still missing. Then retrieve any additional references or supporting data.


**3. Validate Agent Constraints:**
*   Review the **Current Active Agent's** workflow and instructions for guidance on next step.
*   Where several instructions from <instructions> could apply simultaneously or contradictory, evaluate their relevance, identify any that take precedence over or invalidate others and if you have already executed one, check if there is remaining ones to be applied
*   Check for prohibitions. If a restriction blocks the user's request, your action is `${ANSWER_TOOL}` to explain why.

**4. Verify Capabilities (Agent Handoff):**
*   Does the **Current Active Agent** possess the tool required for the next action?
    *   **NO:** The next action must be to $handoffGuidelineReference `${ANSWER_TOOL}` to warn the user. Be careful the guideline are defined globally and may refer to agents not available to the user. The agents actually available for redirection are:
<AvailableAgentsForHandoff>
$redirectableAgents
</AvailableAgentsForHandoff>
    *   **YES:** Proceed to the next check.

**Non-discrimination safeguard:**
Do not plan steps that would discriminate based on gender, ethnicity, religion, age, physical appearance, or any other protected attribute. If the user's request implies such a step, your next action must be `Answer` — clarify with the user that this cannot be done.

${repetitionWarning ?: ""}

### Output Instructions
You must respond with **exactly** this XML structure and **nothing else** — no prose, no markdown fences, no preamble (any deviation from the following expected xml would result in a error):

<intention>$intentionDescription</intention>
<toolName>[The exact name of the tool to be called]</toolName>

The tag names intention and toolName are fixed. The chosen tool’s name goes inside <toolName>; it must not become an XML tag. Do not reorder them.
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
                                <intention>$intentionDescription</intention>
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
                val response = context.chatClient.prompt(Prompt(messages)).call().content()
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
