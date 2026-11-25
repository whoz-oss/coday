package io.whozoss.agentos.agent

import io.whozoss.agentos.chat.UsageTrackingChatModel
import io.whozoss.agentos.logging.withMdc
import io.whozoss.agentos.metrics.ToolMetricsService
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.agent.Agent
import io.whozoss.agentos.sdk.aiProvider.ReasoningEffort
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
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import io.whozoss.agentos.usage.CostRunStopped
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.selects.select
import mu.KLogging
import org.slf4j.MDC
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
 * Control-flow signal thrown by an [AgentSimple] tool callback right after a tool that produced images
 * has recorded its [ToolResponseEvent]. It ends the current Spring AI streamed call so that [AgentSimple]
 * can rebuild the conversation (where [toolImagesUserMessage] attaches the pixels) and start a fresh call:
 * `ToolCallback.call` can only return text, so this is the only way the model sees the images in the same turn.
 *
 * Spring AI only converts `ToolExecutionException` into a tool result, so this propagates out of the
 * stream unwrapped, like [AgentInterrupt]; it is still looked up through the cause chain. `internal`
 * because [io.whozoss.agentos.chat.UsageTrackingChatModel] must not count it as a failed run.
 */
internal class ToolImagesProduced : RuntimeException("A tool produced images", null, false, false)

