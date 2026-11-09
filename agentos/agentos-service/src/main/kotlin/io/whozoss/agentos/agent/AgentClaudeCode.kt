package io.whozoss.agentos.agent

import io.whozoss.agentos.chat.CostCalculator
import io.whozoss.agentos.chat.UsageAccumulator
import io.whozoss.agentos.claudeCode.ClaudeCodeCommand
import io.whozoss.agentos.claudeCode.ClaudeCodeLaunchRequest
import io.whozoss.agentos.claudeCode.ClaudeCodeProcess
import io.whozoss.agentos.claudeCode.ClaudeCodeProcessLauncher
import io.whozoss.agentos.claudeCode.ClaudeCodeProperties
import io.whozoss.agentos.claudeCode.ClaudeCodeSession
import io.whozoss.agentos.claudeCode.ClaudeCodeStreamEvent
import io.whozoss.agentos.claudeCode.ClaudeCodeToolBridge
import io.whozoss.agentos.claudeCode.ClaudeCodeToolHandler
import io.whozoss.agentos.claudeCode.ClaudeCodeToolResult
import io.whozoss.agentos.claudeCode.ClaudeCodeToolRun
import io.whozoss.agentos.claudeCode.ClaudeCodeVersion
import io.whozoss.agentos.logging.withMdc
import io.whozoss.agentos.metrics.ToolMetricsService
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.agent.Agent
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.ReasoningEffort
import io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.ErrorEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.TextChunkEvent
import io.whozoss.agentos.sdk.caseEvent.ThinkingEvent
import io.whozoss.agentos.sdk.caseEvent.ToolResponseEvent
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.usage.CostRunStopped
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.future.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import mu.KLogging
import org.slf4j.MDC
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Agent running on the official `claude` CLI, signed in by the user with their own Claude subscription
 * (see [ClaudeCodeProcessLauncher]). Picked instead of [AgentSimple] for a model whose provider is
 * [io.whozoss.agentos.sdk.aiProvider.AiApiType.ClaudeCode].
 *
 * Each run starts one CLI process. The CLI keeps the agent's conversation in its own session: a run
 * resumes the session of the agent's previous run and sends only what happened since, or starts a new
 * session replaying the case ([ClaudeCodeConversation]). The answer streams as [TextChunkEvent]s and ends
 * in one [MessageEvent], like [AgentSimple].
 *
 * The CLI runs the model's tool loop, with none of its own tools: it calls the agent's AgentOS tools through
 * [ClaudeCodeToolBridge], and each call runs through a [ToolInvoker] as in [AgentSimple]. A tool that
 * interrupts the run (redirect, question to the user) gets its result back to the CLI, which is then stopped.
 *
 * Only a run that completed normally, or was interrupted by a tool once the CLI recorded the result, hands its
 * session on ([AgentFinishedEvent.runtimeSessionId]): after a failure, a stop or a timeout, the next run starts
 * over from the case events, which AgentOS holds.
 */
