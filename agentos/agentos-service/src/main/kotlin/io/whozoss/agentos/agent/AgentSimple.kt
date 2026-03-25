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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import mu.KLogging
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.anthropic.AnthropicChatOptions
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.DefaultToolDefinition
import java.util.UUID
import kotlin.time.TimeSource
import kotlin.time.measureTime

/**
 * Simple agent that owns its own tool-calling loop.
 *
 * ## Design: ChatModel + ToolCallback stubs + internalToolExecutionEnabled=false
 *
 * Spring AI 2.x has a clean separation:
 * - [ToolCallback] carries both a [org.springframework.ai.tool.definition.ToolDefinition]
 *   (the schema the LLM sees) and a `call()` implementation (the executor).
 * - [AnthropicChatOptions.internalToolExecutionEnabled] = `false` disables automatic
 *   tool execution for a specific request, returning the raw [ChatResponse] with
 *   `toolCalls` intact.
 * - [ChatModelFactory] also wires a `noOpEligibilityPredicate` as a belt-and-suspenders
 *   guard at the model level.
 *
 * We put [toolCallbackStubs] in [AnthropicChatOptions] so Spring AI serialises the tool
 * schemas into the Anthropic API request. Setting `internalToolExecutionEnabled = false`
 * ensures Spring AI never invokes `call()` — AgentSimple owns all tool execution.
 *
 * ## Loop design
 *
 * 1. Call [ChatModel.stream], collect chunks, emit [TextChunkEvent]s as they arrive.
 * 2. Union `toolCalls` across all chunks (deduplicate by id).
 * 3. If tool calls present: execute all serially, emit [ToolRequestEvent]/[ToolResponseEvent],
 *    append [AssistantMessage] + [ToolResponseMessage] to the running message list, loop.
 * 4. If no tool calls: emit [MessageEvent] + [AgentFinishedEvent] and exit.
 *
 * Cancellability: [kotlinx.coroutines.ensureActive] at every turn boundary;
 * [kotlinx.coroutines.reactive.asFlow] suspends cooperatively so cancellation propagates
 * into the Reactor subscription.
 */