/**
 * Simple agent implementation: the LLM orchestrates the tools through one streamed call.
 *
 * This agent delegates tool orchestration to the LLM itself:
 * 1. Converts events to messages (including tool calls/responses and image attachments)
 * 2. Makes a streamed LLM call with available tools
 * 3. LLM decides which tools to call and when
 * 4. Tools are wrapped to emit ToolRequestEvent and ToolResponseEvent
 * 5. Streams text progressively with TextChunkEvent
 *
 * Image handling mirrors AgentAdvanced: tool responses that produced images are injected
 * as follow-up [UserMessage] with [org.springframework.ai.content.Media] attachments in
 * [convertEventsToMessages]. Budget management (newest-first selection) is governed by
 * [maxAttachedImages].
 *
 * A tool callback can only return text, so a tool that produced images ends the current Spring AI call
 * ([ToolImagesProduced]) and the run continues with a new call whose messages are rebuilt from the events
 * of the case plus those emitted so far in this run: the very next LLM call sees the images. At most
 * [MAX_IMAGE_CONTINUATIONS] such restarts happen per run; past it, a tool with images behaves as before and
 * its images are only attached from the next user turn on.
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
    /** Reasoning effort the provider receives, carried on the agent's messages. */
    override val reasoningEffort: ReasoningEffort? = null,
    /** Reasoning effort asked (the case's, else the model's), carried on the agent's messages. */
    override val requestedReasoningEffort: ReasoningEffort? = null,
    /** Metrics service for recording tool call telemetry. Null in tests that don't inject it. */
    private val toolMetricsService: ToolMetricsService? = null,
    /**
     * Maximum images attached as Media across the whole prompt, newest first.
     * Mirrors [AgentConfigProperties.maxAttachedImages] (default 20).
     */
    val maxAttachedImages: Int = 20,
    /**
     * Whether a provider HTTP error shown to the case carries the provider response body
     * (`agentos.ai-provider-errors.expose-response-body`, see [io.whozoss.agentos.chat.AiProviderErrorProperties]).
     */
    private val exposeProviderResponseBody: Boolean = true,
) : Agent {
    override fun run(
        events: List<CaseEvent>,
        shouldContinue: () -> Boolean,
    ): Flow<CaseEvent> =
        flow {
            val namespaceId = events.firstOrNull()?.namespaceId ?: throw IllegalArgumentException("No events provided")
            val caseId = events.firstOrNull()?.caseId ?: throw IllegalArgumentException("No events provided")

            // Tool callbacks run on Spring AI / Reactor threads where the thread-local MDC of this
            // coroutine is absent: capture it once, at the start of the run, and re-apply it in each callback.
            val logContext: Map<String, String>? = MDC.getCopyOfContextMap()

            // Channel to collect tool events from callbacks
            val toolEventChannel = Channel<CaseEvent>(Channel.UNLIMITED)

            // Size of the last message list sent to the provider, for the summary of a rejected request.
            var sentMessageCount: Int? = null
            fun requestSummary() =
                ProviderRequestSummary(
                    llmProvider = llmProvider,
                    llmModel = llmModel,
                    messageCount = sentMessageCount,
                    toolNames = tools.map { it.name },
                )

            try {
                // Build the system message from systemPrompt + instructions (either or both may be null)
                val effectiveSystemMessage =
                    listOfNotNull(systemPrompt, instructions)
                        .joinToString("\n")
                        .takeUnless { it.isBlank() }
                        ?.let { SystemMessage(it) }

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

                // Timing is diagnostic-only: do not even take a monotonic-clock reading unless
                // DEBUG is enabled. When enabled, the mark is reset each time the LLM hands back
                // control (prompt sent, or tool response returned), so it measures pure LLM
                // thinking time per turn, including tool-calling and final-text turns.
                val latencyLoggingEnabled = logger.isDebugEnabled
                val llmTurnMark =
                    if (latencyLoggingEnabled) AtomicReference(TimeSource.Monotonic.markNow()) else null
                val llmTurnIndex = if (latencyLoggingEnabled) AtomicInteger(1) else null

                // How many times a tool with images already restarted the call in this run.
                val imageContinuations = AtomicInteger()

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
                            logContext,
                            imageContinuations,
                            shouldContinue,
                        )
                    }

                // Durable events (tool requests and responses, intermediate agent messages) emitted so far
                // in this run: the case does not hold them yet, but the next call must see them.
                val runEvents = mutableListOf<CaseEvent>()
                var content = ""
                // Set from the last response when the tool round limit cut the run short.
                var maxIterationsReached: Int? = null

                while (true) {
                    val allMessages = listOfNotNull(effectiveSystemMessage) + convertEventsToMessages(events + runEvents)
                    sentMessageCount = allMessages.size

                    logger.debug {
                        "[AgentSimple] agent=$name sending ${allMessages.size} messages to LLM $llmProvider/$llmModel with ${tools.size} tool(s)" +
                            (if (instructions != null) ", instructions length=${instructions.length} chars" else ", no instructions")
                    }

                    val iterationEvents = mutableListOf<CaseEvent>()
                    val outcome =
                        streamOnce(
                            allMessages,
                            toolCallbacks,
                            toolEventChannel,
                            iterationEvents,
                            namespaceId,
                            caseId,
                            shouldContinue,
                            llmTurnMark,
                            llmTurnIndex,
                        )

                    if (!outcome.imagesProduced) {
                        // Strip any conversation tags the LLM may have hallucinated in its response.
                        // TextChunkEvents are left as-is (tags split across chunks are harmless noise
                        // in the stream and render invisibly in markdown). Only the stored MessageEvent
                        // needs to be clean.
                        content = outcome.content.stripConversationTags()
                        maxIterationsReached = outcome.maxIterationsReached
                        break
                    }

                    // A tool produced images: the call was cut short so that the next one is built with them.
                    // What the model said before calling the tool becomes a message of its own, placed before
                    // the tool exchange in the rebuilt history so that it does not end on an assistant message.
                    val partialText = outcome.content.stripConversationTags()
                    val partialMessage =
                        partialText.takeIf { it.isNotEmpty() }?.let { agentMessage(namespaceId, caseId, it) }
                    partialMessage?.let { emit(it) }
                    runEvents += listOfNotNull(partialMessage) + iterationEvents

                    emit(ThinkingEvent(namespaceId = namespaceId, caseId = caseId))
                    if (!shouldContinue()) break
                }

                // Close tool event channel and emit remaining events
                toolEventChannel.close()
                for (toolEvent in toolEventChannel) {
                    emit(toolEvent)
                }

                maxIterationsReached?.let { emitMaxIterationsWarning(namespaceId, caseId, it) }

                // Emit the final assistant message
                if (content.isNotEmpty()) {
                    emit(agentMessage(namespaceId, caseId, content))
                }

                emit(
                    AgentFinishedEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        agentId = id,
                        agentName = name,
                        llmProvider = llmProvider,
                        llmModel = llmModel,
                        maxIterationsReached = maxIterationsReached,
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
                emitProviderErrorAndFinishEvents(this@AgentSimple, e, namespaceId, caseId, logger, requestSummary())
            } catch (e: Exception) {
                if (generateSequence<Throwable>(e) { it.cause }.any { it is CostRunStopped }) {
                    // Tool-only rounds may still have events buffered when a cost stop arrives.
                    toolEventChannel.close()
                    for (toolEvent in toolEventChannel) {
                        emit(toolEvent)
                    }
                }
                handleGenericAgentException(
                    this@AgentSimple,
                    e,
                    namespaceId,
                    caseId,
                    logger,
                    requestSummary(),
                    exposeProviderResponseBody,
                )
            }
        }

    /** What one streamed call (with the Spring AI tool rounds it ran) produced. */
    private class StreamOutcome(
        /** Text streamed by the model, raw. */
        val content: String,
        val maxIterationsReached: Int?,
        /** True when the call was cut short because a tool produced images ([ToolImagesProduced]). */
        val imagesProduced: Boolean,
    )

    private fun agentMessage(
        namespaceId: UUID,
        caseId: UUID,
        text: String,
    ) = MessageEvent(
        namespaceId = namespaceId,
        caseId = caseId,
        actor = Actor(id.toString(), name, ActorRole.AGENT),
        content = listOf(MessageContent.Text(text)),
        llmProvider = llmProvider,
        llmModel = llmModel,
        reasoningEffort = reasoningEffort,
        requestedReasoningEffort = requestedReasoningEffort,
    )

    /** Emits what the tool callbacks queued, and records the durable ones in [durableEvents]. */
    private suspend fun FlowCollector<CaseEvent>.drainToolEvents(
        channel: Channel<CaseEvent>,
        durableEvents: MutableList<CaseEvent>,
    ) {
        while (true) {
            val toolEvent = channel.tryReceive().getOrNull() ?: break
            emit(toolEvent)
            if (toolEvent is ToolRequestEvent || toolEvent is ToolResponseEvent) durableEvents.add(toolEvent)
        }
    }

    /**
     * Runs one streamed call of [messages] with [toolCallbacks], emitting text chunks and tool events live.
     *
     * Returns normally when the call completed, or with [StreamOutcome.imagesProduced] when a tool callback
     * ended it with [ToolImagesProduced]. In that case the tool events still queued are emitted before
     * returning. Every durable tool event of the call is added to [durableEvents].
     */
    private suspend fun FlowCollector<CaseEvent>.streamOnce(
        messages: List<Message>,
        toolCallbacks: List<ToolCallback>,
        toolEventChannel: Channel<CaseEvent>,
        durableEvents: MutableList<CaseEvent>,
        namespaceId: UUID,
        caseId: UUID,
        shouldContinue: () -> Boolean,
        llmTurnMark: AtomicReference<TimeSource.Monotonic.ValueTimeMark>?,
        llmTurnIndex: AtomicInteger?,
    ): StreamOutcome {
        // Collect tool events and streamed chunks concurrently. Spring AI executes a
        // tool callback while its content stream is still open; draining the callback
        // channel only when a text chunk arrives used to hide a long-running tool (and
        // the following LLM round) from the UI until the model eventually produced text.
        // Keeping those two sources separate makes the live activity timeline truthful.
        val contentBuilder = StringBuilder()
        var currentTurnLogged = false
        val latencyLoggingEnabled = logger.isDebugEnabled
        var maxIterationsReached: Int? = null
        try {
            val prompt = chatClient.prompt(Prompt(messages))

            // Add tool callbacks if available
            val promptWithTools =
                if (toolCallbacks.isNotEmpty()) {
                    prompt.toolCallbacks(toolCallbacks)
                } else {
                    prompt
                }

            // Use stream() for progressive text display
            val streamSpec = promptWithTools.stream()

            coroutineScope {
                // Keep the producer/consumer bridge bounded: unlike tool callbacks,
                // streamed text can safely suspend until the event flow catches up.
                // This preserves streaming back-pressure instead of accumulating an
                // arbitrarily large response in memory.
                val textChunkChannel = Channel<String>(Channel.BUFFERED)
                val streamJob =
                    launch {
                        try {
                            // takeWhile cancels the upstream reactive stream as soon as
                            // shouldContinue() returns false, rather than merely skipping chunks.
                            streamSpec.chatResponse().asFlow().takeWhile { shouldContinue() }.collect { response ->
                                response.metadata.get<Int>(UsageTrackingChatModel.MAX_ITERATIONS_REACHED)?.let {
                                    maxIterationsReached = it
                                }
                                val chunk = response.result?.output?.text
                                if (!chunk.isNullOrEmpty()) textChunkChannel.send(chunk)
                            }
                            textChunkChannel.close()
                        } catch (e: Throwable) {
                            textChunkChannel.close(e)
                            // Cancellation is control flow, not a stream failure. Let it
                            // propagate through the structured scope so the HTTP stream and
                            // every child coroutine are cancelled promptly.
                            if (e is CancellationException) throw e
                        }
                    }

                var streamCompleted = false
                while (!streamCompleted) {
                    select<Unit> {
                        toolEventChannel.onReceiveCatching { received ->
                            received.exceptionOrNull()?.let { throw it }
                            received.getOrNull()?.let { toolEvent ->
                                emit(toolEvent)
                                if (toolEvent is ToolRequestEvent) durableEvents.add(toolEvent)
                                if (toolEvent is ToolResponseEvent) {
                                    durableEvents.add(toolEvent)
                                    currentTurnLogged = false
                                    // The tool response has been handed back to Spring AI.
                                    // Tell live consumers that the next LLM turn is underway.
                                    emit(ThinkingEvent(namespaceId = namespaceId, caseId = caseId))
                                }
                            }
                        }
                        textChunkChannel.onReceiveCatching { received ->
                            received.exceptionOrNull()?.let { throw it }
                            val chunk = received.getOrNull()
                            if (chunk == null) {
                                streamCompleted = true
                            } else {
                                // Log LLM thinking time once per turn on its first text chunk.
                                if (latencyLoggingEnabled && !currentTurnLogged) {
                                    currentTurnLogged = true
                                    logger.debug {
                                        "[AgentSimple] agent=$name case=$caseId LLM turn=${llmTurnIndex!!.get()} " +
                                            "first response after ${llmTurnMark!!.get().elapsedNow()}"
                                    }
                                }

                                contentBuilder.append(chunk)
                                emit(
                                    TextChunkEvent(
                                        namespaceId = namespaceId,
                                        caseId = caseId,
                                        chunk = chunk,
                                    ),
                                )
                            }
                        }
                    }
                }
                streamJob.join()
            }
        } catch (e: Exception) {
            if (generateSequence<Throwable>(e) { it.cause }.none { it is ToolImagesProduced }) throw e
            // The tool response was queued just before the signal: it may not have been received yet.
            drainToolEvents(toolEventChannel, durableEvents)
            return StreamOutcome(contentBuilder.toString(), maxIterationsReached, imagesProduced = true)
        }
        return StreamOutcome(contentBuilder.toString(), maxIterationsReached, imagesProduced = false)
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
     * by [maxAttachedImages]. Tool responses whose images exceed the budget
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

        // Determine which tool responses get image Media attachments (newest-first budget walk).
        // maxDetailedChars = Int.MAX_VALUE disables text compression — AgentSimple has no history
        // compression; imageCharCost value is neutral since it is only multiplied when images are
        // attached and the budget break never triggers.
        val plan =
            ToolReplayPlanner(
                maxDetailedChars = Int.MAX_VALUE,
                maxAttachedImages = maxAttachedImages,
                imageCharCost = 0,
            ).plan(events)

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
                    flushPendingToolCalls(messages, toolCallsForCurrentMessage, toolResponses, plan)
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
                    flushPendingToolCalls(messages, toolCallsForCurrentMessage, toolResponses, plan)
                    messages.add(AssistantMessage(event.toPromptText()))
                }

                // AnswerEvent: the user's reply to a QuestionEvent.
                // Rendered as UserMessage, consistent with MessageEvent USER rendering.
                // Tool calls are flushed first for the same well-formedness reason.
                is AnswerEvent -> {
                    flushPendingToolCalls(messages, toolCallsForCurrentMessage, toolResponses, plan)
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
        flushPendingToolCalls(messages, toolCallsForCurrentMessage, toolResponses, plan)

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
     * The accumulated calls come from recorded [ToolRequestEvent]s only: the calls of an assistant round
     * that a [ToolImagesProduced] cut short before they ran have no event, so they are simply absent.
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
        plan: ToolReplayPlan,
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
                val output = response?.let { toolResponseText(it, plan) } ?: "[No response recorded]"
                ToolResponseMessage.ToolResponse(toolCall.id(), toolCall.name(), output)
            }
        messages.add(ToolResponseMessage.builder().responses(toolResponseMessages).build())

        // Inject image Media messages for tool calls within the image budget
        pendingToolCalls.forEach { toolCall ->
            val response = toolResponses[toolCall.id()]
            if (response != null && plan.hasAttachedMedia(toolCall.id())) {
                messages.add(toolImagesUserMessage(toolCall.name(), response.images))
            }
        }
        pendingToolCalls.clear()
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
     *
     * A tool that produced images throws [ToolImagesProduced] once its [ToolResponseEvent] is queued, while
     * [imageContinuations] is below [MAX_IMAGE_CONTINUATIONS] and [shouldContinue] holds.
     */
    private fun createToolCallbackWithEvents(
        tool: StandardTool<*>,
        namespaceId: UUID,
        caseId: UUID,
        eventChannel: Channel<CaseEvent>,
        llmTurnMark: AtomicReference<TimeSource.Monotonic.ValueTimeMark>?,
        llmTurnIndex: AtomicInteger?,
        logContext: Map<String, String>?,
        imageContinuations: AtomicInteger,
        shouldContinue: () -> Boolean,
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

            override fun call(toolInput: String): String = withMdc(logContext) { callWithLogContext(toolInput) }

            private fun callWithLogContext(toolInput: String): String {
                val toolRequestId = UUID.randomUUID().toString()

                // The LLM decided to call this tool: in DEBUG, log how long it thought
                // since the prompt was sent (or since the previous tool response was returned).
                if (llmTurnMark != null && llmTurnIndex != null) {
                    logger.debug {
                        "[AgentSimple] agent=$name case=$caseId LLM turn=${llmTurnIndex.get()} " +
                            "requested tool=${tool.name} after ${llmTurnMark.get().elapsedNow()}"
                    }
                }

                logger.trace { "[AgentSimple] agent=$name tool '${tool.name}' called with args: $toolInput" }

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
                                    is ToolResponseEvent -> event.toolName.startsWith("${integrationPrefix}__")
                                    else -> false
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
                        toolRequestId = toolRequestId,
                    )

                val executionResult: ToolExecutionResult
                val sample = toolMetricsService?.startTimer()
                val toolDuration =
                    measureTime {
                        executionResult =
                            try {
                                // MDCContext() captures the MDC of this thread, which withMdc has just merged:
                                // the run's keys plus the foreign keys (e.g. an infra trace id) of the callback thread.
                                runBlocking(Dispatchers.IO + MDCContext()) {
                                    tool.executeWithJson(toolInput, context)
                                }
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
                                        structuredOutput = (e as? AgentInterrupt.AwaitAnswer)?.structuredOutput,
                                    ),
                                )
                                throw e
                            } catch (e: Exception) {
                                // Cancellation and thread interruption (runBlocking above) are signals,
                                // not tool failures: keep unwinding the run instead of turning them
                                // into a result the LLM would be re-prompted on.
                                if (e is CancellationException || e is InterruptedException) throw e
                                // A tool failure is a tool result, not a run failure: the message goes
                                // back to the LLM so it can correct its call (same contract as
                                // AgentAdvanced.executeTool). Spring AI only turns ToolExecutionException
                                // into a tool result; any other exception escaping call() errors the
                                // stream and ends the turn.
                                logger.warn(e) { "[AgentSimple] error during tool execution for ${tool.name}" }
                                val reason = e.message ?: e::class.simpleName ?: "unknown error"
                                ToolExecutionResult.error(
                                    "Error executing tool: $reason",
                                    errorType = e::class.simpleName,
                                    errorMessage = e.message,
                                )
                            }
                    }
                if (llmTurnMark != null) {
                    "tool '${tool.name}' executed in $toolDuration, success=${executionResult.success}" +
                            (executionResult.errorType?.let { ", errorType=$it" } ?: "")
                }
                // Success and tool-error paths stop the timer here. AgentInterrupt stops it before
                // rethrowing; cancellation/interruption rethrow without recording a metric.
                toolMetricsService?.stopTimerAndSendMetrics(
                    sample,
                    tool.name,
                    name,
                    namespaceId,
                    success = executionResult.success,
                )

                // Reset the turn mark so the next measurement starts from when
                // we hand the tool result back to the LLM.
                if (llmTurnMark != null && llmTurnIndex != null) {
                    llmTurnMark.set(TimeSource.Monotonic.markNow())
                    llmTurnIndex.incrementAndGet()
                }

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
                        // Images are preserved on the event (persistence, SSE) and attached to the
                        // conversation by convertEventsToMessages (UserMessage+Media).
                        images = executionResult.images,
                        structuredOutput = executionResult.structuredOutput,
                    ),
                )

                // ToolCallback.call() returns text only, so the images cannot travel with this return value:
                // end the Spring AI call here and let run() start the next one from the rebuilt conversation,
                // where the images are attached. Past the continuation budget the call goes on with the text
                // alone, and the images are attached from the next user turn on.
                if (executionResult.images.isNotEmpty() &&
                    imageContinuations.get() < MAX_IMAGE_CONTINUATIONS &&
                    shouldContinue()
                ) {
                    imageContinuations.incrementAndGet()
                    throw ToolImagesProduced()
                }
                return executionResult.output
            }
        }

    companion object : KLogging() {
        /**
         * How many times, in one run, a tool that produced images may restart the LLM call so that the model
         * sees them right away. Bounds the extra calls of a model looping on image tools.
         */
        internal const val MAX_IMAGE_CONTINUATIONS = 8
    }
}
