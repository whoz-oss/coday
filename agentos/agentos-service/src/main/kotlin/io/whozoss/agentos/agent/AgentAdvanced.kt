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

                    val repeatedTool = detectRepetitionLoop(accumulatedEvents)
                    if (repeatedTool == null) repetitionWarningEmitted = false

                    val repetitionWarning =
                        repeatedTool?.let { toolName ->
                            val msg =
                                "You have called `$toolName` $REPETITION_DETECTION_WINDOW times consecutively with no progress. " +
                                    "Stop and use the ${AgentIntentionGenerator.ANSWER_TOOL} tool to explain the situation or ask the user for more information."
                            if (!repetitionWarningEmitted) {
                                logger.warn { "Repetition loop detected: $toolName called $REPETITION_DETECTION_WINDOW consecutive times" }
                                emit(WarnEvent(namespaceId = namespaceId, caseId = caseId, message = msg))
                                repetitionWarningEmitted = true
                            }
                            msg
                        }

                    val intention = intentionGenerator.generate(context, accumulatedEvents, namespaceId, caseId, repetitionWarning)
                    emit(intention)
                    accumulatedEvents.add(intention)
                    lastIntention = intention

                    if (intention.toolName == AgentIntentionGenerator.ANSWER_TOOL) {
                        continueLoop = false
                        continue
                    }

                    if (!shouldContinue()) break
                    val toolRequestId = UUID.randomUUID().toString()
                    val parameters =
                        generateParameters(accumulatedEvents, intention, namespaceId, caseId, toolRequestId)
                    emit(parameters)
                    accumulatedEvents.add(parameters)

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

                if (shouldContinue()) {
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
                            emit(TextChunkEvent(namespaceId = namespaceId, caseId = caseId, chunk = chunk))
                        }
                    val content = contentBuilder.toString()
                    if (content.isNotEmpty()) {
                        val msg =
                            MessageEvent(
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
        events: List<CaseEvent>,
        intentionEvent: IntentionGeneratedEvent,
        namespaceId: UUID,
        caseId: UUID,
        toolRequestId: String,
    ): ToolRequestEvent {
        val messages = context.buildMessages(events)
        val tool =
            context.tools.firstOrNull { it.name == intentionEvent.toolName }
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
            context.chatClient
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

    private fun executeTool(
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

    companion object : KLogging() {
        internal const val REPETITION_DETECTION_WINDOW = 3
    }
}
