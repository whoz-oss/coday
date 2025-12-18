package io.biznet.agentos.orchestration

import io.biznet.agentos.tools.domain.StandardTool
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.runBlocking
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.tool.method.MethodToolCallback
import org.springframework.ai.tool.support.ToolDefinitions
import org.springframework.util.ReflectionUtils
import java.util.UUID

/**
 * Simple agent implementation with single LLM call.
 *
 * This agent delegates tool orchestration to the LLM itself:
 * 1. Converts events to messages (including tool calls/responses)
 * 2. Makes a single LLM call with available tools
 * 3. LLM decides which tools to call and when
 * 4. Tools are wrapped to emit ToolRequestEvent and ToolResponseEvent
 * 5. Streams text progressively with TextChunkEvent
 *
 * This is simpler but gives less control over the orchestration loop
 * compared to AgentAdvanced.
 */
class AgentSimple(
    override val metadata: EntityMetadata,
    private val model: AgentModel,
    private val chatClient: ChatClient,
    private val tools: List<StandardTool<*>>,
) : IAgent {
    override val name: String get() = model.name
    private val id get() = metadata.id

    override fun run(events: List<CaseEvent>): Flow<CaseEvent> =
        flow {
            val projectId = events.firstOrNull()?.projectId ?: throw IllegalArgumentException("No events provided")
            val caseId = events.firstOrNull()?.caseId ?: throw IllegalArgumentException("No events provided")

            // Channel to collect tool events from callbacks
            val toolEventChannel = Channel<CaseEvent>(Channel.UNLIMITED)

            emit(
                AgentRunningEvent(
                    projectId = projectId,
                    caseId = caseId,
                    agentId = id,
                    agentName = name,
                ),
            )

            try {
                // Convert events to messages
                val messages = convertEventsToMessages(events)

                // Add system instructions if provided
                val allMessages =
                    if (model.instructions != null) {
                        listOf(SystemMessage(model.instructions)) + messages
                    } else {
                        messages
                    }

                emit(ThinkingEvent(projectId = projectId, caseId = caseId))

                // Convert StandardTool to ToolCallback with event emission
                val toolCallbacks =
                    tools.map { tool ->
                        createToolCallbackWithEvents(tool, projectId, caseId, toolEventChannel)
                    }

                // Make single LLM call with tools
                val prompt = chatClient.prompt(Prompt(allMessages))

                // Add tool callbacks if available
                val promptWithTools =
                    if (toolCallbacks.isNotEmpty()) {
                        prompt.toolCallbacks(toolCallbacks)
                    } else {
                        prompt
                    }

                // Use stream() for progressive text display
                val streamSpec = promptWithTools.stream()

                // Collect streamed chunks
                val contentBuilder = StringBuilder()

                // Convert stream to Flow
                streamSpec.content().asFlow().collect { chunk ->
                    // Emit any pending tool events
                    var toolEvent = toolEventChannel.tryReceive().getOrNull()
                    while (toolEvent != null) {
                        emit(toolEvent)
                        toolEvent = toolEventChannel.tryReceive().getOrNull()
                    }

                    contentBuilder.append(chunk)
                    // Emit text chunk for progressive display
                    emit(
                        TextChunkEvent(
                            projectId = projectId,
                            caseId = caseId,
                            chunk = chunk,
                        ),
                    )
                }

                val content = contentBuilder.toString()

                // Close tool event channel and emit remaining events
                toolEventChannel.close()
                for (toolEvent in toolEventChannel) {
                    emit(toolEvent)
                }

                // Emit the final assistant message
                if (content.isNotEmpty()) {
                    emit(
                        MessageEvent(
                            projectId = projectId,
                            caseId = caseId,
                            actor = Actor(id.toString(), name, ActorRole.AGENT),
                            content = listOf(MessageContent.Text(content)),
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

                emit(
                    AgentFinishedEvent(
                        projectId = projectId,
                        caseId = caseId,
                        agentId = id,
                        agentName = name,
                    ),
                )
            }
        }

    /**
     * Convert CaseEvents to Spring AI Messages for LLM context.
     * Includes tool calls and responses for proper conversation history.
     * Converts other agents to "user" role for LLM compatibility.
     */
    private fun convertEventsToMessages(events: List<CaseEvent>): List<Message> {
        val messages = mutableListOf<Message>()
        val toolCallsForCurrentMessage = mutableListOf<AssistantMessage.ToolCall>()
        val toolResponses = mutableMapOf<String, ToolResponseEvent>()

        // First pass: collect tool responses by ID
        events.filterIsInstance<ToolResponseEvent>().forEach { toolResponse ->
            toolResponses[toolResponse.toolRequestId] = toolResponse
        }

        // Second pass: build messages with tool calls
        var i = 0
        while (i < events.size) {
            val event = events[i]

            when (event) {
                is MessageEvent -> {
                    // If we have accumulated tool calls, create AssistantMessage with them
                    if (toolCallsForCurrentMessage.isNotEmpty()) {
                        messages.add(
                            AssistantMessage(
                                "", // Empty content when there are tool calls
                                emptyMap(),
                                toolCallsForCurrentMessage.toList(),
                            ),
                        )

                        // Add corresponding tool responses
                        val toolResponseMessages =
                            toolCallsForCurrentMessage.mapNotNull { toolCall ->
                                val response = toolResponses[toolCall.id()]
                                response?.let {
                                    val output =
                                        when (val content = it.output) {
                                            is MessageContent.Text -> content.content
                                            else -> content.toString()
                                        }
                                    ToolResponseMessage.ToolResponse(toolCall.id(), toolCall.name(), output)
                                }
                            }

                        if (toolResponseMessages.isNotEmpty()) {
                            messages.add(ToolResponseMessage(toolResponseMessages))
                        }

                        toolCallsForCurrentMessage.clear()
                    }

                    // Add the message event
                    val textContent =
                        event.content
                            .filterIsInstance<MessageContent.Text>()
                            .joinToString("\n") { it.content }

                    when (event.actor.role) {
                        ActorRole.USER -> messages.add(UserMessage(textContent))
                        ActorRole.AGENT -> {
                            if (event.actor.id == id.toString()) {
                                messages.add(AssistantMessage(textContent))
                            } else {
                                // Convert other agents to user messages for LLM compatibility
                                messages.add(UserMessage("[${event.actor.displayName}]: $textContent"))
                            }
                        }
                    }
                }

                is ToolRequestEvent -> {
                    // Accumulate tool calls to attach to next AssistantMessage
                    toolCallsForCurrentMessage.add(
                        AssistantMessage.ToolCall(
                            event.toolRequestId,
                            "function",
                            event.toolName,
                            event.args,
                        ),
                    )
                }

                else -> {
                    // Ignore other event types for message conversion
                }
            }

            i++
        }

        // Handle any remaining tool calls at the end
        if (toolCallsForCurrentMessage.isNotEmpty()) {
            messages.add(
                AssistantMessage(
                    "",
                    emptyMap(),
                    toolCallsForCurrentMessage.toList(),
                ),
            )

            val toolResponseMessages =
                toolCallsForCurrentMessage.mapNotNull { toolCall ->
                    val response = toolResponses[toolCall.id()]
                    response?.let {
                        val output =
                            when (val content = it.output) {
                                is MessageContent.Text -> content.content
                                else -> content.toString()
                            }
                        ToolResponseMessage.ToolResponse(toolCall.id(), toolCall.name(), output)
                    }
                }

            if (toolResponseMessages.isNotEmpty()) {
                messages.add(ToolResponseMessage(toolResponseMessages))
            }
        }

        return messages
    }

    /**
     * Create a MethodToolCallback that wraps a StandardTool and emits events.
     * Emits ToolRequestEvent before execution and ToolResponseEvent after.
     */
    private fun createToolCallbackWithEvents(
        tool: StandardTool<*>,
        projectId: UUID,
        caseId: UUID,
        eventChannel: Channel<CaseEvent>,
    ): MethodToolCallback {
        // Create a wrapper tool that emits events
        val wrapperTool =
            object : StandardTool<Any?> {
                override val name = tool.name
                override val description = tool.description
                override val inputSchema = tool.inputSchema
                override val version = tool.version
                override val paramType = tool.paramType

                override fun execute(input: Any?): String {
                    val toolRequestId = UUID.randomUUID().toString()

                    // Emit ToolRequestEvent
                    runBlocking {
                        eventChannel.send(
                            ToolRequestEvent(
                                projectId = projectId,
                                caseId = caseId,
                                toolRequestId = toolRequestId,
                                toolName = tool.name,
                                args = input?.toString() ?: "{}",
                            ),
                        )
                    }

                    // Execute the actual tool
                    val result =
                        try {
                            @Suppress("UNCHECKED_CAST")
                            (tool as StandardTool<Any?>).execute(input)
                        } catch (e: Exception) {
                            runBlocking {
                                eventChannel.send(
                                    ToolResponseEvent(
                                        projectId = projectId,
                                        caseId = caseId,
                                        toolRequestId = toolRequestId,
                                        toolName = tool.name,
                                        output = MessageContent.Text("Error: ${e.message}"),
                                        success = false,
                                    ),
                                )
                            }
                            throw e
                        }

                    // Emit ToolResponseEvent
                    runBlocking {
                        eventChannel.send(
                            ToolResponseEvent(
                                projectId = projectId,
                                caseId = caseId,
                                toolRequestId = toolRequestId,
                                toolName = tool.name,
                                output = MessageContent.Text(result),
                                success = true,
                            ),
                        )
                    }

                    return result
                }
            }

        val method =
            if (wrapperTool.paramType == null) {
                ReflectionUtils.findMethod(wrapperTool::class.java, "execute")!!
            } else {
                ReflectionUtils.findMethod(wrapperTool::class.java, "execute", wrapperTool.paramType)!!
            }

        return MethodToolCallback
            .builder()
            .toolDefinition(
                ToolDefinitions
                    .builder(method)
                    .description(wrapperTool.description)
                    .name(wrapperTool.name)
                    .inputSchema(wrapperTool.inputSchema)
                    .build(),
            ).toolMethod(method)
            .toolObject(wrapperTool)
            .build()
    }
}
