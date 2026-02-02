package io.biznet.agentos.agent

import io.biznet.agentos.sdk.entity.EntityMetadata
import io.biznet.agentos.sdk.model.ActorRole
import io.biznet.agentos.sdk.model.Agent
import io.biznet.agentos.sdk.model.AgentFinishedEvent
import io.biznet.agentos.sdk.model.AgentModel
import io.biznet.agentos.sdk.model.AgentRunningEvent
import io.biznet.agentos.sdk.model.CaseEvent
import io.biznet.agentos.sdk.model.IntentionGeneratedEvent
import io.biznet.agentos.sdk.model.MessageContent
import io.biznet.agentos.sdk.model.MessageEvent
import io.biznet.agentos.sdk.model.StandardTool
import io.biznet.agentos.sdk.model.ThinkingEvent
import io.biznet.agentos.sdk.model.ToolRequestEvent
import io.biznet.agentos.sdk.model.ToolResponseEvent
import io.biznet.agentos.sdk.model.ToolSelectedEvent
import io.biznet.agentos.sdk.model.WarnEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt
import java.util.UUID
import kotlin.collections.firstOrNull
import kotlin.collections.joinToString
import kotlin.collections.map

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
    override val metadata: EntityMetadata,
    private val model: AgentModel,
    private val chatClient: ChatClient,
    private val tools: List<StandardTool<*>>,
    private val agentService: AgentService,
    private val maxIterations: Int = 20,
) : Agent {
    override val name: String get() = model.name
    private val id get() = metadata.id

    override fun run(events: List<CaseEvent>): Flow<CaseEvent> =
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
                while (continueLoop && iteration < maxIterations) {
                    iteration++

                    // 1. Generate intention
                    emit(ThinkingEvent(projectId = projectId, caseId = caseId))
                    val intention = generateIntention(events, projectId, caseId)
                    emit(intention)

                    // 2. Select tool
                    val toolSelected = selectTool(events, intention, projectId, caseId)
                    emit(toolSelected)

                    // Check if we should stop
                    if (toolSelected.toolName == "Answer") {
                        continueLoop = false
                        continue
                    }

                    // 3. Generate parameters
                    val toolRequestId = UUID.randomUUID().toString()
                    val parameters = generateParameters(events, intention, toolSelected, projectId, caseId, toolRequestId)
                    emit(parameters)

                    // 4. Execute tool
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
     * Generate the intention for the next step based on conversation history.
     */
    private fun generateIntention(
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
            chatClient
                .prompt(Prompt(messages + UserMessage(intentionPrompt)))
                .call()
                .content() ?: "Unable to generate intention"

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
    private fun selectTool(
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
            chatClient
                .prompt(Prompt(messages + UserMessage(intentionEvent.intention) + UserMessage(selectionPrompt)))
                .call()
                .content()
                ?.trim() ?: "Answer"

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
    private fun generateParameters(
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
            chatClient
                .prompt(Prompt(messages + UserMessage(intentionEvent.intention) + UserMessage(parametersPrompt)))
                .call()
                .content() ?: "{}"

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
            // TODO: Parse parameters based on tool.paramType
            // For now, pass null as we need proper parameter parsing
            val result = tool.execute(null)

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
                    ActorRole.USER -> UserMessage(textContent)
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
}
