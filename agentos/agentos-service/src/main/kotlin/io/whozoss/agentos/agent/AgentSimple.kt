package io.whozoss.agentos.agent

import io.whozoss.agentos.metrics.ToolMetricsService
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.agent.Agent
import io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent
import io.whozoss.agentos.sdk.caseEvent.AnswerEvent
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.QuestionEvent
import io.whozoss.agentos.sdk.caseEvent.TextChunkEvent
import io.whozoss.agentos.sdk.caseEvent.ThinkingEvent
import io.whozoss.agentos.sdk.caseEvent.ToolRequestEvent
import io.whozoss.agentos.sdk.caseEvent.ToolResponseEvent
import io.whozoss.agentos.sdk.caseEvent.WarnEvent
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile
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
import org.springframework.ai.retry.NonTransientAiException
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.DefaultToolDefinition
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.TimeSource
import kotlin.time.measureTime

/**
 * Simple agent implementation with single LLM call.
 *
 * This agent delegates tool orchestration to the LLM itself:
 * 1. Converts events to messages (including tool calls/responses and image attachments)
 * 2. Makes a single LLM call with available tools
 * 3. LLM decides which tools to call and when
 * 4. Tools are wrapped to emit ToolRequestEvent and ToolResponseEvent
 * 5. Streams text progressively with TextChunkEvent
 *
 * Image handling mirrors AgentAdvanced: tool responses that produced images are injected
 * as follow-up [UserMessage] with [org.springframework.ai.content.Media] attachments in
 * [convertEventsToMessages]. Budget management (newest-first selection) is governed by
 * [imageCharCost] and [maxAttachedImages].
 *
 * This is simpler but gives less control over the orchestration loop
 * compared to AgentAdvanced.
 *
 */
