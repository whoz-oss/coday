package io.whozoss.agentos.agent

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.agent.Agent
import io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent
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
                // Convert events to messages
                val messages = convertEventsToMessages(events)

                // Add system instructions if provided
                val allMessages =
                    if (instructions != null) {
                        listOf(SystemMessage(instructions)) + messages
                    } else {
                        messages
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
                        ),
                    )
                    return@flow
                }

                // WZ-31596: resolve any pending confirmation before starting a new LLM turn.
                // The user's latest reply (already in `events`) is fed to the
                // ConfirmationManager which decides confirm/reject/ambiguous, then the tool
                // is invoked accordingly. On success the synthetic System_ResolveConfirmation
                // pair + a ConfirmationResolvedEvent marker are emitted so the LLM history
                // and downstream "unresolved?" detection remain consistent.
                val unresolvedPending = findUnresolvedPendingConfirmation(events)
                if (unresolvedPending != null) {
                    val resolved =
                        handleConfirmationResolution(
                            events = events,
                            pending = unresolvedPending,
                            namespaceId = namespaceId,
                            caseId = caseId,
                        )
                    if (!resolved) {
                        // Ambiguous reply: pending stays alive, the WarnEvent has been
                        // emitted, no point burning an LLM turn — let the next user message
                        // re-trigger the resolution loop.
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
                    // Resolved: fall through to the normal LLM turn so the agent can
                    // comment on the outcome (now visible in its message history).
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

                // WZ-31596: confirmation flow short-circuits executeWithJson for opt-in tools.
                // Note: this is evaluated BEFORE the regular execute, so an opt-in tool that
                // signals AwaitConfirmation never reaches its execute() — eliminating the
                // "tool forgot to throw" failure mode.
                maybeRunConfirmationFlow(
                    tool = tool,
                    toolRequestId = toolRequestId,
                    toolInput = toolInput,
                    context = context,
                    confirmationManager = confirmationManager,
                    objectMapper = objectMapper,
                )?.let { directResult ->
                    // shouldConfirm == false: tool was executed directly via executeWithConfirmation.
                    sendEvent(
                        ToolResponseEvent(
                            namespaceId = namespaceId,
                            caseId = caseId,
                            toolRequestId = toolRequestId,
                            toolName = tool.name,
                            output = MessageContent.Text(directResult),
                            success = true,
                        ),
                    )
                    llmTurnMark.set(TimeSource.Monotonic.markNow())
                    llmTurnIndex.incrementAndGet()
                    return directResult
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

    // ─── WZ-31596 confirmation helpers ─────────────────────────────────────────────────────

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
     * Drive the resolution of an unresolved pending confirmation against the latest user
     * message. Emits the synthetic System_ResolveConfirmation pair (request+response) and
     * the [ConfirmationResolvedEvent] marker on a clean outcome.
     *
     * @return true when the pending was resolved (confirmed or rejected), false when the
     *   LLM reply was ambiguous — in which case a [WarnEvent] has been emitted and the
     *   pending remains alive for the next user message.
     */
    private suspend fun FlowCollector<CaseEvent>.handleConfirmationResolution(
        events: List<CaseEvent>,
        pending: PendingConfirmationEvent,
        namespaceId: UUID,
        caseId: UUID,
    ): Boolean {
        val manager = confirmationManager
        val mapper = objectMapper
        if (manager == null || mapper == null) {
            emit(
                WarnEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    message = "Cannot resolve pending confirmation: confirmation manager not available.",
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
        val history = convertEventsToMessages(events)
        val lastUserText =
            events
                .filterIsInstance<MessageEvent>()
                .lastOrNull { it.actor.role == ActorRole.USER }
                ?.content
                ?.filterIsInstance<MessageContent.Text>()
                ?.joinToString(" ") { it.content }
                ?: ""

        val context =
            ToolContext(
                namespaceId = namespaceId,
                userId = userId,
                userExternalId = userExternalId,
                caseEvents = caseEventsProvider(),
            )

        val confirmed: Boolean
        val resultText: String
        try {
            confirmed =
                manager.analyzeConfirmation(
                    chatClient = chatClient,
                    history = history,
                    pendingPayload = payload,
                    specificInstructions = pending.analysisInstructions,
                )
            resultText =
                if (confirmed) {
                    @Suppress("UNCHECKED_CAST")
                    (tool as StandardTool<Any?>).executeWithConfirmation(payload, context)
                } else {
                    @Suppress("UNCHECKED_CAST")
                    (tool as StandardTool<Any?>).onRejected(payload, lastUserText, context)
                }
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
        } catch (e: Exception) {
            logger.warn(e) { "[AgentSimple] confirmation resolution failed for ${pending.toolName}" }
            emit(
                WarnEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    message = "Confirmation resolution failed: ${e.message}",
                ),
            )
            return false
        }

        // Synthetic System_ResolveConfirmation tool-call so the LLM history shows a clean
        // request/response pair carrying the actual result, with a fresh toolRequestId
        // (the original pending toolRequestId already paired with the "Awaiting…" response).
        val resolutionToolRequestId = UUID.randomUUID().toString()
        emit(
            ToolRequestEvent(
                namespaceId = namespaceId,
                caseId = caseId,
                toolRequestId = resolutionToolRequestId,
                toolName = SYSTEM_RESOLVE_CONFIRMATION,
                args = """{"pendingEventId":"${pending.metadata.id}","confirmed":$confirmed}""",
            ),
        )
        emit(
            ToolResponseEvent(
                namespaceId = namespaceId,
                caseId = caseId,
                toolRequestId = resolutionToolRequestId,
                toolName = SYSTEM_RESOLVE_CONFIRMATION,
                output = MessageContent.Text(resultText),
                success = confirmed,
            ),
        )
        emit(
            ConfirmationResolvedEvent(
                namespaceId = namespaceId,
                caseId = caseId,
                pendingEventId = pending.metadata.id,
                confirmed = confirmed,
            ),
        )
        return true
    }

    /**
     * Confirmation pre-flight for a single tool call. Returns a non-null String when the
     * tool was executed directly (shouldConfirm == false) — that string is the result to
     * hand back to the LLM. Returns null when the standard executeWithJson flow should
     * proceed (tool doesn't opt-in, or no confirmationManager).
     *
     * Throws [AgentInterrupt.AwaitConfirmation] when the user must be prompted.
     */
    private fun maybeRunConfirmationFlow(
        tool: StandardTool<*>,
        toolRequestId: String,
        toolInput: String,
        context: ToolContext,
        confirmationManager: ConfirmationManager?,
        objectMapper: ObjectMapper?,
    ): String? {
        if (!tool.supportsConfirmation) return null
        if (confirmationManager == null || objectMapper == null) {
            // Tool wants confirmation but we have no manager → refuse rather than risk
            // running its side-effect unconfirmed. The error path of the caller will
            // surface this as a regular tool failure (no PendingConfirmationEvent emitted).
            throw IllegalStateException(
                "Tool '${tool.name}' requires confirmation but no ConfirmationManager is configured for this agent.",
            )
        }

        @Suppress("UNCHECKED_CAST")
        val typed = tool as StandardTool<Any?>
        val parsedInput = typed.parseInput(toolInput)
        if (!typed.requiresConfirmation(parsedInput, context)) return null

        val payload = typed.getConfirmationPayload(parsedInput, context)
        val needsExplicit =
            confirmationManager.shouldConfirm(
                chatClient = chatClient,
                history = convertEventsToMessages(context.caseEvents),
                actionLabel = "Tool ${tool.name}",
                proposedData = payload,
            )
        if (!needsExplicit) {
            // shouldConfirm == false → user has already implicitly authorized; execute directly.
            return typed.executeWithConfirmation(payload, context)
        }

        // Explicit confirmation required.
        val payloadJson = objectMapper.writeValueAsString(payload)
        val rawLabel = typed.confirmationLabel(payload)
        val sanitizedLabel = sanitizeForLlm(rawLabel)
        throw AgentInterrupt.AwaitConfirmation(
            toolName = tool.name,
            toolRequestId = toolRequestId,
            pendingPayloadJson = payloadJson,
            confirmationLabel = sanitizedLabel,
            analysisInstructions = typed.getConfirmationAnalysisInstructions(),
        )
    }

    /**
     * Whitelist-based sanitization of user-supplied label fragments before they're
     * injected into LLM context. The blacklist `<>\n` discussed in the v1 plan does not
     * actually stop natural-language injections — only a whitelist + length cap does.
     */
    private fun sanitizeForLlm(s: String): String = s.filter { it.isLetterOrDigit() || it in " _-./" }.take(200)

    companion object : KLogging() {
        /** Synthetic tool name used to materialise a resolution step in the LLM history. */
        const val SYSTEM_RESOLVE_CONFIRMATION = "System_ResolveConfirmation"
    }
}
