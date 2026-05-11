package io.whozoss.agentos.agent

import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.agent.Agent
import io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent
import io.whozoss.agentos.sdk.caseEvent.AgentRunningEvent
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.IntentionGeneratedEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.TextChunkEvent
import io.whozoss.agentos.sdk.caseEvent.ThinkingEvent
import io.whozoss.agentos.sdk.caseEvent.ToolRequestEvent
import io.whozoss.agentos.sdk.caseEvent.ToolResponseEvent
import io.whozoss.agentos.sdk.caseEvent.WarnEvent
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.reactive.asFlow
import mu.KLogging
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt
import java.util.UUID

/**
 * Advanced agent implementation with multi-step orchestration loop.
 *
 * This agent follows an explicit reasoning loop:
 * 1. Generate intention (what to do next)
 * 2. Select appropriate tool
 * 3. Generate parameters for the tool
 * 4. Execute the tool
 * 5. Repeat until Answer tool is called
 *
 * Each step emits events for observability and potential resumption.
 */
class AgentAdvanced(
    override val metadata: EntityMetadata = EntityMetadata(),
    override val name: String,
    private val chatClient: ChatClient,
    private val tools: List<StandardTool<*>>,
    /** The effective system instructions passed to the LLM, after namespace context injection. */
    val instructions: String? = null,
    /** AgentOS UUID of the user who initiated the case, or null when unresolvable. */
    private val userId: UUID? = null,
    /** Identity-provider key of the user (e.g. email). Used by plugins that manage their own auth. */
    private val userExternalId: String? = null,
    /** Returns the live event list of the current case at the moment of invocation. */
    private val caseEventsProvider: () -> List<CaseEvent> = { emptyList() },
    private val maxIterations: Int = 20,
) : Agent {
    override fun run(
        events: List<CaseEvent>,
        shouldContinue: () -> Boolean,
    ): Flow<CaseEvent> =
        flow {
            val namespaceId = events.firstOrNull()?.namespaceId ?: throw IllegalArgumentException("No events provided")
            val caseId = events.firstOrNull()?.caseId ?: throw IllegalArgumentException("No events provided")

            emit(
                AgentRunningEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    agentId = id,
                    agentName = name,
                ),
            )

            var iteration = 0
            var continueLoop = true
            var lastIntention: IntentionGeneratedEvent? = null
            val accumulatedEvents = events.toMutableList()

            try {
                while (continueLoop && iteration < maxIterations && shouldContinue()) {
                    iteration++

                    // 1. Generate intention + select tool in a single LLM call
                    // Guard before each blocking LLM call so an interrupt/kill that
                    // arrives mid-iteration is honoured without waiting for the full
                    // iteration to complete.
                    if (!shouldContinue()) break
                    emit(ThinkingEvent(namespaceId = namespaceId, caseId = caseId))
                    val intention = resolveIntentionAndTool(accumulatedEvents, namespaceId, caseId)
                    emit(intention)
                    accumulatedEvents.add(intention)
                    lastIntention = intention

                    // Check if we should stop (Answer tool = done)
                    if (intention.toolName == ANSWER_TOOL) {
                        continueLoop = false
                        continue
                    }

                    // 2. Generate parameters
                    if (!shouldContinue()) break
                    val toolRequestId = UUID.randomUUID().toString()
                    val parameters =
                        generateParameters(accumulatedEvents, intention, namespaceId, caseId, toolRequestId)
                    emit(parameters)
                    accumulatedEvents.add(parameters)

                    // 3. Execute tool
                    if (!shouldContinue()) break
                    val response = executeTool(parameters, namespaceId, caseId)
                    emit(response)
                    accumulatedEvents.add(response)
                }

                if (iteration >= maxIterations) {
                    emit(
                        WarnEvent(
                            namespaceId = namespaceId,
                            caseId = caseId,
                            message = "Agent reached maximum iterations ($maxIterations) without completing",
                        ),
                    )
                }

                // Generate final streamed answer before finishing
                if (shouldContinue()) {
                    val finalPromptText = "Based on the above conversation and your analysis, provide your response to the user."
                    val intentionContext = lastIntention?.let { "Your analysis: ${it.intention}\n\n$finalPromptText" } ?: finalPromptText
                    val messages = buildMessages(accumulatedEvents) + UserMessage(intentionContext)

                    val contentBuilder = StringBuilder()
                    chatClient
                        .prompt(Prompt(messages))
                        .stream()
                        .content()
                        .asFlow()
                        .takeWhile { shouldContinue() }
                        .collect { chunk ->
                            contentBuilder.append(chunk)
                            emit(TextChunkEvent(namespaceId = namespaceId, caseId = caseId, chunk = chunk))
                        }
                    val content = contentBuilder.toString()
                    if (content.isNotEmpty()) {
                        val msg = MessageEvent(
                            namespaceId = namespaceId,
                            caseId = caseId,
                            actor = Actor(id.toString(), name, ActorRole.AGENT),
                            content = listOf(MessageContent.Text(content)),
                        )
                        emit(msg)
                        accumulatedEvents.add(msg)
                    }
                }

                emit(
                    AgentFinishedEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        agentId = id,
                        agentName = name,
                    ),
                )
            } catch (e: AgentInterrupt) {
                // Not an error: a tool requested a structured interruption of this agent run.
                emitInterruptEvents(this@AgentAdvanced, e, namespaceId, caseId, logger)
            } catch (e: Exception) {
                logger.error(e) { "Error during agent execution" }
                emit(
                    WarnEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        message = "Error during agent execution: ${e.message}",
                    ),
                )
            }
        }

    /**
     * Single LLM call that both reasons about the next step and selects the tool to call.
     *
     * The prompt follows a 4-step structured reasoning approach:
     * 1. Analyse execution state (did the last step succeed?)
     * 2. Validate agent constraints (instructions / scope)
     * 3. Check capabilities (is a handoff needed?)
     * 4. Verify data prerequisites (is all required information available?)
     *
     * The LLM must respond with XML-tagged fields:
     *   <intention>free-text reasoning</intention>
     *   <toolName>ToolName</toolName>
     */
    private fun resolveIntentionAndTool(
        events: List<CaseEvent>,
        namespaceId: UUID,
        caseId: UUID,
    ): IntentionGeneratedEvent {
        val messages = buildMessages(events)
        val toolNames = tools.map { it.name } + ANSWER_TOOL
        val toolsDescription = tools.joinToString("\n") { "- ${it.name}: ${it.description}" }

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
Review the system instructions and ensure the next action stays within the agent's defined scope.

## Step 3 — Capability check
Does the required action fall within the available tools? If not, select $ANSWER_TOOL and explain why.

## Step 4 — Data prerequisites
Is all the information required to call the next tool already available in the conversation history?
If not, select $ANSWER_TOOL and ask the user for the missing information.

Now produce your response using EXACTLY these XML tags (no extra text outside the tags):
<intention>your concise reasoning from the 4 steps above</intention>
<toolName>one tool name from: $toolNames</toolName>
            """.trimIndent()

        val response =
            chatClient
                .prompt(Prompt(messages + UserMessage(prompt)))
                .call()
                .content() ?: "<intention>Unable to generate intention</intention><toolName>$ANSWER_TOOL</toolName>"

        val (intention, toolName) = parseIntentionAndTool(response, toolNames)

        return IntentionGeneratedEvent(
            namespaceId = namespaceId,
            caseId = caseId,
            agentId = id,
            intention = intention,
            toolName = toolName,
        )
    }

    /**
     * Parse the XML-tagged LLM response into intention text and tool name.
     *
     * Expected format:
     *   <intention>reasoning text</intention>
     *   <toolName>ToolName</toolName>
     *
     * Tolerant: extracts whatever is present, falls back to [ANSWER_TOOL] when
     * the tool tag is absent or does not match any valid tool name.
     */
    internal fun parseIntentionAndTool(
        response: String,
        validToolNames: List<String>,
    ): Pair<String, String> {
        val intention = extractFromTag(response, "intention") ?: response.trim()
        val rawTool = extractFromTag(response, "toolName")
        val toolName = validToolNames.firstOrNull { it.equals(rawTool?.trim(), ignoreCase = true) } ?: ANSWER_TOOL
        return intention to toolName
    }

    /**
     * Extract the text content of the first occurrence of `<tag>…</tag>` in [input].
     * Returns null when the tag is absent.
     */
    private fun extractFromTag(
        input: String,
        tag: String,
    ): String? =
        Regex("""<$tag>(.*?)</$tag>""", RegexOption.DOT_MATCHES_ALL)
            .find(input)
            ?.groupValues
            ?.get(1)
            ?.trim()

    /**
     * Generate parameters for the tool selected in [intentionEvent].
     */
    private fun generateParameters(
        events: List<CaseEvent>,
        intentionEvent: IntentionGeneratedEvent,
        namespaceId: UUID,
        caseId: UUID,
        toolRequestId: String,
    ): ToolRequestEvent {
        val messages = buildMessages(events)
        val tool =
            tools.firstOrNull { it.name == intentionEvent.toolName }
                ?: throw IllegalStateException("Tool not found: ${intentionEvent.toolName}")

        val parametersPrompt =
            """
Tool: ${tool.name}
Description: ${tool.description}
Input Schema: ${tool.inputSchema}

Intention: ${intentionEvent.intention}

Generate ONLY the JSON object matching the input schema above. No explanation, no markdown fences.
            """.trimIndent()

        val parameters =
            chatClient
                .prompt(Prompt(messages + UserMessage(parametersPrompt)))
                .call()
                .content()
                ?.trim() ?: "{}"

        return ToolRequestEvent(
            namespaceId = namespaceId,
            caseId = caseId,
            toolRequestId = toolRequestId,
            toolName = tool.name,
            args = parameters,
        )
    }

    /**
     * Execute the tool with the generated parameters.
     */
    private fun executeTool(
        toolRequest: ToolRequestEvent,
        namespaceId: UUID,
        caseId: UUID,
    ): ToolResponseEvent {
        val tool =
            tools.firstOrNull { it.name == toolRequest.toolName }
                ?: return ToolResponseEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    toolRequestId = toolRequest.toolRequestId,
                    toolName = toolRequest.toolName,
                    output = MessageContent.Text("Tool not found: ${toolRequest.toolName}"),
                    success = false,
                )

        val startMs = System.currentTimeMillis()
        return try {
            val integrationPrefix = toolRequest.toolName.substringBefore("__", missingDelimiterValue = "")
            val filteredEvents =
                caseEventsProvider().let { all ->
                    if (integrationPrefix.isEmpty()) {
                        all
                    } else {
                        all.filter { event ->
                            when (event) {
                                is ToolRequestEvent -> event.toolName.startsWith("${integrationPrefix}__")
                                is ToolResponseEvent -> event.toolName.startsWith("${integrationPrefix}__")
                                else -> true
                            }
                        }
                    }
                }
            val result =
                tool.executeWithJson(
                    toolRequest.args,
                    ToolContext(
                        namespaceId = namespaceId,
                        userId = userId,
                        userExternalId = userExternalId,
                        caseEvents = filteredEvents,
                    ),
                )

            ToolResponseEvent(
                namespaceId = namespaceId,
                caseId = caseId,
                toolRequestId = toolRequest.toolRequestId,
                toolName = toolRequest.toolName,
                output = MessageContent.Text(result),
                success = true,
                durationMs = System.currentTimeMillis() - startMs,
            )
        } catch (e: AgentInterrupt) {
            // Re-throw so the run() catch block can handle it — do not swallow as a tool error.
            throw e
        } catch (e: Exception) {
            ToolResponseEvent(
                namespaceId = namespaceId,
                caseId = caseId,
                toolRequestId = toolRequest.toolRequestId,
                toolName = toolRequest.toolName,
                output = MessageContent.Text("Error executing tool: ${e.message}"),
                success = false,
                durationMs = System.currentTimeMillis() - startMs,
            )
        }
    }

    /**
     * Build the full message list for an LLM call, prepending system instructions when present.
     */
    private fun buildMessages(events: List<CaseEvent>): List<Message> {
        val history = convertEventsToMessages(events)
        return if (instructions != null) listOf(SystemMessage(instructions)) + history else history
    }

    /**
     * Convert CaseEvents to Spring AI Messages for LLM context.
     * Masks tool calls/responses and keeps only user-agent exchanges.
     * Converts other agents to "user" role for LLM compatibility.
     */
    private fun convertEventsToMessages(events: List<CaseEvent>): List<Message> =
        events
            .filterIsInstance<MessageEvent>()
            .map { messageEvent ->
                val textContent =
                    messageEvent.content
                        .filterIsInstance<MessageContent.Text>()
                        .joinToString("\n") { it.content }

                when (messageEvent.actor.role) {
                    ActorRole.USER -> {
                        UserMessage(textContent)
                    }

                    ActorRole.AGENT -> {
                        if (messageEvent.actor.id == id.toString()) {
                            AssistantMessage(textContent)
                        } else {
                            // Convert other agents to user messages for LLM compatibility
                            UserMessage("[${messageEvent.actor.displayName}]: $textContent")
                        }
                    }
                }
            }

    companion object : KLogging() {
        private const val ANSWER_TOOL = "Answer"
    }
}
