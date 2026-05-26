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
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.reactive.asFlow
import mu.KLogging
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt
import java.util.UUID

class AgentAdvanced(
    override val metadata: EntityMetadata = EntityMetadata(),
    override val name: String,
    private val context: AgentAdvancedContext,
    private val intentionGenerator: AgentIntentionGenerator,
    private val userId: UUID? = null,
    private val userExternalId: String? = null,
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
            var repetitionWarningEmitted = false
            val accumulatedEvents = events.toMutableList()

            try {
                while (continueLoop && iteration < maxIterations && shouldContinue()) {
                    iteration++

                    if (!shouldContinue()) break
                    emit(ThinkingEvent(namespaceId = namespaceId, caseId = caseId))

                    val repetitionWarning =
                        handleRepetitionDetection(
                            accumulatedEvents,
                            namespaceId,
                            caseId,
                            repetitionWarningEmitted,
                        ) { event -> emit(event) }
                    repetitionWarningEmitted = repetitionWarning != null

                    val intention =
                        intentionGenerator.generate(context, accumulatedEvents, namespaceId, caseId, repetitionWarning)
                    emit(intention)
                    accumulatedEvents.add(intention)
                    lastIntention = intention

                    if (intention.toolName == AgentIntentionGenerator.ANSWER_TOOL) {
                        continueLoop = false
                    } else {
                        handleToolExecution(
                            accumulatedEvents,
                            intention,
                            namespaceId,
                            caseId,
                            shouldContinue,
                        ) { event -> emit(event) }
                    }
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
                if (shouldContinue()) {
                    generateFinalResponse(
                        accumulatedEvents,
                        lastIntention,
                        namespaceId,
                        caseId,
                        shouldContinue,
                    ) { event -> emit(event) }
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

    private suspend fun handleRepetitionDetection(
        events: List<CaseEvent>,
        namespaceId: UUID,
        caseId: UUID,
        warningAlreadyEmitted: Boolean,
        emitEvent: suspend (CaseEvent) -> Unit,
    ): String? {
        val repeatedTool = detectRepetitionLoop(events) ?: return null
        val msg =
            "Calling a tool with the same parameters will produce the same results.\n" +
                "You have called the tool $repeatedTool $REPETITION_DETECTION_WINDOW times consecutively. " +
                "If the tool has not added meaningful information to the conversation, " +
                "stop calling it and consider the next step toward achieving the user's goal. " +
                "If you do not have enough information to proceed, use ${AgentIntentionGenerator.ANSWER_TOOL} to ask the user for further instructions."
        if (!warningAlreadyEmitted) {
            logger.warn { "Repetition loop detected: $repeatedTool called $REPETITION_DETECTION_WINDOW consecutive times" }
            emitEvent(WarnEvent(namespaceId = namespaceId, caseId = caseId, message = msg))
        }
        return msg
    }

    private suspend fun handleToolExecution(
        accumulatedEvents: MutableList<CaseEvent>,
        intention: IntentionGeneratedEvent,
        namespaceId: UUID,
        caseId: UUID,
        shouldContinue: () -> Boolean,
        emitEvent: suspend (CaseEvent) -> Unit,
    ) {
        if (!shouldContinue()) return
        val toolRequestId = UUID.randomUUID().toString()
        val parameters =
            generateParameters(
                accumulatedEvents = accumulatedEvents,
                intentionEvent = intention,
                namespaceId = namespaceId,
                caseId = caseId,
                toolRequestId = toolRequestId,
            )
        emitEvent(parameters)
        accumulatedEvents.add(parameters)

        if (!shouldContinue()) return
        // executeTool always returns a ToolResponseEvent, even on AgentInterrupt.
        // We must emit and accumulate the response before re-throwing so the event
        // history stays well-formed (every ToolRequestEvent has a matching response).
        var interrupt: AgentInterrupt? = null
        val response =
            try {
                executeTool(parameters, namespaceId, caseId)
            } catch (e: AgentInterrupt) {
                interrupt = e
                // executeTool already built the ToolResponseEvent before throwing;
                // the .also { throw e } pattern means the value was created but never
                // returned. Re-create the response here so it can be emitted.
                ToolResponseEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    toolRequestId = parameters.toolRequestId,
                    toolName = parameters.toolName,
                    output =
                        MessageContent.Text(
                            when (e) {
                                is AgentInterrupt.Redirect -> "Redirecting to agent '${e.targetAgentName}'."
                            },
                        ),
                    success = true,
                )
            }
        emitEvent(response)
        accumulatedEvents.add(response)
        // Re-throw the interrupt after the response has been properly recorded.
        interrupt?.let { throw it }
    }

    private suspend fun generateFinalResponse(
        accumulatedEvents: List<CaseEvent>,
        lastIntention: IntentionGeneratedEvent?,
        namespaceId: UUID,
        caseId: UUID,
        shouldContinue: () -> Boolean,
        emitEvent: suspend (CaseEvent) -> Unit,
    ) {
        val finalPromptText = "Based on the above conversation and your analysis, provide your response to the user."
        val intentionContext = lastIntention?.let { "Your analysis: ${it.intention}\n\n$finalPromptText" } ?: finalPromptText
        val messages = context.buildMessages(accumulatedEvents) + UserMessage(intentionContext)

        val contentBuilder = StringBuilder()
        context.chatClient
            .prompt(Prompt(messages))
            .stream()
            .content()
            .asFlow()
            .takeWhile { shouldContinue() }
            .collect { chunk ->
                contentBuilder.append(chunk)
                emitEvent(TextChunkEvent(namespaceId = namespaceId, caseId = caseId, chunk = chunk))
            }
        val content = contentBuilder.toString().stripConversationTags()
        if (content.isNotEmpty()) {
            val msg =
                MessageEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    actor = Actor(id.toString(), name, ActorRole.AGENT),
                    content = listOf(MessageContent.Text(content)),
                )
            emitEvent(msg)
        }
    }

    internal fun detectRepetitionLoop(events: List<CaseEvent>): String? =
        events
            .filterIsInstance<ToolResponseEvent>()
            .filter { it.success }
            .takeLast(REPETITION_DETECTION_WINDOW)
            .takeIf { it.size == REPETITION_DETECTION_WINDOW }
            ?.map { it.toolName }
            ?.toSet()
            ?.singleOrNull()

    private fun generateParameters(
        accumulatedEvents: List<CaseEvent>,
        intentionEvent: IntentionGeneratedEvent,
        namespaceId: UUID,
        caseId: UUID,
        toolRequestId: String,
    ): ToolRequestEvent {
        val tool =
            context.tools.firstOrNull { it.name == intentionEvent.toolName }
                ?: throw IllegalStateException("Tool not found: ${intentionEvent.toolName}")

        val parametersPrompt =
            """
Generate the parameters for the tool call below.
Tool: ${tool.name}
Description: ${tool.description}
Input Schema: 
```
${tool.inputSchema}
```

Intention: ${intentionEvent.intention}

**Generate ONLY the JSON object matching the input schema above. No explanation, no markdown fences.**
            """.trimIndent()
        val accumulatedEventsWithoutCurrentToolCall = accumulatedEvents.dropLast(1)
        val messages = context.buildMessages(accumulatedEventsWithoutCurrentToolCall) + UserMessage(parametersPrompt)
        val parameters =
            context.chatClient
                .prompt(Prompt(messages))
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

    private suspend fun executeTool(
        toolRequest: ToolRequestEvent,
        namespaceId: UUID,
        caseId: UUID,
    ): ToolResponseEvent {
        val tool =
            context.tools.firstOrNull { it.name == toolRequest.toolName }
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
            val result: ToolExecutionResult =
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
                output = MessageContent.Text(result.output),
                success = result.success,
                durationMs = System.currentTimeMillis() - startMs,
                toolMetadata = result.metadata,
            )
        } catch (e: AgentInterrupt) {
            // Re-throw so handleToolExecution() can emit a proper ToolResponseEvent
            // before the interrupt propagates to the run() catch block.
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

    companion object : KLogging() {
        internal const val REPETITION_DETECTION_WINDOW = 3
    }
}