class AgentClaudeCode(
    override val metadata: EntityMetadata = EntityMetadata(),
    override val name: String,
    private val launcher: ClaudeCodeProcessLauncher,
    private val properties: ClaudeCodeProperties,
    /** The user the run is for: the CLI runs with their own login. */
    private val userId: UUID,
    /** The namespace context block, first part of the system prompt. */
    val systemPrompt: String? = null,
    /** The agent's effective instructions, second part of the system prompt. */
    val instructions: String? = null,
    private val modelConfig: AiModel,
    override val llmProvider: String,
    override val reasoningEffort: ReasoningEffort? = null,
    override val requestedReasoningEffort: ReasoningEffort? = null,
    /** Records the usage of each run and holds the run-cost gate; null when usage tracking is off. */
    private val usageAccumulator: UsageAccumulator? = null,
    /** Bound on the model's tool rounds in a run (`--max-turns`); the CLI default when null. */
    private val maxTurns: Int? = null,
    /** The agent's AgentOS tools, served to the CLI through [toolBridge]. */
    private val tools: Collection<StandardTool<*>> = emptyList(),
    private val toolBridge: ClaudeCodeToolBridge? = null,
    /** Identity-provider key of the user (e.g. email). Used by plugins that manage their own auth. */
    private val userExternalId: String? = null,
    /** Returns the live event list of the current case at the moment of invocation. */
    private val caseEventsProvider: () -> List<CaseEvent> = { emptyList() },
    private val toolMetricsService: ToolMetricsService? = null,
) : Agent {
    override val llmModel: String = modelConfig.apiModelName

    override fun run(
        events: List<CaseEvent>,
        shouldContinue: () -> Boolean,
    ): Flow<CaseEvent> =
        flow {
            val namespaceId = events.firstOrNull()?.namespaceId ?: throw IllegalArgumentException("No events provided")
            val caseId = events.firstOrNull()?.caseId ?: throw IllegalArgumentException("No events provided")
            if (!shouldContinue()) {
                emit(finished(namespaceId, caseId))
                return@flow
            }
            emit(ThinkingEvent(namespaceId = namespaceId, caseId = caseId))
            // Tool events are sent from the MCP request threads, and emitted here in order.
            val toolEvents = Channel<CaseEvent>(Channel.UNLIMITED)
            val toolCalls = ToolCalls(namespaceId, caseId, toolEvents)
            try {
                usageAccumulator?.beforeCall?.invoke()?.await()
                toolBridge?.takeIf { tools.isNotEmpty() }?.open(tools, toolCalls).use { toolRun ->
                    val conversation = ClaudeCodeConversation(events, id)
                    val resumed =
                        conversation.resumableSessionId?.let { sessionId ->
                            val session = ClaudeCodeSession.Resume(sessionId)
                            runTurn(namespaceId, caseId, session, conversation.resumeMessage(), shouldContinue, toolRun, toolCalls, toolEvents)
                        }
                    val turn =
                        if (resumed == null || resumed.sessionLost) {
                            resumed?.let { logger.info { "[AgentClaudeCode] agent=$name case=$caseId session ${it.session.id} is gone, starting a new one" } }
                            val session = ClaudeCodeSession.Start(UUID.randomUUID())
                            runTurn(namespaceId, caseId, session, conversation.newSessionMessage(), shouldContinue, toolRun, toolCalls, toolEvents)
                        } else {
                            resumed
                        }
                    emitOutcome(namespaceId, caseId, turn, toolCalls.interrupt)
                }
            } catch (e: IOException) {
                logger.warn(e) { "[AgentClaudeCode] agent=$name case=$caseId could not start '${properties.executable}'" }
                emit(
                    ErrorEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        message = "The Claude Code CLI ('${properties.executable}') cannot be started on the AgentOS host. Install it there, then send your message again.",
                    ),
                )
                emit(finished(namespaceId, caseId))
            } catch (e: Exception) {
                handleGenericAgentException(this@AgentClaudeCode, e, namespaceId, caseId, logger)
            } finally {
                // A call still running in an MCP request thread finishes without being recorded.
                toolEvents.close()
            }
        }

    /**
     * Runs one CLI process for [session], streaming its answer as [TextChunkEvent]s and the events of its
     * tool calls ([toolEvents]) as they come.
     */
    private suspend fun FlowCollector<CaseEvent>.runTurn(
        namespaceId: UUID,
        caseId: UUID,
        session: ClaudeCodeSession,
        message: String,
        shouldContinue: () -> Boolean,
        toolRun: ClaudeCodeToolRun?,
        toolCalls: ToolCalls,
        toolEvents: ReceiveChannel<CaseEvent>,
    ): Turn {
        val turn = Turn(session, expectsTools = toolRun != null)
        val request =
            ClaudeCodeLaunchRequest(
                userId = userId.toString(),
                caseId = caseId.toString(),
                model = llmModel,
                systemPrompt = listOfNotNull(systemPrompt, instructions).joinToString("\n").ifBlank { "You are $name." },
                session = session,
                effort = reasoningEffort,
                maxTurns = maxTurns,
                mcpServer = toolRun?.mcpServer,
            )
        launcher.launch(request).use { process ->
            process.sendUserMessage(message)
            // One message per process: the CLI ends once this turn is answered.
            process.closeInput()
            coroutineScope {
                val lastOutput = AtomicLong(System.nanoTime())
                val watchdog =
                    launch {
                        var interruptedAt: Long? = null
                        while (isActive) {
                            delay(WATCH_INTERVAL_MILLIS)
                            val now = System.nanoTime()
                            // The CLI waits silently for an AgentOS tool, which can run for long (a delegation).
                            if (toolRun?.busy == true) lastOutput.set(now)
                            if (toolCalls.interrupt != null) interruptedAt = interruptedAt ?: now
                            when {
                                !shouldContinue() || toolCalls.costStopped -> turn.stopped = true
                                // Normally stopped as soon as the CLI shows the call's result (see handle).
                                interruptedAt != null && now - interruptedAt > INTERRUPT_GRACE_NANOS -> turn.interrupted = true
                                now - lastOutput.get() > properties.idleTimeout.toNanos() -> turn.timedOut = true
                                else -> continue
                            }
                            process.stop()
                            break
                        }
                    }
                val outputs = process.events().produceIn(this)
                try {
                    var ended = false
                    while (!ended) {
                        // Tool events first: a call's response is sent before the CLI reads it and goes on.
                        select<Unit> {
                            toolEvents.onReceive { event -> emitToolEvent(event, namespaceId, caseId, modelGoesOn = toolCalls.interrupt == null) }
                            outputs.onReceiveCatching { received ->
                                received.exceptionOrNull()?.let { throw it }
                                val event = received.getOrNull()
                                if (event == null) {
                                    ended = true
                                } else {
                                    lastOutput.set(System.nanoTime())
                                    handle(event, turn, process, namespaceId, caseId, toolCalls)
                                }
                            }
                        }
                    }
                } finally {
                    watchdog.cancel()
                }
            }
            // The calls answered before the CLI ended.
            while (true) emitToolEvent(toolEvents.tryReceive().getOrNull() ?: break, namespaceId, caseId, modelGoesOn = false)
            process.awaitExit(EXIT_WAIT_MILLIS)
            turn.exitCode = process.exitCode
            turn.stderr = process.stderrTail()
        }
        return turn
    }

    private suspend fun FlowCollector<CaseEvent>.emitToolEvent(
        event: CaseEvent,
        namespaceId: UUID,
        caseId: UUID,
        modelGoesOn: Boolean,
    ) {
        emit(event)
        // The result goes back to the model: tell live consumers that its next step is underway.
        if (event is ToolResponseEvent && modelGoesOn) emit(ThinkingEvent(namespaceId = namespaceId, caseId = caseId))
    }

    private suspend fun FlowCollector<CaseEvent>.handle(
        event: ClaudeCodeStreamEvent,
        turn: Turn,
        process: ClaudeCodeProcess,
        namespaceId: UUID,
        caseId: UUID,
        toolCalls: ToolCalls,
    ) {
        when (event) {
            is ClaudeCodeStreamEvent.Init -> {
                turn.started = true
                val version = event.cliVersion
                val toolsStatus = event.mcpServerStatus(ClaudeCodeCommand.MCP_SERVER_NAME)
                if (version != null && !ClaudeCodeVersion.isAtLeast(version, properties.minimumVersion)) {
                    turn.unsupportedVersion = version
                    process.stop()
                } else if (turn.expectsTools && toolsStatus != MCP_CONNECTED) {
                    // Never let the model work without the agent's tools: it would answer as if it had none.
                    turn.toolsUnavailable = toolsStatus ?: "absent"
                    process.stop()
                }
            }

            is ClaudeCodeStreamEvent.ToolResults -> {
                // The CLI has recorded the result of the interrupting call: its session can be resumed from here.
                if (toolCalls.interrupt != null && !turn.interrupted) {
                    turn.interrupted = true
                    turn.interruptRecorded = true
                    process.stop()
                }
            }

            is ClaudeCodeStreamEvent.TextDelta -> {
                if (event.text.isNotEmpty()) {
                    turn.streamedText.append(event.text)
                    emit(TextChunkEvent(namespaceId = namespaceId, caseId = caseId, chunk = event.text))
                }
            }

            is ClaudeCodeStreamEvent.AssistantMessage -> {
                if (event.error != null) {
                    turn.assistantError = event.error
                } else {
                    turn.messageTexts.add(event.text)
                }
            }

            is ClaudeCodeStreamEvent.RateLimit -> turn.rateLimit = event

            is ClaudeCodeStreamEvent.Result -> turn.result = event

            is ClaudeCodeStreamEvent.Malformed -> logger.debug { "[AgentClaudeCode] case=$caseId skipped an unreadable output line (${event.length} chars)" }

            is ClaudeCodeStreamEvent.ThinkingDelta, is ClaudeCodeStreamEvent.Other -> Unit
        }
    }

    private suspend fun FlowCollector<CaseEvent>.emitOutcome(
        namespaceId: UUID,
        caseId: UUID,
        turn: Turn,
        interrupt: AgentInterrupt?,
    ) {
        val result = turn.result
        result?.usage?.let { usage ->
            usageAccumulator?.record(
                CostCalculator.fromCounts(usage.inputTokens, usage.outputTokens, usage.cacheReadTokens, usage.cacheWriteTokens, modelConfig),
            )
        }
        val failure = if (interrupt != null) null else failureMessage(turn)
        if (failure != null) {
            logger.warn {
                "[AgentClaudeCode] agent=$name case=$caseId turn failed: subtype=${result?.subtype} terminal=${result?.terminalReason} " +
                    "assistantError=${turn.assistantError} exit=${turn.exitCode} stderr=${turn.stderr.takeLast(LOGGED_STDERR_CHARS)}"
            }
        }

        // The synthetic message of a failed call ("Not logged in · Please run /login") is not an answer.
        val content =
            if (turn.assistantError != null) {
                ""
            } else {
                turn.streamedText.ifEmpty { turn.messageTexts.joinToString("\n\n") }.toString().stripConversationTags()
            }
        if (content.isNotBlank()) {
            emit(
                MessageEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    actor = Actor(id.toString(), name, ActorRole.AGENT),
                    content = listOf(MessageContent.Text(content)),
                    llmProvider = llmProvider,
                    llmModel = llmModel,
                    reasoningEffort = reasoningEffort,
                    requestedReasoningEffort = requestedReasoningEffort,
                ),
            )
        }

        if (interrupt != null) {
            val resumable = turn.started && turn.interruptRecorded
            emitInterruptAndFinishEvents(
                this@AgentClaudeCode,
                interrupt,
                namespaceId,
                caseId,
                logger,
                runtimeSessionId = turn.session.id.toString().takeIf { resumable },
            )
            return
        }

        val maxTurnsReached = result?.takeIf { it.subtype == "error_max_turns" || it.terminalReason == "max_turns" }?.numTurns
        maxTurnsReached?.let { emitMaxIterationsWarning(namespaceId, caseId, it) }
        failure?.let { emit(ErrorEvent(namespaceId = namespaceId, caseId = caseId, message = it)) }

        val completed = failure == null && !turn.stopped && turn.started && result != null
        emit(
            finished(namespaceId, caseId).copy(
                maxIterationsReached = maxTurnsReached,
                runtimeSessionId = turn.session.id.toString().takeIf { completed },
            ),
        )
    }

    /** What the user is told when the turn did not complete, or null when it did or was stopped on request. */
    private fun failureMessage(turn: Turn): String? {
        val result = turn.result
        val rateLimit = turn.rateLimit
        return when {
            turn.unsupportedVersion != null -> {
                "The Claude Code CLI on the AgentOS host is version ${turn.unsupportedVersion}, older than " +
                    "${properties.minimumVersion}. Update it (claude update), then send your message again."
            }

            turn.assistantError == "authentication_failed" -> {
                "Claude Code is not signed in for you on the AgentOS host. Run this command there, then send your " +
                    "message again: ${launcher.loginCommand(userId.toString())}"
            }

            turn.assistantError == "rate_limit" || rateLimit?.status == "rejected" -> {
                "Your Claude subscription limit is reached" +
                    (rateLimit?.resetsAt?.let { ", it resets at $it (UTC)." } ?: ".")
            }

            turn.stopped -> null

            turn.toolsUnavailable != null -> {
                "Claude Code could not reach the agent's AgentOS tools (MCP server ${turn.toolsUnavailable}), so the turn was " +
                    "stopped. Send your message again; if it persists, check agentos.claude-code.bridge-url."
            }

            turn.timedOut -> "Claude Code printed nothing for ${properties.idleTimeout.toMinutes()} minutes: the turn was stopped."

            result == null -> "Claude Code stopped without an answer (exit code ${turn.exitCode})."

            result.isError && result.subtype != "error_max_turns" && result.terminalReason != "max_turns" -> {
                "Claude Code could not answer: " + (result.text ?: result.errors.joinToString("; ").ifBlank { result.subtype })
            }

            turn.assistantError != null -> "Claude Code could not answer (${turn.assistantError})."

            else -> null
        }
    }

    private fun finished(
        namespaceId: UUID,
        caseId: UUID,
    ) = AgentFinishedEvent(
        namespaceId = namespaceId,
        caseId = caseId,
        agentId = id,
        agentName = name,
        llmProvider = llmProvider,
        llmModel = llmModel,
    )

    /**
     * The tool calls the CLI makes during a run, on MCP request threads. A call that interrupts the run is
     * recorded, and the calls after it are refused, as are calls once the run's cost gate stopped it.
     */
    private inner class ToolCalls(
        namespaceId: UUID,
        caseId: UUID,
        private val events: Channel<CaseEvent>,
    ) : ClaudeCodeToolHandler {
        // MCP request threads lack the run's log context: capture it once and re-apply it in each call.
        private val logContext: Map<String, String>? = MDC.getCopyOfContextMap()
        private val invoker =
            ToolInvoker(
                agentName = name,
                namespaceId = namespaceId,
                caseId = caseId,
                userId = userId,
                userExternalId = userExternalId,
                caseEventsProvider = caseEventsProvider,
                toolMetricsService = toolMetricsService,
                sendEvent = { event -> events.trySend(event) },
            )
        private val firstInterrupt = AtomicReference<AgentInterrupt?>()

        /** The call that interrupted the run, if one did. */
        val interrupt: AgentInterrupt?
            get() = firstInterrupt.get()

        @Volatile var costStopped = false
            private set

        override fun call(
            tool: StandardTool<*>,
            arguments: String,
            toolUseId: String?,
        ): ClaudeCodeToolResult =
            withMdc(logContext) {
                interrupt?.let { return@withMdc ClaudeCodeToolResult("Not run: ${it.toolOutput()}", isError = true) }
                if (costStopped || !passesCostGate()) {
                    return@withMdc ClaudeCodeToolResult("Not run: the run was stopped.", isError = true)
                }
                try {
                    // The CLI's tool_use id, so the call reads the same in the case events and in the CLI session.
                    val result = invoker.invoke(tool, arguments, toolUseId ?: UUID.randomUUID().toString())
                    ClaudeCodeToolResult(result.output, result.images, isError = !result.success)
                } catch (e: AgentInterrupt) {
                    firstInterrupt.compareAndSet(null, e)
                    ClaudeCodeToolResult(e.toolOutput())
                }
            }

        /** The run-cost gate, as before each model call of the other runtimes; it may wait for the user's go. */
        private fun passesCostGate(): Boolean =
            try {
                usageAccumulator?.beforeCall?.invoke()?.join()
                true
            } catch (e: Exception) {
                if (generateSequence<Throwable>(e) { it.cause }.none { it is CostRunStopped }) throw e
                costStopped = true
                false
            }
    }

    /** What one CLI process produced. */
    private class Turn(
        val session: ClaudeCodeSession,
        /** The CLI was launched with the agent's tools. */
        val expectsTools: Boolean,
    ) {
        val streamedText = StringBuilder()
        val messageTexts = mutableListOf<String>()
        var started = false
        var assistantError: String? = null
        var rateLimit: ClaudeCodeStreamEvent.RateLimit? = null
        var result: ClaudeCodeStreamEvent.Result? = null
        var unsupportedVersion: String? = null

        @Volatile var stopped = false

        @Volatile var timedOut = false

        /** Stopped after a tool interrupted the run. */
        @Volatile var interrupted = false

        /** The CLI showed the result of the interrupting call before it was stopped. */
        var interruptRecorded = false

        /** The state of the AgentOS MCP server at start, when it was not connected. */
        var toolsUnavailable: String? = null
        var exitCode: Int? = null
        var stderr: String = ""

        /** The resumed session no longer exists: the CLI refused it before calling the model. */
        val sessionLost: Boolean
            get() =
                session is ClaudeCodeSession.Resume &&
                    (
                        result?.errors?.any { it.startsWith(NO_CONVERSATION) } == true ||
                            (result == null && NO_CONVERSATION in stderr)
                    )
    }

    companion object : KLogging() {
        private const val NO_CONVERSATION = "No conversation found"
        private const val MCP_CONNECTED = "connected"

        /** How long after an interrupting call the CLI may take to show its result before it is stopped anyway. */
        private val INTERRUPT_GRACE_NANOS = java.time.Duration.ofSeconds(10).toNanos()
        private const val WATCH_INTERVAL_MILLIS = 200L
        private const val EXIT_WAIT_MILLIS = 2_000L
        private const val LOGGED_STDERR_CHARS = 1_000
    }
}
