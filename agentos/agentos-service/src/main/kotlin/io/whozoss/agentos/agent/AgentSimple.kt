package io.whozoss.agentos.agent

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.agent.Agent
import io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent
import io.whozoss.agentos.sdk.caseEvent.AnswerEvent
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.ConfirmationResolvedEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.PendingConfirmationEvent
import io.whozoss.agentos.sdk.caseEvent.TextChunkEvent
import io.whozoss.agentos.sdk.caseEvent.ThinkingEvent
import io.whozoss.agentos.sdk.caseEvent.ToolRequestEvent
import io.whozoss.agentos.sdk.caseEvent.ToolResponseEvent
import io.whozoss.agentos.sdk.caseEvent.WarnEvent
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.reactive.asFlow
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
 *
 */
class AgentSimple(
    override val metadata: EntityMetadata = EntityMetadata(),
    override val name: String,
    private val chatClient: ChatClient,
    private val tools: Collection<StandardTool<*>>,
    /** The effective system instructions passed to the LLM, after namespace context injection. */
    val instructions: String? = null,
    /** AgentOS UUID of the user who initiated the case, or null when unresolvable. */
    private val userId: UUID? = null,
    /** Identity-provider key of the user (e.g. email). Used by plugins that manage their own auth. */
    private val userExternalId: String? = null,
    /** Returns the live event list of the current case at the moment of invocation. */
    private val caseEventsProvider: () -> List<CaseEvent> = { emptyList() },
    /**
     * Drives the WZ-31596 confirmation flow. Null disables confirmation for this agent
     * — tools with `supportsConfirmation = true` will be rejected at call time to avoid
     * applying side-effects without an approval path.
     */
    private val confirmationManager: ConfirmationManager? = null,
    /** ObjectMapper for serialising/deserialising confirmation payloads. */
    private val objectMapper: ObjectMapper? = null,
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
                        ),
                    )
                    return@flow
                }

                // Resolve any pending confirmation BEFORE building messages and accumulate the
                // resolution events into `effectiveEvents` — otherwise the LLM history would show
                // the placeholder tool_result + the user's reply with no link, pushing the LLM
                // to re-call the original tool.
                val effectiveEvents = events.toMutableList()
                val unresolvedPending = findUnresolvedPendingConfirmation(events)
                if (unresolvedPending != null) {
                    val resolved =
                        handleConfirmationResolution(
                            events = events,
                            pending = unresolvedPending,
                            namespaceId = namespaceId,
                            caseId = caseId,
                            appendTo = effectiveEvents,
                        )
                    if (!resolved) {
                        // Ambiguous reply: pending stays alive, WarnEvent already emitted,
                        // no point burning an LLM turn — let the next user message retry.
                        emit(
                            AgentFinishedEvent(
                                namespaceId = namespaceId,
                                caseId = caseId,
                                agentId = id,
                                agentName = name,
                            ),
                        )
                        return@flow
                    }
                    // Resolved: fall through to the normal LLM turn so the agent can comment.
                }

                // Convert (potentially augmented) events to messages
                val messages = convertEventsToMessages(effectiveEvents)

                // Add system instructions if provided
                val allMessages =
                    if (instructions != null) {
                        listOf(SystemMessage(instructions)) + messages
                    } else {
                        messages
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
                            confirmationManager,
                            objectMapper,
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
            } catch (e: AgentInterrupt) {
                // Not an error: a tool requested a structured interruption of this agent run.
                // Drain any pending tool events before emitting orchestration events so the
                // event stream is always well-ordered: request → response → interrupt handling.
                toolEventChannel.close()
                for (toolEvent in toolEventChannel) {
                    emit(toolEvent)
                }
                emitInterruptEvents(this@AgentSimple, e, namespaceId, caseId, logger)
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
     *
     * WZ-31596 handling of opt-in tool confirmations:
     * - The user-facing prompt lives in a [QuestionEvent] (out-of-LLM-channel) — never
     *   surfaced here. The same goes for the matching [AnswerEvent].
     * - A [PendingConfirmationEvent] marks a tool_use that was deferred (no tool_result
     *   was emitted at the time). Until a [ConfirmationResolvedEvent] is present, the
     *   original [ToolRequestEvent] is OMITTED from the LLM history entirely — the LLM
     *   has no recollection of an unresolved tool call (Hermes-style out-of-channel
     *   approval, but async and event-sourced).
     * - Once a [ConfirmationResolvedEvent] exists for a pending, the original tool_use
     *   is re-introduced into the history paired with a SYNTHETIC tool_result carrying
     *   [ConfirmationResolvedEvent.resultText] — the real outcome of
     *   [StandardTool.executeWithConfirmation] or [StandardTool.onRejected].
     * - When the user replied via a free-form [MessageEvent] (no AnswerEvent widget),
     *   that reply is also filtered to avoid the "oui" looking like a fresh request
     *   to the LLM after the synthetic tool_result.
     *
     * The Neo4j event store keeps the full audit trail (Pending + Question + Answer +
     * Resolved); only the LLM-visible view is rewritten in memory.
     */
    private fun convertEventsToMessages(events: List<CaseEvent>): List<Message> {
        val messages = mutableListOf<Message>()
        val toolCallsForCurrentMessage = mutableListOf<AssistantMessage.ToolCall>()
        val toolResponses = mutableMapOf<String, ToolResponseEvent>()

        events.filterIsInstance<ToolResponseEvent>().forEach { toolResponse ->
            toolResponses[toolResponse.toolRequestId] = toolResponse
        }

        val pendingsById: Map<UUID, PendingConfirmationEvent> =
            events.filterIsInstance<PendingConfirmationEvent>().associateBy { it.metadata.id }
        val resolutionByToolRequestId: Map<String, ConfirmationResolvedEvent> =
            events
                .filterIsInstance<ConfirmationResolvedEvent>()
                .mapNotNull { resolved ->
                    val pending = pendingsById[resolved.pendingEventId] ?: return@mapNotNull null
                    pending.toolRequestId to resolved
                }.toMap()
        val unresolvedPendingToolRequestIds: Set<String> =
            pendingsById.values
                .asSequence()
                .map { it.toolRequestId }
                .filter { it !in resolutionByToolRequestId }
                .toMutableSet()

        // Free-form confirmation replies (user typed "oui" instead of clicking the widget)
        // are filtered out: without this, they would appear after the synthetic tool_result
        // and look like a fresh user request → the LLM would re-call the tool.
        val confirmationReplyMessageIds: Set<UUID> = run {
            val skipped = mutableSetOf<UUID>()
            var openPendingId: UUID? = null
            for (ev in events) {
                when (ev) {
                    is PendingConfirmationEvent -> openPendingId = ev.metadata.id
                    is ConfirmationResolvedEvent -> if (openPendingId == ev.pendingEventId) openPendingId = null
                    is MessageEvent ->
                        if (openPendingId != null && ev.actor.role == ActorRole.USER) skipped += ev.metadata.id
                    else -> Unit
                }
            }
            skipped
        }

        // Helper: flush accumulated tool calls into AssistantMessage + ToolResponseMessage.
        fun flushToolCalls() {
            if (toolCallsForCurrentMessage.isEmpty()) return
            messages.add(
                AssistantMessage
                    .builder()
                    .toolCalls(toolCallsForCurrentMessage.toList())
                    .build(),
            )
            val toolResponseMessages =
                toolCallsForCurrentMessage.mapNotNull { toolCall ->
                    val syntheticResult = resolutionByToolRequestId[toolCall.id()]?.resultText
                    val output =
                        when {
                            syntheticResult != null -> syntheticResult
                            else -> {
                                val response = toolResponses[toolCall.id()] ?: return@mapNotNull null
                                when (val content = response.output) {
                                    is MessageContent.Text -> content.content
                                    else -> content.toString()
                                }
                            }
                        }
                    ToolResponseMessage.ToolResponse(toolCall.id(), toolCall.name(), output)
                }
            if (toolResponseMessages.isNotEmpty()) {
                messages.add(ToolResponseMessage.builder().responses(toolResponseMessages).build())
            }
            toolCallsForCurrentMessage.clear()
        }

        for (event in events) {
            when (event) {
                is MessageEvent -> {
                    if (event.metadata.id in confirmationReplyMessageIds) continue
                    flushToolCalls()
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
                                messages.add(UserMessage("[${event.actor.displayName}]: $textContent"))
                            }
                        }
                    }
                }
                is ToolRequestEvent -> {
                    // Skip tool_uses whose pending is still unresolved — the LLM should not
                    // see them yet (no matching tool_result would be present, breaking the
                    // Anthropic native protocol).
                    if (event.toolRequestId in unresolvedPendingToolRequestIds) continue
                    val safeArgs = event.args?.takeIf { it.isNotBlank() } ?: "{}"
                    toolCallsForCurrentMessage.add(
                        AssistantMessage.ToolCall(event.toolRequestId, "function", event.toolName, safeArgs),
                    )
                }
                else -> {
                    // Ignore other event types (PendingConfirmation, QuestionEvent, AnswerEvent,
                    // ConfirmationResolved, Thinking, AgentRunning, etc.) — they don't translate
                    // to a Spring AI message.
                }
            }
        }

        flushToolCalls()
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
        namespaceId: UUID,
        caseId: UUID,
        eventChannel: Channel<CaseEvent>,
        llmTurnMark: AtomicReference<TimeSource.Monotonic.ValueTimeMark>,
        llmTurnIndex: AtomicInteger,
        confirmationManager: ConfirmationManager?,
        objectMapper: ObjectMapper?,
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
                logger.info { "[AgentSimple] $name LLM turn $turn answered in ${llmTurnMark.get().elapsedNow()}" }

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

                // Opt-in tools short-circuit executeWithJson via the confirmation flow.
                // Note: this runs BEFORE the regular execute, so an opt-in tool's execute()
                // is never reached — eliminating the "tool forgot to throw" failure mode.
                val confirmationOutcome =
                    try {
                        maybeRunConfirmationFlow(
                            tool = tool,
                            toolInput = toolInput,
                            context = context,
                            confirmationManager = confirmationManager,
                            objectMapper = objectMapper,
                        )
                    } catch (e: Exception) {
                        // Pre-flight validation failure (e.g. path does not exist). Surface the
                        // error to the LLM as a tool_result so it can retry with corrected input,
                        // instead of letting the exception cut the Spring AI stream and leave
                        // the case stuck in IDLE without any user-visible message.
                        val errorMessage = "Error: ${e.message}"
                        sendEvent(
                            ToolResponseEvent(
                                namespaceId = namespaceId,
                                caseId = caseId,
                                toolRequestId = toolRequestId,
                                toolName = tool.name,
                                output = MessageContent.Text(errorMessage),
                                success = false,
                            ),
                        )
                        return errorMessage
                    }
                when (confirmationOutcome) {
                    is ConfirmationOutcome.Skip -> {
                        // Fall through to the standard executeWithJson flow below.
                    }
                    is ConfirmationOutcome.ExecutedDirectly -> {
                        // shouldConfirm == false: tool executed directly via executeWithConfirmation.
                        sendEvent(
                            ToolResponseEvent(
                                namespaceId = namespaceId,
                                caseId = caseId,
                                toolRequestId = toolRequestId,
                                toolName = tool.name,
                                output = MessageContent.Text(confirmationOutcome.result),
                                success = true,
                            ),
                        )
                        llmTurnMark.set(TimeSource.Monotonic.markNow())
                        llmTurnIndex.incrementAndGet()
                        return confirmationOutcome.result
                    }
                    is ConfirmationOutcome.Await -> {
                        // UI hint: emit a placeholder ToolResponseEvent so the SSE/UI flips the
                        // tool box from "Running…" to ✅ with an informative output. The LLM does
                        // NOT see this placeholder — `convertEventsToMessages` prefers the
                        // synthetic `resultText` from the ConfirmationResolvedEvent once the
                        // pending is resolved (cf. `flushToolCalls`). Before resolution, the
                        // entire tool_use is omitted from the LLM history anyway.
                        sendEvent(
                            ToolResponseEvent(
                                namespaceId = namespaceId,
                                caseId = caseId,
                                toolRequestId = toolRequestId,
                                toolName = tool.name,
                                output = MessageContent.Text("Awaiting user confirmation: ${confirmationOutcome.label}"),
                                success = true,
                            ),
                        )
                        // Throw AgentInterrupt.AwaitConfirmation — caught by the outer flow's
                        // try/catch in run(), which calls emitInterruptEvents to fire (in order):
                        //   PendingConfirmationEvent (orchestration marker, with questionId)
                        //   QuestionEvent           (user-facing prompt, out-of-LLM-channel)
                        //   AgentFinishedEvent      (close the turn)
                        llmTurnMark.set(TimeSource.Monotonic.markNow())
                        llmTurnIndex.incrementAndGet()
                        throw AgentInterrupt.AwaitConfirmation(
                            toolName = tool.name,
                            toolRequestId = toolRequestId,
                            pendingPayloadJson = confirmationOutcome.payloadJson,
                            confirmationLabel = confirmationOutcome.label,
                            question = confirmationOutcome.question,
                            analysisInstructions = confirmationOutcome.instructions,
                            questionId = confirmationOutcome.questionId,
                        )
                    }
                }

                val result: String
                val toolDuration =
                    measureTime {
                        result =
                            try {
                                tool.executeWithJson(toolInput, context)
                            } catch (e: AgentInterrupt) {
                                // Interrupt is not an error: emit a successful response so traces
                                // are complete, then re-throw the signal for the flow catch block.
                                val message =
                                    when (e) {
                                        is AgentInterrupt.Redirect -> "Redirecting to agent '${e.targetAgentName}'."
                                        is AgentInterrupt.AwaitConfirmation -> "Awaiting user confirmation for: ${e.confirmationLabel}"
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
                logger.info { "[AgentSimple] tool ${tool.name} executed in $toolDuration" }

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
                        output = MessageContent.Text(result),
                        success = true,
                    ),
                )

                return result
            }
        }

    // ─── Confirmation helpers ──────────────────────────────────────────────────────────────

    /**
     * Find a [PendingConfirmationEvent] in [events] that hasn't been closed by a matching
     * [ConfirmationResolvedEvent]. Returns the most recent unresolved pending, or null
     * when all pendings are already resolved.
     */
    private fun findUnresolvedPendingConfirmation(events: List<CaseEvent>): PendingConfirmationEvent? {
        val resolvedIds =
            events
                .filterIsInstance<ConfirmationResolvedEvent>()
                .mapTo(mutableSetOf()) { it.pendingEventId }
        return events
            .filterIsInstance<PendingConfirmationEvent>()
            .lastOrNull { it.metadata.id !in resolvedIds }
    }

    /**
     * Drive the resolution of an unresolved pending confirmation.
     *
     * Resolution path priority (WZ-31596 QuestionEvent flow):
     * 1. If an [AnswerEvent] references the pending's [PendingConfirmationEvent.questionId],
     *    use it directly: `answer == "Confirmer"` → confirmed, else rejected. No LLM call.
     * 2. Otherwise fall back to the latest user [MessageEvent] (legacy / free-form reply path)
     *    and run [ConfirmationManager.analyzeConfirmation] with lenient matching.
     *
     * On a clean outcome, emits a [ConfirmationResolvedEvent] carrying [resultText] (the
     * output of [StandardTool.executeWithConfirmation] or [StandardTool.onRejected]). The
     * synthetic System_ResolveConfirmation tool_call pair that the previous version emitted
     * is no longer needed — `convertEventsToMessages` injects a synthetic tool_result for
     * the ORIGINAL tool_use using `resultText`.
     *
     * @return true when resolved (confirmed or rejected), false on ambiguity (WarnEvent
     *   emitted, pending remains alive for the next user message).
     */
    private suspend fun FlowCollector<CaseEvent>.handleConfirmationResolution(
        events: List<CaseEvent>,
        pending: PendingConfirmationEvent,
        namespaceId: UUID,
        caseId: UUID,
        appendTo: MutableList<CaseEvent>,
    ): Boolean {
        val mapper = objectMapper
        if (mapper == null) {
            emit(
                WarnEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    message = "Cannot resolve pending confirmation: ObjectMapper not available.",
                ),
            )
            return false
        }
        val tool = tools.firstOrNull { it.name == pending.toolName }
        if (tool == null) {
            emit(
                WarnEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    message = "Cannot resolve pending confirmation: tool '${pending.toolName}' not available in this agent.",
                ),
            )
            return false
        }

        val payload =
            try {
                mapper.readValue(pending.pendingPayloadJson, Map::class.java)
            } catch (e: Exception) {
                logger.warn(e) { "[AgentSimple] failed to deserialize pending payload for ${pending.toolName}" }
                emit(
                    WarnEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        message = "Cannot resolve pending confirmation: payload deserialization failed.",
                    ),
                )
                return false
            }

        val context =
            ToolContext(
                namespaceId = namespaceId,
                userId = userId,
                userExternalId = userExternalId,
                caseEvents = events,
            )

        // Path 1: AnswerEvent linked to the pending's questionId (UI button click).
        val answerEvent =
            pending.questionId?.let { qid ->
                events
                    .filterIsInstance<AnswerEvent>()
                    .firstOrNull { it.questionId == qid }
            }
        val confirmed: Boolean
        val replyText: String
        if (answerEvent != null) {
            confirmed = answerEvent.answer.trim().equals(CONFIRMATION_ANSWER_CONFIRM, ignoreCase = true)
            replyText = answerEvent.answer
            logger.info { "[AgentSimple] resolved via AnswerEvent for ${pending.toolName}: confirmed=$confirmed" }
        } else {
            // Path 2: fallback to free-form user MessageEvent + LLM lenient matching.
            val manager = confirmationManager
            if (manager == null) {
                emit(
                    WarnEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        message = "Cannot resolve pending confirmation: ConfirmationManager not available for free-form reply.",
                    ),
                )
                return false
            }
            val lastUserText =
                events
                    .filterIsInstance<MessageEvent>()
                    .lastOrNull { it.actor.role == ActorRole.USER }
                    ?.content
                    ?.filterIsInstance<MessageContent.Text>()
                    ?.joinToString(" ") { it.content }
            if (lastUserText.isNullOrBlank()) {
                // No AnswerEvent and no user message — nothing to interpret yet.
                // Leave the pending alive and exit; the next turn will retry.
                return false
            }
            val history = convertEventsToMessages(events)
            try {
                confirmed =
                    manager.analyzeConfirmation(
                        chatClient = chatClient,
                        history = history,
                        pendingPayload = payload,
                        specificInstructions = pending.analysisInstructions,
                    )
                replyText = lastUserText
            } catch (e: AmbiguousConfirmationException) {
                logger.info { "[AgentSimple] ambiguous confirmation reply for ${pending.toolName}: ${e.message}" }
                emit(
                    WarnEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        message = "Could not interpret your reply. Please confirm or reject explicitly.",
                    ),
                )
                return false
            }
        }

        val resultText: String =
            try {
                if (confirmed) {
                    @Suppress("UNCHECKED_CAST")
                    (tool as StandardTool<Any?>).executeWithConfirmation(payload, context)
                } else {
                    @Suppress("UNCHECKED_CAST")
                    (tool as StandardTool<Any?>).onRejected(payload, replyText, context)
                }
            } catch (e: Exception) {
                logger.warn(e) { "[AgentSimple] tool execution failed during confirmation resolution for ${pending.toolName}" }
                "Error during tool execution: ${e.message}"
            }

        val resolved =
            ConfirmationResolvedEvent(
                namespaceId = namespaceId,
                caseId = caseId,
                pendingEventId = pending.metadata.id,
                confirmed = confirmed,
                resultText = resultText,
            )
        emit(resolved)
        appendTo += resolved
        return true
    }

    /**
     * Outcome of the confirmation pre-flight for a single tool call.
     */
    private sealed class ConfirmationOutcome {
        /** Tool does not opt-in (or no ConfirmationManager available): run the standard flow. */
        object Skip : ConfirmationOutcome()

        /** Tool opts-in but shouldConfirm == false: user already authorised, executed directly. */
        data class ExecutedDirectly(val result: String) : ConfirmationOutcome()

        /**
         * Tool opts-in and shouldConfirm == true: the user must be prompted via a
         * [QuestionEvent] out-of-LLM-channel. The caller throws an [AgentInterrupt.AwaitConfirmation]
         * carrying this payload; the interrupt handler then emits the
         * [PendingConfirmationEvent] + [QuestionEvent] + [AgentFinishedEvent] in order.
         */
        data class Await(
            val payloadJson: String,
            val label: String,
            val question: String,
            val instructions: String,
            val questionId: UUID,
        ) : ConfirmationOutcome()
    }

    /**
     * Confirmation pre-flight for a single tool call.
     *
     * WZ-31596 (post-Hermes-analysis design): when explicit confirmation is required, this
     * returns a [ConfirmationOutcome.Await] carrying a pre-generated `questionId`. The
     * caller throws [AgentInterrupt.AwaitConfirmation] to exit the Spring AI tool loop
     * BEFORE a tool_result is appended to the LLM history. The user's confirmation is
     * captured via a [QuestionEvent] / [AnswerEvent] pair, kept entirely out of the LLM
     * conversation. On the next turn, `convertEventsToMessages` injects a synthetic
     * tool_result for the original tool_use carrying the actual execution result.
     */
    private fun maybeRunConfirmationFlow(
        tool: StandardTool<*>,
        toolInput: String,
        context: ToolContext,
        confirmationManager: ConfirmationManager?,
        objectMapper: ObjectMapper?,
    ): ConfirmationOutcome {
        if (!tool.supportsConfirmation) return ConfirmationOutcome.Skip
        if (confirmationManager == null || objectMapper == null) {
            // Tool wants confirmation but we have no manager → refuse rather than risk
            // running its side-effect unconfirmed.
            throw IllegalStateException(
                "Tool '${tool.name}' requires confirmation but no ConfirmationManager is configured for this agent.",
            )
        }

        @Suppress("UNCHECKED_CAST")
        val typed = tool as StandardTool<Any?>
        val parsedInput = typed.parseInput(toolInput)
        if (!typed.requiresConfirmation(parsedInput, context)) return ConfirmationOutcome.Skip

        val payload = typed.getConfirmationPayload(parsedInput, context)
        val history by lazy { convertEventsToMessages(context.caseEvents) }
        // Tools flagged bypassImplicitConsent always force an explicit prompt; skip the LLM
        // judgement step entirely (intended for destructive actions like file deletion).
        val needsExplicit =
            tool.bypassImplicitConsent ||
                confirmationManager.shouldConfirm(
                    chatClient = chatClient,
                    history = history,
                    actionLabel = "Tool ${tool.name}",
                    proposedData = payload,
                )
        if (!needsExplicit) {
            // shouldConfirm == false → user has already implicitly authorized; execute directly.
            return ConfirmationOutcome.ExecutedDirectly(typed.executeWithConfirmation(payload, context))
        }

        // Explicit confirmation required → defer via Question/Answer. The deterministic label
        // is kept for audit on PendingConfirmationEvent; the user-facing widget text is
        // formulated by the LLM out-of-channel so it matches the conversation language.
        val payloadJson = objectMapper.writeValueAsString(payload)
        val sanitizedLabel = sanitizeForLlm(typed.confirmationLabel(payload))
        val question =
            confirmationManager.formulateQuestion(
                chatClient = chatClient,
                history = history,
                fallbackLabel = sanitizedLabel,
                pendingData = payload,
            )
        return ConfirmationOutcome.Await(
            payloadJson = payloadJson,
            label = sanitizedLabel,
            question = question,
            instructions = typed.getConfirmationAnalysisInstructions(),
            questionId = UUID.randomUUID(),
        )
    }

    /**
     * Whitelist-based sanitization of user-supplied label fragments before they're
     * injected into LLM context. The blacklist `<>\n` discussed in the v1 plan does not
     * actually stop natural-language injections — only a whitelist + length cap does.
     */
    private fun sanitizeForLlm(s: String): String = s.filter { it.isLetterOrDigit() || it in " _-./" }.take(200)

    companion object : KLogging()
}
