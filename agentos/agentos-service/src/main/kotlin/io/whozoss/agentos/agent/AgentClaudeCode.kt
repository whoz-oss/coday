package io.whozoss.agentos.agent

import io.whozoss.agentos.chat.CostCalculator
import io.whozoss.agentos.chat.UsageAccumulator
import io.whozoss.agentos.claudeCode.ClaudeCodeLaunchRequest
import io.whozoss.agentos.claudeCode.ClaudeCodeProcess
import io.whozoss.agentos.claudeCode.ClaudeCodeProcessLauncher
import io.whozoss.agentos.claudeCode.ClaudeCodeProperties
import io.whozoss.agentos.claudeCode.ClaudeCodeSession
import io.whozoss.agentos.claudeCode.ClaudeCodeStreamEvent
import io.whozoss.agentos.claudeCode.ClaudeCodeVersion
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
import io.whozoss.agentos.sdk.entity.EntityMetadata
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mu.KLogging
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

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
 * Only a run that completed normally hands its session on ([AgentFinishedEvent.runtimeSessionId]): after a
 * failure, a stop or a timeout, the next run starts over from the case events, which AgentOS holds.
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
            try {
                usageAccumulator?.beforeCall?.invoke()?.await()
                val conversation = ClaudeCodeConversation(events, id)
                val resumed =
                    conversation.resumableSessionId?.let { sessionId ->
                        runTurn(namespaceId, caseId, ClaudeCodeSession.Resume(sessionId), conversation.resumeMessage(), shouldContinue)
                    }
                val turn =
                    if (resumed == null || resumed.sessionLost) {
                        resumed?.let { logger.info { "[AgentClaudeCode] agent=$name case=$caseId session ${it.session.id} is gone, starting a new one" } }
                        runTurn(namespaceId, caseId, ClaudeCodeSession.Start(UUID.randomUUID()), conversation.newSessionMessage(), shouldContinue)
                    } else {
                        resumed
                    }
                emitOutcome(namespaceId, caseId, turn)
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
            }
        }

    /** Runs one CLI process for [session], streaming its answer as [TextChunkEvent]s. */
    private suspend fun FlowCollector<CaseEvent>.runTurn(
        namespaceId: UUID,
        caseId: UUID,
        session: ClaudeCodeSession,
        message: String,
        shouldContinue: () -> Boolean,
    ): Turn {
        val turn = Turn(session)
        val request =
            ClaudeCodeLaunchRequest(
                userId = userId.toString(),
                caseId = caseId.toString(),
                model = llmModel,
                systemPrompt = listOfNotNull(systemPrompt, instructions).joinToString("\n").ifBlank { "You are $name." },
                session = session,
                effort = reasoningEffort,
                maxTurns = maxTurns,
            )
        launcher.launch(request).use { process ->
            process.sendUserMessage(message)
            // One message per process: the CLI ends once this turn is answered.
            process.closeInput()
            coroutineScope {
                val lastOutput = AtomicLong(System.nanoTime())
                val watchdog =
                    launch {
                        while (isActive) {
                            delay(WATCH_INTERVAL_MILLIS)
                            when {
                                !shouldContinue() -> turn.stopped = true
                                System.nanoTime() - lastOutput.get() > properties.idleTimeout.toNanos() -> turn.timedOut = true
                                else -> continue
                            }
                            process.stop()
                            break
                        }
                    }
                try {
                    process.events().collect { event ->
                        lastOutput.set(System.nanoTime())
                        handle(event, turn, process, namespaceId, caseId)
                    }
                } finally {
                    watchdog.cancel()
                }
            }
            process.awaitExit(EXIT_WAIT_MILLIS)
            turn.exitCode = process.exitCode
            turn.stderr = process.stderrTail()
        }
        return turn
    }

    private suspend fun FlowCollector<CaseEvent>.handle(
        event: ClaudeCodeStreamEvent,
        turn: Turn,
        process: ClaudeCodeProcess,
        namespaceId: UUID,
        caseId: UUID,
    ) {
        when (event) {
            is ClaudeCodeStreamEvent.Init -> {
                turn.started = true
                val version = event.cliVersion
                if (version != null && !ClaudeCodeVersion.isAtLeast(version, properties.minimumVersion)) {
                    turn.unsupportedVersion = version
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

            is ClaudeCodeStreamEvent.ThinkingDelta, is ClaudeCodeStreamEvent.ToolResults, is ClaudeCodeStreamEvent.Other -> Unit
        }
    }

    private suspend fun FlowCollector<CaseEvent>.emitOutcome(
        namespaceId: UUID,
        caseId: UUID,
        turn: Turn,
    ) {
        val result = turn.result
        result?.usage?.let { usage ->
            usageAccumulator?.record(
                CostCalculator.fromCounts(usage.inputTokens, usage.outputTokens, usage.cacheReadTokens, usage.cacheWriteTokens, modelConfig),
            )
        }
        val failure = failureMessage(turn)
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

    /** What one CLI process produced. */
    private class Turn(
        val session: ClaudeCodeSession,
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
        private const val WATCH_INTERVAL_MILLIS = 200L
        private const val EXIT_WAIT_MILLIS = 2_000L
        private const val LOGGED_STDERR_CHARS = 1_000
    }
}
