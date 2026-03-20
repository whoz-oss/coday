package io.whozoss.agentos.agent

import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.agent.Agent
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
import kotlin.time.TimeSource
import kotlin.time.measureTime

/**
 * Simple agent implementation that delegates tool orchestration to Spring AI.
 *
 * ## LLM call strategy
 *
 * When tools are registered we use [ChatClient.CallResponseSpec] (`call()`) rather
 * than `stream()`. Spring AI's streaming path processes parallel tool calls one at a
 * time: it executes the first callback, sends a new LLM request with only that result,
 * and repeats — so the LLM never receives all parallel tool results together and loops
 * indefinitely re-requesting the missing ones.
 *
 * `call()` collects ALL tool calls from a single LLM turn, executes ALL callbacks,
 * then sends one follow-up request with all results. This is what Anthropic expects
 * and terminates the loop correctly.
 *
 * When no tools are registered we use `stream()` for progressive text display.
 *
 * ## Tool events
 *
 * Tool callbacks emit [ToolRequestEvent] and [ToolResponseEvent] into a channel.
 * After `call()` returns (all tool rounds complete), we drain the channel and emit
 * those events so they are stored in the event list for history reconstruction on
 * subsequent [run] invocations.
 */
class AgentSimple(
    override val metadata: EntityMetadata = EntityMetadata(),
    private val model: AiModel,
    private val chatClient: ChatClient,
    private val tools: Collection<StandardTool<*>>,
) : Agent {
    override val name: String get() = model.name

    /** The effective system instructions passed to the LLM, after namespace context injection. */
    val instructions: String? get() = model.instructions

    override fun run(events: List<CaseEvent>): Flow<CaseEvent> =
        flow {
            val namespaceId = events.firstOrNull()?.namespaceId ?: throw IllegalArgumentException("No events provided")
            val caseId = events.firstOrNull()?.caseId ?: throw IllegalArgumentException("No events provided")

            // Channel to collect tool events emitted by callbacks during call().
            val toolEventChannel = Channel<CaseEvent>(Channel.UNLIMITED)

            try {
                val messages = convertEventsToMessages(events)

                val allMessages =
                    if (model.instructions != null) {
                        listOf(SystemMessage(model.instructions!!)) + messages
                    } else {
                        messages
                    }

                // Bail out cleanly if cancelled before the LLM call starts.
                currentCoroutineContext().ensureActive()

                emit(ThinkingEvent(namespaceId = namespaceId, caseId = caseId))

                val llmTurnMark = AtomicReference(TimeSource.Monotonic.markNow())
                val llmTurnIndex = AtomicInteger(1)

                val toolCallbacks =
                    tools.map { tool ->
                        createToolCallbackWithEvents(
                            tool,
                            namespaceId,
                            caseId,
                            toolEventChannel,
                            llmTurnMark,
                            llmTurnIndex,
                        )
                    }

                val prompt = chatClient.prompt(Prompt(allMessages))
                val promptWithTools =
                    if (toolCallbacks.isNotEmpty()) {
                        prompt.toolCallbacks(toolCallbacks)
                    } else {
                        prompt
                    }

                // ----------------------------------------------------------------
                // LLM invocation
                //
                // Tools present: use blocking call().
                //   Spring AI's stream() processes parallel tool calls one at a time
                //   (execute callback → new LLM request → repeat), so the LLM never
                //   receives all parallel results together and loops indefinitely.
                //   call() batches all callbacks from one turn and sends one follow-up
                //   request, matching what Anthropic requires.
                //
                // No tools: use stream() for progressive text display.
                // ----------------------------------------------------------------
                val content: String

                if (toolCallbacks.isNotEmpty()) {
                    val response =
                        withContext(Dispatchers.IO) {
                            promptWithTools.call().content()
                        } ?: ""
                    logger.info {
                        "[AgentSimple] $name LLM answered in ${llmTurnMark.get().elapsedNow()}"
                    }
                    content = response
                    if (content.isNotEmpty()) {
                        emit(TextChunkEvent(namespaceId = namespaceId, caseId = caseId, chunk = content))
                    }
                } else {
                    val contentBuilder = StringBuilder()
                    var firstChunk = true
                    promptWithTools
                        .stream()
                        .content()
                        .asFlow()
                        .takeWhile { currentCoroutineContext().isActive }
                        .collect { chunk ->
                            if (firstChunk) {
                                firstChunk = false
                                logger.info {
                                    "[AgentSimple] $name LLM answered in ${llmTurnMark.get().elapsedNow()}"
                                }
                            }
                            contentBuilder.append(chunk)
                            emit(TextChunkEvent(namespaceId = namespaceId, caseId = caseId, chunk = chunk))
                        }
                    content = contentBuilder.toString()
                }

                // Drain tool events emitted by callbacks during call().
                toolEventChannel.close()
                for (toolEvent in toolEventChannel) {
                    emit(toolEvent)
                }

                if (content.isNotEmpty()) {
                    emit(
                        MessageEvent(
                            namespaceId = namespaceId,
                            caseId = caseId,
                            actor = Actor(id.toString(), name, ActorRole.AGENT),
                            content = listOf(MessageContent.Text(content)),
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
                logger.debug { "[AgentSimple] $name cancelled (${e.message})" }
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
                emit(
                    AgentFinishedEvent(
                        namespaceId = namespaceId,
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
                    // Accumulate tool calls to attach to the next AssistantMessage.
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

        // Handle any remaining tool calls at the end of the event list
        // (tool round with no trailing MessageEvent, e.g. history ends mid-turn).
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
     * Create a [ToolCallback] that wraps a [StandardTool] and emits events.
     *
     * We implement [ToolCallback] directly rather than using [MethodToolCallback] because
     * [MethodToolCallback] generates its input schema by introspecting the Java method
     * signature and ignores the schema we pass via `.inputSchema(...)`. The LLM therefore
     * received a schema with a single opaque `jsonArgs` property instead of the tool's
     * real parameters, causing it to send empty arguments every time.
     *
     * By implementing [ToolCallback] directly we fully control the [ToolDefinition] sent
     * to the LLM, while `call(String)` receives the raw JSON and passes it to
     * [StandardTool.executeWithJson] for plugin-classloader-safe deserialization.
     */
    private fun createToolCallbackWithEvents(
        tool: StandardTool<*>,
        namespaceId: UUID,
        caseId: UUID,
        eventChannel: Channel<CaseEvent>,
        llmTurnMark: AtomicReference<TimeSource.Monotonic.ValueTimeMark>,
        llmTurnIndex: AtomicInteger,
    ): ToolCallback =
        object : ToolCallback {
            private val definition =
                DefaultToolDefinition
                    .builder()
                    .name(tool.name)
                    .description(tool.description)
                    .inputSchema(tool.inputSchema)
                    .build()

            override fun getToolDefinition() = definition

            override fun call(toolInput: String): String {
                val toolRequestId = UUID.randomUUID().toString()

                val turn = llmTurnIndex.get()
                logger.info { "[AgentSimple] $name LLM turn $turn answered in ${llmTurnMark.get().elapsedNow()}" }

                runBlocking {
                    eventChannel.send(
                        ToolRequestEvent(
                            namespaceId = namespaceId,
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
                                            namespaceId = namespaceId,
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

                llmTurnMark.set(TimeSource.Monotonic.markNow())
                llmTurnIndex.incrementAndGet()

                runBlocking {
                    eventChannel.send(
                        ToolResponseEvent(
                            namespaceId = namespaceId,
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
