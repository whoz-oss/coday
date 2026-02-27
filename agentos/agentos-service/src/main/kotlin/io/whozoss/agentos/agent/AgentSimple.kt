package io.whozoss.agentos.orchestration

import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.agent.Agent
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent
import io.whozoss.agentos.sdk.caseEvent.AgentRunningEvent
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.TextChunkEvent
import io.whozoss.agentos.sdk.caseEvent.ThinkingEvent
import io.whozoss.agentos.sdk.caseEvent.ToolRequestEvent
import io.whozoss.agentos.sdk.caseEvent.ToolResponseEvent
import io.whozoss.agentos.sdk.caseEvent.WarnEvent
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.StandardTool
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.runBlocking
import mu.KLogging
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.DefaultToolDefinition
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.collections.firstOrNull
import kotlin.collections.map
import kotlin.time.TimeSource
import kotlin.time.measureTime

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
    override val metadata: EntityMetadata = EntityMetadata(),
    private val model: AiModel,
    private val chatClient: ChatClient,
    private val tools: Collection<StandardTool<*>>,
) : Agent {
    override val name: String get() = model.name

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
                        listOf(SystemMessage(model.instructions!!)) + messages
                    } else {
                        messages
                    }

                emit(ThinkingEvent(projectId = projectId, caseId = caseId))

                // Shared timer: reset to markNow() each time the LLM hands back control
                // (prompt sent, or tool response returned). Measures pure LLM thinking time
                // per turn, including both tool-calling turns and the final text turn.
                val llmTurnMark = AtomicReference(TimeSource.Monotonic.markNow())
                val llmTurnIndex = AtomicInteger(1)

                // Convert StandardTool to ToolCallback with event emission
                val toolCallbacks =
                    tools.map { tool ->
                        createToolCallbackWithEvents(tool, projectId, caseId, toolEventChannel, llmTurnMark, llmTurnIndex)
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
                var currentTurnLogged = false

                // Convert stream to Flow
                streamSpec.content().asFlow().collect { chunk ->
                    // Emit any pending tool events and reset the text-turn log flag so the
                    // next text chunk after a tool call is correctly recognised as a new turn.
                    var toolEvent = toolEventChannel.tryReceive().getOrNull()
                    while (toolEvent != null) {
                        emit(toolEvent)
                        if (toolEvent is ToolResponseEvent) {
                            currentTurnLogged = false
                        }
                        toolEvent = toolEventChannel.tryReceive().getOrNull()
                    }

                    // Log LLM thinking time once per turn on the first text chunk of that turn
                    if (!currentTurnLogged) {
                        currentTurnLogged = true
                        logger.info { "[AgentSimple] $name LLM turn ${llmTurnIndex.get()} answered in ${llmTurnMark.get().elapsedNow()}" }
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
                logger.error(e) { "Error during agent execution" }
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
                            AssistantMessage
                                .builder()
                                .toolCalls(
                                    toolCallsForCurrentMessage.toList(),
                                ).build(),
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
                            messages.add(ToolResponseMessage.builder().responses(toolResponseMessages).build())
                        }

                        toolCallsForCurrentMessage.clear()
                    }

                    // Add the message event
                    val textContent =
                        event.content
                            .filterIsInstance<MessageContent.Text>()
                            .joinToString("\n") { it.content }

                    when (event.actor.role) {
                        ActorRole.USER -> {
                            messages.add(UserMessage(textContent))
                        }

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
                    // Accumulate tool calls to attach to next AssistantMessage.
                    // Spring AI's AnthropicChatModel calls ModelOptionsUtils.jsonToMap() on
                    // the args string when rebuilding history — it crashes on blank/empty input.
                    // Normalise null or blank args to "{}" (valid JSON, safe empty object).
                    val safeArgs = event.args?.takeIf { it.isNotBlank() } ?: "{}"
                    toolCallsForCurrentMessage.add(
                        AssistantMessage.ToolCall(
                            event.toolRequestId,
                            "function",
                            event.toolName,
                            safeArgs,
                        ),
                    )
                }

                else -> {
                    // Ignore other event types for message conversion
                }
            }

            i++
        }

        // Handle any remaining tool calls at the end.
        // Note: args are already normalised to "{}" in the accumulation loop above.
        if (toolCallsForCurrentMessage.isNotEmpty()) {
            messages.add(
                AssistantMessage
                    .builder()
                    .toolCalls(
                        toolCallsForCurrentMessage.toList(),
                    ).build(),
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
                messages.add(ToolResponseMessage.builder().responses(toolResponseMessages).build())
            }
        }

        return messages
    }

    /**
     * Create a [ToolCallback] that wraps a StandardTool and emits events.
     * Emits ToolRequestEvent before execution and ToolResponseEvent after.
     *
     * We implement [ToolCallback] directly rather than using [MethodToolCallback] because
     * [MethodToolCallback] generates its input schema by introspecting the Java method
     * signature (here: `invoke(jsonArgs: String?)`) and ignores the schema we pass via
     * `.inputSchema(...)`. The LLM therefore received a schema with a single opaque
     * `jsonArgs` property instead of the tool's real parameters (e.g. `timezone`), which
     * caused it to send empty arguments every time.
     *
     * By implementing [ToolCallback] directly we fully control the [ToolDefinition] —
     * name, description, and inputSchema — that Spring AI sends to the LLM, while the
     * `call(String)` method receives the raw JSON the LLM produced and passes it straight
     * to [StandardTool.executeWithJson] for plugin-classloader-safe deserialization.
     *
     * [llmTurnMark] and [llmTurnIndex] are shared with the stream collector so that
     * LLM thinking time can be measured per turn (i.e. per tool-calling round-trip).
     */
    private fun createToolCallbackWithEvents(
        tool: StandardTool<*>,
        projectId: UUID,
        caseId: UUID,
        eventChannel: Channel<CaseEvent>,
        llmTurnMark: AtomicReference<TimeSource.Monotonic.ValueTimeMark>,
        llmTurnIndex: AtomicInteger,
    ): ToolCallback =
        object : ToolCallback {
            // Expose the tool's own schema verbatim — no reflection-based generation.
            private val definition =
                DefaultToolDefinition.builder()
                    .name(tool.name)
                    .description(tool.description)
                    .inputSchema(tool.inputSchema)
                    .build()

            override fun getToolDefinition() = definition

            override fun call(toolInput: String): String {
                val toolRequestId = UUID.randomUUID().toString()

                // The LLM decided to call this tool: log how long it thought since
                // the prompt was sent (or since the previous tool response was returned).
                val turn = llmTurnIndex.get()
                logger.info { "[AgentSimple] $name LLM turn $turn answered in ${llmTurnMark.get().elapsedNow()}" }

                runBlocking {
                    eventChannel.send(
                        ToolRequestEvent(
                            projectId = projectId,
                            caseId = caseId,
                            toolRequestId = toolRequestId,
                            toolName = tool.name,
                            args = toolInput,
                        ),
                    )
                }

                val result: String
                val toolDuration =
                    measureTime {
                        result =
                            try {
                                tool.executeWithJson(toolInput)
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
                    }
                logger.info { "[AgentSimple] tool ${tool.name} executed in $toolDuration" }

                // Reset the turn mark so the next measurement starts from when
                // we hand the tool result back to the LLM.
                llmTurnMark.set(TimeSource.Monotonic.markNow())
                llmTurnIndex.incrementAndGet()

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

    companion object : KLogging()
}