class AgentSimple(
    override val metadata: EntityMetadata = EntityMetadata(),
    private val model: AiModel,
    private val chatModel: ChatModel,
    private val tools: Collection<StandardTool<*>>,
) : Agent {
    override val name: String get() = model.name

    val instructions: String? get() = model.instructions

    private val toolsByName: Map<String, StandardTool<*>> = tools.associateBy { it.name }

    /**
     * ToolCallback stubs that carry tool schemas for the LLM.
     *
     * Spring AI reads these to build the `tools` array in the provider API request.
     * The `call()` implementation is intentionally unreachable — [ChatModelFactory]
     * wires a [org.springframework.ai.model.tool.ToolExecutionEligibilityPredicate]
     * that always returns `false`, so Spring AI never invokes it.
     */
    private val toolCallbackStubs: Array<ToolCallback> =
        tools
            .map { tool ->
                object : ToolCallback {
                    private val definition =
                        DefaultToolDefinition
                            .builder()
                            .name(tool.name)
                            .description(tool.description)
                            .inputSchema(tool.inputSchema)
                            .build()

                    override fun getToolDefinition() = definition

                    override fun call(toolInput: String): String =
                        error("[AgentSimple] ToolCallback.call() must never be invoked — AgentSimple owns tool execution. Check ChatModelFactory.noOpEligibilityPredicate.")
                }
            }
            .toTypedArray()

    override fun run(events: List<CaseEvent>): Flow<CaseEvent> =
        flow {
            val namespaceId =
                events.firstOrNull()?.namespaceId
                    ?: throw IllegalArgumentException("No events provided")
            val caseId =
                events.firstOrNull()?.caseId
                    ?: throw IllegalArgumentException("No events provided")

            try {
                val systemMessages: List<Message> =
                    if (model.instructions != null) listOf(SystemMessage(model.instructions!!)) else emptyList()

                val messages = convertEventsToMessages(events).toMutableList()

                currentCoroutineContext().ensureActive()
                emit(ThinkingEvent(namespaceId = namespaceId, caseId = caseId))

                var turnIndex = 1
                var turnMark = TimeSource.Monotonic.markNow()

                while (true) {
                    currentCoroutineContext().ensureActive()

                    // Advertise tool schemas via AnthropicChatOptions.toolCallbacks.
                    // internalToolExecutionEnabled=false prevents Spring AI from executing
                    // them — we get the raw ChatResponse with toolCalls for our own loop.
                    val options =
                        AnthropicChatOptions
                            .builder()
                            .toolCallbacks(toolCallbackStubs.toList())
                            .internalToolExecutionEnabled(false)
                            .build()
                    val prompt = Prompt(systemMessages + messages, options)

                    val chunks: List<ChatResponse> =
                        chatModel
                            .stream(prompt)
                            .asFlow()
                            .toList()

                    logger.info {
                        "[AgentSimple] $name turn $turnIndex answered in ${turnMark.elapsedNow()}"
                    }

                    val textBuilder = StringBuilder()
                    for (chunk in chunks) {
                        val text = chunk.result?.output?.text
                        if (!text.isNullOrEmpty()) {
                            textBuilder.append(text)
                            emit(TextChunkEvent(namespaceId = namespaceId, caseId = caseId, chunk = text))
                        }
                    }

                    val toolCalls: List<AssistantMessage.ToolCall> =
                        chunks
                            .flatMap { it.result?.output?.toolCalls.orEmpty() }
                            .distinctBy { it.id }

                    if (toolCalls.isEmpty()) {
                        val finalText = textBuilder.toString()
                        if (finalText.isNotEmpty()) {
                            emit(
                                MessageEvent(
                                    namespaceId = namespaceId,
                                    caseId = caseId,
                                    actor = Actor(id.toString(), name, ActorRole.AGENT),
                                    content = listOf(MessageContent.Text(finalText)),
                                ),
                            )
                        }
                        break
                    }

                    currentCoroutineContext().ensureActive()

                    val toolResults =
                        executeToolCalls(
                            toolCalls = toolCalls,
                            namespaceId = namespaceId,
                            caseId = caseId,
                            turnIndex = turnIndex,
                        )

                    messages += AssistantMessage.builder().toolCalls(toolCalls).build()

                    val springResponses =
                        toolResults.map { (toolCall, result) ->
                            ToolResponseMessage.ToolResponse(toolCall.id, toolCall.name, result)
                        }
                    messages += ToolResponseMessage.builder().responses(springResponses).build()

                    turnIndex++
                    turnMark = TimeSource.Monotonic.markNow()
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
                logger.error(e) { "[AgentSimple] $name error during execution" }
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

    private suspend fun FlowCollector<CaseEvent>.executeToolCalls(
        toolCalls: List<AssistantMessage.ToolCall>,
        namespaceId: UUID,
        caseId: UUID,
        turnIndex: Int,
    ): List<Pair<AssistantMessage.ToolCall, String>> {
        val results = mutableListOf<Pair<AssistantMessage.ToolCall, String>>()

        for (toolCall in toolCalls) {
            currentCoroutineContext().ensureActive()

            val toolName = toolCall.name
            val toolInput = toolCall.arguments
            val toolRequestId = toolCall.id

            emit(
                ToolRequestEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    toolRequestId = toolRequestId,
                    toolName = toolName,
                    args = toolInput,
                ),
            )

            val tool = toolsByName[toolName]
            val (result, success) =
                if (tool == null) {
                    logger.warn { "[AgentSimple] $name unknown tool: $toolName" }
                    "Tool not found: $toolName" to false
                } else {
                    var output = ""
                    var ok = true
                    val duration =
                        measureTime {
                            output =
                                try {
                                    tool.executeWithJson(toolInput)
                                } catch (e: Exception) {
                                    ok = false
                                    "Error: ${e.message}"
                                }
                        }
                    logger.info { "[AgentSimple] tool $toolName executed in $duration (turn $turnIndex)" }
                    output to ok
                }

            emit(
                ToolResponseEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    toolRequestId = toolRequestId,
                    toolName = toolName,
                    output = MessageContent.Text(result),
                    success = success,
                ),
            )

            results += toolCall to result
        }

        return results
    }

    private fun convertEventsToMessages(events: List<CaseEvent>): List<Message> {
        val messages = mutableListOf<Message>()
        val pendingToolCalls = mutableListOf<AssistantMessage.ToolCall>()
        val toolResponsesById =
            events
                .filterIsInstance<ToolResponseEvent>()
                .associateBy { it.toolRequestId }

        fun flushPendingToolCalls() {
            if (pendingToolCalls.isEmpty()) return
            messages += AssistantMessage.builder().toolCalls(pendingToolCalls.toList()).build()
            val responses =
                pendingToolCalls.mapNotNull { tc ->
                    toolResponsesById[tc.id()]?.let { resp ->
                        val text =
                            when (val o = resp.output) {
                                is MessageContent.Text -> o.content
                                else -> o.toString()
                            }
                        ToolResponseMessage.ToolResponse(tc.id(), tc.name(), text)
                    }
                }
            if (responses.isNotEmpty()) {
                messages += ToolResponseMessage.builder().responses(responses).build()
            }
            pendingToolCalls.clear()
        }

        for (event in events) {
            when (event) {
                is MessageEvent -> {
                    flushPendingToolCalls()
                    val text =
                        event.content
                            .filterIsInstance<MessageContent.Text>()
                            .joinToString("\n") { it.content }
                    when (event.actor.role) {
                        ActorRole.USER -> messages += UserMessage(text)
                        ActorRole.AGENT ->
                            messages +=
                                if (event.actor.id == id.toString()) {
                                    AssistantMessage(text)
                                } else {
                                    UserMessage("[${event.actor.displayName}]: $text")
                                }
                    }
                }
                is ToolRequestEvent -> {
                    val safeArgs = event.args?.takeIf { it.isNotBlank() } ?: "{}"
                    pendingToolCalls +=
                        AssistantMessage.ToolCall(
                            event.toolRequestId,
                            "function",
                            event.toolName,
                            safeArgs,
                        )
                }
                else -> Unit
            }
        }
        flushPendingToolCalls()
        return messages
    }

    companion object : KLogging()
}
