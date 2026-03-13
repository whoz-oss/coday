package io.whozoss.agentos.agent

import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.agent.Agent
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent
import io.whozoss.agentos.sdk.caseEvent.AgentRunningEvent
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.IntentionGeneratedEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.ThinkingEvent
import io.whozoss.agentos.sdk.caseEvent.ToolRequestEvent
import io.whozoss.agentos.sdk.caseEvent.ToolResponseEvent
import io.whozoss.agentos.sdk.caseEvent.ToolSelectedEvent
import io.whozoss.agentos.sdk.caseEvent.WarnEvent
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.StandardTool
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import mu.KLogging
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
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
 *
 * All LLM calls use the streaming API (`stream().content()`) collected
 * to a single string via [streamToText]. This makes every LLM step
 * cancellable: coroutine cancellation propagates into the Reactor
 * subscription and aborts the in-flight HTTP request, so neither
 * interrupt nor kill leave orphaned API calls consuming quota.
 */
class AgentAdvanced(
    override val metadata: EntityMetadata = EntityMetadata(),
    private val model: AiModel,
    private val chatClient: ChatClient,
    private val tools: List<StandardTool<*>>,
    private val maxIterations: Int = 20,
) : Agent {
    override val name: String get() = model.name

    override fun run(
        events: List<CaseEvent>,
        shouldContinue: () -> Boolean,
    ): Flow<CaseEvent> =
        flow {
            val projectId = events.firstOrNull()?.projectId ?: throw IllegalArgumentException("No events provided")
            val caseId = events.firstOrNull()?.caseId ?: throw IllegalArgumentException("No events provided")

            emit(
                AgentRunningEvent(
                    projectId = projectId,
                    caseId = caseId,
                    agentId = id,
                    agentName = name,
                ),
            )

            var iteration = 0
            var continueLoop = true

            try {
                while (continueLoop && iteration < maxIterations && shouldContinue()) {
                    iteration++

                    // 1. Generate intention
                    // Each guard before a network call lets an interrupt/kill that arrives
                    // mid-iteration be honoured at the earliest possible point, rather than
                    // waiting for all remaining LLM round-trips to complete.
                    if (!shouldContinue()) break
                    emit(ThinkingEvent(projectId = projectId, caseId = caseId))
                    val intention = generateIntention(events, projectId, caseId)
                    emit(intention)

                    // 2. Select tool
                    if (!shouldContinue()) break
                    val toolSelected = selectTool(events, intention, projectId, caseId)
                    emit(toolSelected)

                    // Check if we should stop (Answer tool = done)
                    if (toolSelected.toolName == "Answer") {
                        continueLoop = false
                        continue
                    }

                    // 3. Generate parameters
                    if (!shouldContinue()) break
                    val toolRequestId = UUID.randomUUID().toString()
                    val parameters =
                        generateParameters(events, intention, toolSelected, projectId, caseId, toolRequestId)
                    emit(parameters)

                    // 4. Execute tool
                    if (!shouldContinue()) break
                    val response = executeTool(parameters, projectId, caseId)
                    emit(response)
                }

                if (iteration >= maxIterations) {
                    emit(
                        WarnEvent(
                            projectId = projectId,
                            caseId = caseId,
                            message = "Agent reached maximum iterations ($maxIterations) without completing",
                        ),
                    )
                }

                emit(
                    AgentFinishedEvent(
                        projectId = projectId,
                        caseId = caseId,
                        agentId = id,
                        agentName = name,
                    ),
                )
            } catch (e: Exception) {
                logger.error(e) { "Error during agent execution" }
                emit(
                    WarnEvent(
                        projectId = projectId,
                        caseId = caseId,
                        message = "Error during agent execution: ${e.message}",
                    ),
                )
            }
        }

    /**
     * Collect a streaming LLM response into a plain String.
     *
     * Using `stream()` instead of `call()` means:
     * - The coroutine suspends on each chunk rather than blocking a thread.
     * - Coroutine cancellation propagates into the Reactor subscription,
     *   aborting the underlying HTTP request immediately.
     *
     * Replacing `.call().content() ?: fallback` with `streamToText(prompt, fallback)`
     * is a drop-in substitution — the returned String is identical.
     */
    private suspend fun streamToText(
        prompt: Prompt,
        fallback: String = "",
    ): String {
        val chunks =
            chatClient
                .prompt(prompt)
                .stream()
                .content()
                .asFlow()
                .toList()
        val result = chunks.joinToString("")
        return result.ifEmpty { fallback }
    }

    /**
     * Generate the intention for the next step based on conversation history.
     */
    private suspend fun generateIntention(
        events: List<CaseEvent>,
        projectId: UUID,
        caseId: UUID,
    ): IntentionGeneratedEvent {
        val messages = convertEventsToMessages(events)
        val toolsDescription = tools.joinToString("\n") { "- ${it.name}: ${it.description}" }

        val lastUserMessage =
            events
                .filterIsInstance<MessageEvent>()
                .lastOrNull { it.actor.role == ActorRole.USER }
                ?.content
                ?.filterIsInstance<MessageContent.Text>()
                ?.joinToString(" ") { it.content }
                ?: "No user message found"

        val intentionPrompt =
            if (events.none { it is ToolRequestEvent }) {
                // First iteration
                """
Knowing the following available tools:
$toolsDescription

Here is the user's query:
<request>
$lastUserMessage
</request>

Make a concise explanation of what is the next logical step to take (which tool to call now to complete the step to achieve a satisfactory answer to the request).
                """.trimIndent()
            } else {
                // Subsequent iterations
                """
Knowing the following available tools:
$toolsDescription

Based on all previous steps, make a concise explanation of what is the next logical step to take (which tool to call now to complete the step to achieve a satisfactory answer to the request).
                """.trimIndent()
            }

        val intention =
            streamToText(
                Prompt(messages + UserMessage(intentionPrompt)),
                fallback = "Unable to generate intention",
            )

        return IntentionGeneratedEvent(
            projectId = projectId,
            caseId = caseId,
            agentId = id,
            intention = intention,
        )
    }

    /**
     * Select the appropriate tool based on the intention.
     */
    private suspend fun selectTool(
        events: List<CaseEvent>,
        intentionEvent: IntentionGeneratedEvent,
        projectId: UUID,
        caseId: UUID,
    ): ToolSelectedEvent {
        val messages = convertEventsToMessages(events)
        val toolNames = tools.map { it.name }

        val selectionPrompt =
            """
Based on this intention: "${intentionEvent.intention}"

Which tool should be used from: $toolNames

Respond with just the tool name.
            """.trimIndent()

        val toolName =
            streamToText(
                Prompt(messages + UserMessage(intentionEvent.intention) + UserMessage(selectionPrompt)),
                fallback = "Answer",
            ).trim()

        return ToolSelectedEvent(
            projectId = projectId,
            caseId = caseId,
            agentId = id,
            toolName = toolName,
        )
    }

    /**
     * Generate parameters for the selected tool.
     */
    private suspend fun generateParameters(
        events: List<CaseEvent>,
        intentionEvent: IntentionGeneratedEvent,
        toolSelectedEvent: ToolSelectedEvent,
        projectId: UUID,
        caseId: UUID,
        toolRequestId: String,
    ): ToolRequestEvent {
        val messages = convertEventsToMessages(events)
        val tool =
            tools.firstOrNull { it.name == toolSelectedEvent.toolName }
                ?: throw IllegalStateException("Tool not found: ${toolSelectedEvent.toolName}")

        val parametersPrompt =
            """
Tool: ${tool.name}
Description: ${tool.description}
Input Schema: ${tool.inputSchema}

Intention: ${intentionEvent.intention}

Generate the JSON parameters for this tool call.
            """.trimIndent()

        val parameters =
            streamToText(
                Prompt(messages + UserMessage(intentionEvent.intention) + UserMessage(parametersPrompt)),
                fallback = "{}",
            )

        return ToolRequestEvent(
            projectId = projectId,
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
        projectId: UUID,
        caseId: UUID,
    ): ToolResponseEvent {
        val tool =
            tools.firstOrNull { it.name == toolRequest.toolName }
                ?: return ToolResponseEvent(
                    projectId = projectId,
                    caseId = caseId,
                    toolRequestId = toolRequest.toolRequestId,
                    toolName = toolRequest.toolName,
                    output = MessageContent.Text("Tool not found: ${toolRequest.toolName}"),
                    success = false,
                )

        return try {
            val result = tool.executeWithJson(toolRequest.args)

            ToolResponseEvent(
                projectId = projectId,
                caseId = caseId,
                toolRequestId = toolRequest.toolRequestId,
                toolName = toolRequest.toolName,
                output = MessageContent.Text(result),
                success = true,
            )
        } catch (e: Exception) {
            ToolResponseEvent(
                projectId = projectId,
                caseId = caseId,
                toolRequestId = toolRequest.toolRequestId,
                toolName = toolRequest.toolName,
                output = MessageContent.Text("Error executing tool: ${e.message}"),
                success = false,
            )
        }
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

    companion object : KLogging()
}