class AgentSimple(
    override val metadata: EntityMetadata = EntityMetadata(),
    override val name: String,
    private val chatClient: ChatClient,
    private val tools: Collection<StandardTool<*>>,
    /** The namespace context block, sent as the first part of the system message. */
    val systemPrompt: String? = null,
    /** The effective system instructions passed to the LLM (agent instructions + integrations + user). */
    val instructions: String? = null,
    /** AgentOS UUID of the user who initiated the case, or null when unresolvable. */
    private val userId: UUID? = null,
    /** Identity-provider key of the user (e.g. email). Used by plugins that manage their own auth. */
    private val userExternalId: String? = null,
    /** Returns the live event list of the current case at the moment of invocation. */
    private val caseEventsProvider: () -> List<CaseEvent> = { emptyList() },
    override val llmProvider: String,
    override val llmModel: String,
    /** Metrics service for recording tool call telemetry. Null in tests that don't inject it. */
    private val toolMetricsService: ToolMetricsService? = null,
    /**
     * Char-equivalent cost of one attached image against the image budget.
     * Mirrors [AgentConfigProperties.imageCharCost] (default 6 000).
     */
    val imageCharCost: Int = 6_000,
    /**
     * Maximum images attached as Media across the whole prompt, newest first.
     * Mirrors [AgentConfigProperties.maxAttachedImages] (default 20).
     */
    val maxAttachedImages: Int = 20,
) : Agent {
    override fun run(
        events: List<CaseEvent>,
        shouldContinue: () -> Boolean,
    ): Flow<CaseEvent> =
        flow {
            val namespaceId = events.firstOrNull()?.namespaceId ?: throw IllegalArgumentException("No events provided")
            val caseId = events.firstOrNull()?.caseId ?: throw IllegalArgumentException("No events provided")

            // Channel to collect tool events from callbacks
            val toolEventChannel = Channel<CaseEvent>(Channel.UNLIMITED)

            try {
                // Convert events to messages
                val messages = convertEventsToMessages(events)

                // Build the system message from systemPrompt + instructions (either or both may be null)
                val effectiveSystemMessage =
                    listOfNotNull(systemPrompt, instructions)
                        .joinToString("\n")
                        .takeUnless { it.isBlank() }
                        ?.let { SystemMessage(it) }
                val allMessages =
                    if (effectiveSystemMessage != null) {
                        listOf(effectiveSystemMessage) + messages
                    } else {
                        messages
                    }

                logger.debug {
                    "[$name] sending ${allMessages.size} messages to LLM" +
                        (if (instructions != null) ", instructions length=${instructions.length} chars" else ", no instructions")
                }

                // Bail out immediately if an interrupt/kill was requested before
                // the LLM call even starts (e.g. kill fired while the previous
                // tool response was being stored).
                if (!shouldContinue()) {
                    emit(
                        AgentFinishedEvent(
                            namespaceId = namespaceId,
                            caseId = caseId,
                            agentId = id,
                            agentName = name,
                            llmProvider = llmProvider,
                            llmModel = llmModel,
                        ),
                    )
                    return@flow
                }

                emit(ThinkingEvent(namespaceId = namespaceId, caseId = caseId))

                // Shared timer: reset to markNow() each time the LLM hands back control
                // (prompt sent, or tool response returned). Measures pure LLM thinking time
                // per turn, including both tool-calling turns and the final text turn.
                val llmTurnMark = AtomicReference(TimeSource.Monotonic.markNow())
                val llmTurnIndex = AtomicInteger(1)

                // Convert StandardTool to ToolCallback with event emission
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

                // Convert stream to Flow.
                // takeWhile cancels the upstream reactive stream as soon as shouldContinue()
                // returns false, so we stop consuming the LLM HTTP stream immediately
                // rather than merely skipping chunks with return@collect.
                streamSpec.content().asFlow().takeWhile { shouldContinue() }.collect { chunk ->
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
                        logger.info {
                            "[AgentSimple] $name LLM turn ${llmTurnIndex.get()} answered in ${
                                llmTurnMark.get().elapsedNow()
                            }"
                        }
                    }

                    contentBuilder.append(chunk)
                    // Emit text chunk for progressive display
                    emit(
                        TextChunkEvent(
                            namespaceId = namespaceId,
                            caseId = caseId,
                            chunk = chunk,
                        ),
                    )
                }

                // Strip any conversation tags the LLM may have hallucinated in its response.
                // TextChunkEvents are left as-is (tags split across chunks are harmless noise
                // in the stream and render invisibly in markdown). Only the stored MessageEvent
                // needs to be clean.
                val content = contentBuilder.toString().stripConversationTags()

                // Close tool event channel and emit remaining events
                toolEventChannel.close()
                for (toolEvent in toolEventChannel) {
                    emit(toolEvent)
                }

                // Emit the final assistant message
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
                        llmProvider = llmProvider,
                        llmModel = llmModel,
                    ),
                )
            } catch (e: AgentInterrupt) {
                // Not an error: a tool requested a structured interruption of this agent run.
                // Drain any pending tool events before emitting orchestration events so the
                // event stream is always well-ordered: request → response → interrupt handling.
                toolEventChannel.close()
                for (toolEvent in toolEventChannel) {
                    emit(toolEvent)
                }
                emitInterruptAndFinishEvents(this@AgentSimple, e, namespaceId, caseId, logger)
            } catch (e: NonTransientAiException) {
                emitProviderErrorAndFinishEvents(this@AgentSimple, e, namespaceId, caseId, logger)
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
                        llmProvider = llmProvider,
                        llmModel = llmModel,
                    ),
                )
            }
        }

    /**
     * Convert CaseEvents to Spring AI Messages for LLM context.
     * Includes tool calls and responses for proper conversation history.
     * Converts other agents to "user" role for LLM compatibility.
     *
     * When the last user [MessageEvent] carries a non-null [MessageEvent.sessionContext],
     * it is injected as an extra [UserMessage] immediately before that message.
     * Session context on earlier messages is ignored — only the current turn's context
     * is relevant to the LLM.
     *
     * Image handling: tool responses that include images are replayed with a follow-up
     * [UserMessage] carrying [org.springframework.ai.content.Media] attachments, mirroring
     * the pattern used by AgentAdvancedContext. Budget management (newest-first) is governed
     * by [maxAttachedImages] and [imageCharCost]. Tool responses whose images exceed the budget
     * receive a text marker instead.
     */
    internal fun convertEventsToMessages(events: List<CaseEvent>): List<Message> {
        val messages = mutableListOf<Message>()
        val toolCallsForCurrentMessage = mutableListOf<AssistantMessage.ToolCall>()
        val toolResponses = mutableMapOf<String, ToolResponseEvent>()

        // First pass: collect tool responses by ID
        events.filterIsInstance<ToolResponseEvent>().forEach { toolResponse ->
            toolResponses[toolResponse.toolRequestId] = toolResponse
        }

        // Determine which tool responses get image Media attachments (newest-first budget walk)
        val mediaRequestIds = selectImageRequestIds(events, toolResponses)

        val lastUserMessageIndex =
            events.indexOfLast {
                it is MessageEvent && it.actor.role == ActorRole.USER
            }

        // Second pass: build messages with tool calls
        events.forEachIndexed { index, event ->
            when (event) {
                is MessageEvent -> {
                    // Inject session context as a UserMessage immediately before the last user message.
                    if (index == lastUserMessageIndex) {
                        event.sessionContextPromptText()?.let { messages.add(UserMessage(it)) }
                    }
                    flushPendingToolCalls(messages, toolCallsForCurrentMessage, toolResponses, mediaRequestIds)
                    messages.add(event.toSpringAiMessage(id.toString()))
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

                // QuestionEvent: the agent asked the user a question and terminated its run.
                // Rendered as AssistantMessage (the agent's own voice). Any accumulated tool
                // calls are flushed first so the message list stays well-formed (every
                // AssistantMessage with tool_calls must be followed by a ToolResponseMessage).
                is QuestionEvent -> {
                    flushPendingToolCalls(messages, toolCallsForCurrentMessage, toolResponses, mediaRequestIds)
                    messages.add(AssistantMessage(event.toPromptText()))
                }

                // AnswerEvent: the user's reply to a QuestionEvent.
                // Rendered as UserMessage, consistent with MessageEvent USER rendering.
                // Tool calls are flushed first for the same well-formedness reason.
                is AnswerEvent -> {
                    flushPendingToolCalls(messages, toolCallsForCurrentMessage, toolResponses, mediaRequestIds)
                    messages.add(UserMessage(event.answer))
                }

                else -> {
                    // Ignore other event types for message conversion
                }
            }
        }

        // Flush any tool calls that trail the end of the event list without a following
        // conversational message (e.g. the last thing the agent did was call a tool, then
        // the run was interrupted). Args are already normalised in the accumulation loop above.
        flushPendingToolCalls(messages, toolCallsForCurrentMessage, toolResponses, mediaRequestIds)

        return messages
    }

    /**
     * Flush accumulated tool calls into [messages] as a well-formed
     * `AssistantMessage(tool_calls) + ToolResponseMessage` pair, then clear the accumulator.
     *
     * ## Why this invariant exists
     *
     * OpenAI (and Anthropic) require that every `AssistantMessage` carrying `tool_calls`
     * is immediately followed by a `ToolResponseMessage` that covers **each** `tool_call_id`
     * referenced in that assistant turn. Violating this constraint causes a hard HTTP 400
     * from the provider. Because `convertEventsToMessages` accumulates tool calls across
     * `ToolRequestEvent` entries and only materialises the `AssistantMessage` when a
     * conversational boundary is reached (a `MessageEvent`, `QuestionEvent`, `AnswerEvent`,
     * or the end of the list), this flush must be called at every such boundary — and only
     * at those boundaries.
     *
     * When a response is missing (e.g. the run was interrupted mid-tool), a
     * `[No response recorded]` placeholder is inserted so the message list remains
     * syntactically valid even for incomplete histories.
     *
     * This function is a no-op when [pendingToolCalls] is empty.
     */
    private fun flushPendingToolCalls(
        messages: MutableList<Message>,
        pendingToolCalls: MutableList<AssistantMessage.ToolCall>,
        toolResponses: Map<String, ToolResponseEvent>,
        mediaRequestIds: Set<String>,
    ) {
        if (pendingToolCalls.isEmpty()) return

        messages.add(
            AssistantMessage
                .builder()
                .toolCalls(pendingToolCalls.toList())
                .build(),
        )
        val toolResponseMessages =
            pendingToolCalls.map { toolCall ->
                val response = toolResponses[toolCall.id()]
                val output = response?.let { toolResponseText(it, mediaRequestIds) } ?: "[No response recorded]"
                ToolResponseMessage.ToolResponse(toolCall.id(), toolCall.name(), output)
            }
        messages.add(ToolResponseMessage.builder().responses(toolResponseMessages).build())

        // Inject image Media messages for tool calls within the image budget
        pendingToolCalls.forEach { toolCall ->
            val response = toolResponses[toolCall.id()]
            if (response != null && response.images.isNotEmpty() && toolCall.id() in mediaRequestIds) {
                messages.add(toolImagesUserMessage(toolCall.name(), response.images))
            }
        }
        pendingToolCalls.clear()
    }

    /**
     * Newest-first walk over events to decide which tool responses get image [Media] attachments.
     *
     * Mirrors AgentAdvancedContext's budget logic: walks events in reverse, attaches images
     * for a response when the [maxAttachedImages] cap allows, and stops when a response would
     * push the total over the cap. Returns the set of toolRequestIds whose images are attached.
     */
    private fun selectImageRequestIds(
        events: List<CaseEvent>,
        toolResponses: Map<String, ToolResponseEvent>,
    ): Set<String> {
        val withMedia = mutableSetOf<String>()
        var imagesAttached = 0

        for (event in events.reversed()) {
            if (event !is ToolRequestEvent) continue
            val response = toolResponses[event.toolRequestId] ?: continue
            val images = response.images
            if (images.isEmpty()) continue
            if (imagesAttached + images.size <= maxAttachedImages) {
                withMedia.add(event.toolRequestId)
                imagesAttached += images.size
            }
            // Do not break: a later (older) response that fits under the cap after a larger one
            // was skipped should still be considered. (Consistent with AgentAdvanced behavior.)
        }
        return withMedia
    }

    /**
     * Textual rendering of a tool response for the ToolResponseMessage.
     *
     * When a response has images within the [mediaRequestIds] budget, the text is left
     * plain — the LLM will see them via the follow-up UserMessage with Media attachments.
     * When a response has images that are outside the budget (oldest, evicted), a marker
     * is appended telling the LLM the images are no longer attached and it should re-call
     * the tool if needed.
     */
    private fun toolResponseText(
        response: ToolResponseEvent,
        mediaRequestIds: Set<String>,
    ): String {
        val text =
            when (val content = response.output) {
                is MessageContent.Text -> content.content
                is MessageContent.Image -> "[image ${content.mimeType} ${content.width}x${content.height}]"
            }
        return when {
            response.images.isEmpty() -> text

            response.toolRequestId in mediaRequestIds -> text

            // images come via follow-up UserMessage
            else -> text + "\n[${response.images.size} image(s) no longer attached, call the tool again if needed]"
        }
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
        namespaceId: UUID,
        caseId: UUID,
        eventChannel: Channel<CaseEvent>,
        llmTurnMark: AtomicReference<TimeSource.Monotonic.ValueTimeMark>,
        llmTurnIndex: AtomicInteger,
    ): ToolCallback =
        object : ToolCallback {
            // Expose the tool's own schema verbatim — no reflection-based generation.
            private val definition =
                DefaultToolDefinition
                    .builder()
                    .name(tool.name)
                    .description(tool.description)
                    .inputSchema(tool.inputSchema)
                    .build()

            override fun getToolDefinition() = definition

            fun sendEvent(event: CaseEvent) {
                eventChannel.trySend(event)
            }

            override fun call(toolInput: String): String {
                val toolRequestId = UUID.randomUUID().toString()

                // The LLM decided to call this tool: log how long it thought since
                // the prompt was sent (or since the previous tool response was returned).
                val turn = llmTurnIndex.get()
                logger.info { "$name LLM turn $turn answered in ${llmTurnMark.get().elapsedNow()}" }

                logger.trace { "[$name] tool '${tool.name}' called with args: $toolInput" }

                sendEvent(
                    ToolRequestEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        toolRequestId = toolRequestId,
                        toolName = tool.name,
                        args = toolInput,
                    ),
                )

                // Filter case events to only those belonging to this integration.
                // Tool names follow the "INTEGRATION_NAME__toolName" convention, so the
                // prefix before "__" is the integration identity. Events from other
                // integrations are not visible to this tool — no cross-integration leakage.
                val integrationPrefix = tool.name.substringBefore("__", missingDelimiterValue = "")
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
                val context =
                    ToolContext(
                        namespaceId = namespaceId,
                        userId = userId,
                        userExternalId = userExternalId,
                        caseEvents = filteredEvents,
                    )

                val executionResult: ToolExecutionResult
                val sample = toolMetricsService?.startTimer()
                val toolDuration =
                    measureTime {
                        executionResult =
                            try {
                                runBlocking(Dispatchers.IO) { tool.executeWithJson(toolInput, context) }
                            } catch (e: AgentInterrupt) {
                                // Interrupt is not an error: emit a successful response so traces
                                // are complete, then re-throw the signal for the flow catch block.
                                toolMetricsService?.stopTimerAndSendMetrics(
                                    sample,
                                    tool.name,
                                    name,
                                    namespaceId,
                                    success = true,
                                )
                                val message =
                                    when (e) {
                                        is AgentInterrupt.Redirect -> "Redirecting to agent '${e.targetAgentName}'."
                                        is AgentInterrupt.AwaitAnswer -> "Waiting for the user's answer."
                                    }
                                sendEvent(
                                    ToolResponseEvent(
                                        namespaceId = namespaceId,
                                        caseId = caseId,
                                        toolRequestId = toolRequestId,
                                        toolName = tool.name,
                                        output = MessageContent.Text(message),
                                        success = true,
                                    ),
                                )
                                throw e
                            } catch (e: Exception) {
                                toolMetricsService?.stopTimerAndSendMetrics(
                                    sample,
                                    tool.name,
                                    name,
                                    namespaceId,
                                    success = false,
                                )
                                sendEvent(
                                    ToolResponseEvent(
                                        namespaceId = namespaceId,
                                        caseId = caseId,
                                        toolRequestId = toolRequestId,
                                        toolName = tool.name,
                                        output = MessageContent.Text("Error: ${e.message}"),
                                        success = false,
                                    ),
                                )
                                throw e
                            }
                    }
                logger.info { "tool '${tool.name}' executed in $toolDuration" }
                // Success path: stop the timer here (exception paths stop it before re-throwing).
                toolMetricsService?.stopTimerAndSendMetrics(
                    sample,
                    tool.name,
                    name,
                    namespaceId,
                    success = executionResult.success,
                )

                // Reset the turn mark so the next measurement starts from when
                // we hand the tool result back to the LLM.
                llmTurnMark.set(TimeSource.Monotonic.markNow())
                llmTurnIndex.incrementAndGet()

                sendEvent(
                    ToolResponseEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        toolRequestId = toolRequestId,
                        toolName = tool.name,
                        output = MessageContent.Text(executionResult.output),
                        success = executionResult.success,
                        durationMs = toolDuration.inWholeMilliseconds,
                        toolMetadata = executionResult.metadata,
                        // Images are preserved on the event (persistence, SSE).
                        // They will be delivered to the LLM via the conversation history
                        // on subsequent turns (convertEventsToMessages injects UserMessage+Media).
                        images = executionResult.images,
                    ),
                )

                // ToolCallback.call() returns text only — images reach the LLM via the
                // rebuilt conversation context in convertEventsToMessages(), not here.
                return executionResult.output
            }
        }

    companion object : KLogging()
}
