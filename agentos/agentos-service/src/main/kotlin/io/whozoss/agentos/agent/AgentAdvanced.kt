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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
import kotlin.coroutines.cancellation.CancellationException

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
 *
 * Cancellation is the primary stop mechanism. [shouldContinue] is accepted
 * in the signature for SDK compatibility but is not used internally —
 * [ensureActive] at each step gives the same cooperative-stop guarantee
 * through standard coroutine machinery.
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
        // Accepted for SDK backward compatibility; not used internally.
        // Cancellation is the primary stop mechanism via ensureActive().
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

            try {
                while (continueLoop && iteration < maxIterations) {
                    // Honour coroutine cancellation (interrupt or kill) at the top of
                    // every iteration — before any network call is made. This is the
                    // idiomatic replacement for the old `if (!shouldContinue()) break`
                    // guards: ensureActive() throws CancellationException if the
                    // coroutine has been cancelled, which unwinds the flow cleanly.
                    currentCoroutineContext().ensureActive()
                    iteration++

                    // 1. Generate intention (suspends on streaming; cancellable)
                    emit(ThinkingEvent(namespaceId = namespaceId, caseId = caseId))
                    val intention = generateIntention(events, namespaceId, caseId)
                    emit(intention)

                    // 2. Select tool (suspends on streaming; cancellable)
                    currentCoroutineContext().ensureActive()
                    val toolSelected = selectTool(events, intention, namespaceId, caseId)
                    emit(toolSelected)

                    // Check if we should stop (Answer tool = done)
                    if (toolSelected.toolName == "Answer") {
                        continueLoop = false
                        continue
                    }

                    // 3. Generate parameters (suspends on streaming; cancellable)
                    currentCoroutineContext().ensureActive()
                    val toolRequestId = UUID.randomUUID().toString()
                    val parameters =
                        generateParameters(events, intention, toolSelected, namespaceId, caseId, toolRequestId)
                    emit(parameters)

                    // 4. Execute tool (synchronous; ensureActive guards before the call)
                    currentCoroutineContext().ensureActive()
                    val response = executeTool(parameters, namespaceId, caseId)
                    emit(response)
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

                emit(
                    AgentFinishedEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        agentId = id,
                        agentName = name,
                    ),
                )
            } catch (e: CancellationException) {
                // Normal cooperative cancellation (interrupt or kill) — not an error.
                // Re-throw so the coroutine machinery can propagate the cancellation
                // up to the turn job and the case loop.
                logger.debug { "[AgentAdvanced] $name cancelled (${e.message})" }
                throw e
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
        namespaceId: UUID,
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
            namespaceId = namespaceId,
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
        namespaceId: UUID,
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
            namespaceId = namespaceId,
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
        namespaceId: UUID,
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

        return try {
            val result = tool.executeWithJson(toolRequest.args)

            ToolResponseEvent(
                namespaceId = namespaceId,
                caseId = caseId,
                toolRequestId = toolRequest.toolRequestId,
                toolName = toolRequest.toolName,
                output = MessageContent.Text(result),
                success = true,
            )
        } catch (e: Exception) {
            ToolResponseEvent(
                namespaceId = namespaceId,
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
