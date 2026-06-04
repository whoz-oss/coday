package io.whozoss.agentos.agent

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.agent.Agent
import io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.ConfirmationResolvedEvent
import io.whozoss.agentos.sdk.caseEvent.IntentionGeneratedEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.PendingConfirmationEvent
import io.whozoss.agentos.sdk.caseEvent.TextChunkEvent
import io.whozoss.agentos.sdk.caseEvent.ThinkingEvent
import io.whozoss.agentos.sdk.caseEvent.ToolRequestEvent
import io.whozoss.agentos.sdk.caseEvent.ToolResponseEvent
import io.whozoss.agentos.sdk.caseEvent.WarnEvent
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.ConfirmationMode
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import io.whozoss.agentos.util.AttemptFailure
import io.whozoss.agentos.util.AttemptResult
import io.whozoss.agentos.util.AttemptSuccess
import io.whozoss.agentos.util.retryWithFallback
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.reactive.asFlow
import mu.KLogging
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.retry.NonTransientAiException
import java.util.UUID

class AgentAdvanced(
    override val metadata: EntityMetadata = EntityMetadata(),
    override val name: String,
    private val context: AgentAdvancedContext,
    private val intentionGenerator: AgentIntentionGenerator,
    private val objectMapper: ObjectMapper,
    private val userId: UUID? = null,
    private val userExternalId: String? = null,
    private val caseEventsProvider: () -> List<CaseEvent> = { emptyList() },
    private val maxIterations: Int = 20,
) : Agent {
    override fun run(
        events: List<CaseEvent>,
        shouldContinue: () -> Boolean,
    ): Flow<CaseEvent> =
        flow {
            val namespaceId = events.firstOrNull()?.namespaceId ?: throw IllegalArgumentException("No events provided")
            val caseId = events.firstOrNull()?.caseId ?: throw IllegalArgumentException("No events provided")

            val accumulatedEvents = events.toMutableList()

            // WZ-31596 IN-CHANNEL pre-flight: resolve unresolved PendingConfirmationEvent
            // before entering the intention loop. Mirrors Copilote.handlePendingConfirmation.
            val unresolvedPending = findUnresolvedPendingConfirmation(accumulatedEvents)
            if (unresolvedPending != null) {
                val resolution =
                    handleConfirmationResolution(
                        events = accumulatedEvents,
                        pending = unresolvedPending,
                        namespaceId = namespaceId,
                        caseId = caseId,
                        shouldContinue = shouldContinue,
                        emitEvent = { event -> emit(event) },
                        appendTo = accumulatedEvents,
                    )
                when (resolution) {
                    // Unresolved: no reply yet / AMBIGUOUS re-ask in flight / run cancelled.
                    // Aborted:    tool threw post-confirm or orphan-closed pending.
                    // Both cases: no actionable continuation — close the turn cleanly.
                    ConfirmationResolution.Unresolved,
                    ConfirmationResolution.Aborted,
                    -> {
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

                    // Applied / Rejected: fall through to the intention loop so the LLM can
                    // comment naturally on the outcome ("OK, deleted") or produce a follow-up
                    // ("alors quel fichier veux-tu supprimer ?").
                    ConfirmationResolution.Applied,
                    ConfirmationResolution.Rejected,
                    -> {
                        Unit
                    }
                }
            }

            var iteration = 0
            var continueLoop = true
            var hasUnresolvedConfirmation = false
            var lastIntention: IntentionGeneratedEvent? = null
            var repetitionWarningEmitted = false

            try {
                while (continueLoop && iteration < maxIterations && shouldContinue()) {
                    iteration++

                    emit(ThinkingEvent(namespaceId = namespaceId, caseId = caseId))

                    val repetitionWarning =
                        handleRepetitionDetection(
                            events = accumulatedEvents,
                            namespaceId = namespaceId,
                            caseId = caseId,
                            warningAlreadyEmitted = repetitionWarningEmitted,
                            emitEvent = { event -> emit(event) },
                        )
                    repetitionWarningEmitted = repetitionWarning != null

                    val intention =
                        intentionGenerator.generate(context, accumulatedEvents, namespaceId, caseId, repetitionWarning)
                    emit(intention)
                    accumulatedEvents.add(intention)
                    lastIntention = intention

                    logger.debug {
                        "[$name] iteration $iteration/${maxIterations}\n\nintention='${intention.intention}\n\n'tool='${intention.toolName}\n\n'"
                    }

                    when {
                        intention.toolName == AgentIntentionGenerator.ANSWER_TOOL -> {
                            continueLoop = false
                        }

                        else -> {
                            val gateOutcome =
                                handleToolExecution(
                                    accumulatedEvents = accumulatedEvents,
                                    intention = intention,
                                    namespaceId = namespaceId,
                                    caseId = caseId,
                                    shouldContinue = shouldContinue,
                                    emitEvent = { event -> emit(event) },
                                )
                            if (gateOutcome == GateOutcome.AwaitingConfirmation) {
                                continueLoop = false
                                hasUnresolvedConfirmation = true
                            }
                        }
                    }
                }

                if (!hasUnresolvedConfirmation) {
                    if (iteration >= maxIterations) {
                        emit(
                            WarnEvent(
                                namespaceId = namespaceId,
                                caseId = caseId,
                                message = "Agent reached maximum iterations ($maxIterations) without completing",
                            ),
                        )
                    }
                    if (shouldContinue()) {
                        generateFinalResponse(
                            accumulatedEvents = accumulatedEvents,
                            lastIntention = lastIntention,
                            namespaceId = namespaceId,
                            caseId = caseId,
                            shouldContinue = shouldContinue,
                            emitEvent = { event -> emit(event) },
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
                }
            } catch (e: AgentInterrupt) {
                // Not an error: a tool requested a structured interruption of this agent run.
                emitInterruptEvents(this@AgentAdvanced, e, namespaceId, caseId, logger)
            } catch (e: NonTransientAiException) {
                emitProviderErrorEvents(this@AgentAdvanced, e, namespaceId, caseId, logger)
            } catch (e: ConfirmationConfigurationException) {
                // DI wiring bug — surface loudly so prod logs catch it. Still emit a WarnEvent
                // so the per-case lifecycle terminates cleanly, but the operator-facing signal
                // is the error log, not the per-run warning.
                logger.error(e) { "AgentAdvanced confirmation flow misconfigured for case $caseId" }
                emit(
                    WarnEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        message = "Confirmation flow misconfigured: ${e.message}",
                    ),
                )
            } catch (e: ToolNotFoundException) {
                // Tool registry desync OR LLM hallucinated a tool name — ops-relevant signal,
                // log at error level so it surfaces in production monitoring. Still emit a
                // WarnEvent so the per-case lifecycle terminates cleanly.
                logger.error(e) { "AgentAdvanced received an intention referencing an unknown tool for case $caseId" }
                emit(
                    WarnEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        message = "Unknown tool referenced: ${e.message}",
                    ),
                )
            } catch (e: Exception) {
                logger.error(e) { "Error during agent execution" }
                emit(
                    WarnEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        message = "Error during agent execution: ${e.message}",
                    ),
                )
            }
        }

    private suspend fun handleRepetitionDetection(
        events: List<CaseEvent>,
        namespaceId: UUID,
        caseId: UUID,
        warningAlreadyEmitted: Boolean,
        emitEvent: suspend (CaseEvent) -> Unit,
    ): String? {
        val repeatedTool = detectRepetitionLoop(events)
        return when {
            repeatedTool == null -> {
                null
            }

            else -> {
                val msg =
                    "Calling a tool with the same parameters will produce the same results.\n" +
                        "You have called the tool $repeatedTool $REPETITION_THRESHOLD times within the last $REPETITION_WINDOW tool calls. " +
                        "If the tool has not added meaningful information to the conversation, " +
                        "stop calling it and consider the next step toward achieving the user's goal. " +
                        "If you do not have enough information to proceed, use ${AgentIntentionGenerator.ANSWER_TOOL} to ask the user for further instructions."
                if (!warningAlreadyEmitted) {
                    logger.warn {
                        "Repetition loop detected: $repeatedTool called $REPETITION_THRESHOLD times within the last $REPETITION_WINDOW tool calls"
                    }
                    emitEvent(WarnEvent(namespaceId = namespaceId, caseId = caseId, message = msg))
                }
                msg
            }
        }
    }

    /**
     * Executes the tool for the given [intention], handling the confirmation gate when the
     * tool's [confirmationMode] is not [ConfirmationMode.NONE].
     *
     * @return [GateOutcome.AwaitingConfirmation] if a [PendingConfirmationEvent] was emitted
     *   and the agent must exit the loop; [GateOutcome.ContinueLoop] if the tool was executed
     *   normally or skipped because [shouldContinue] returned `false`.
     */
    private suspend fun handleToolExecution(
        accumulatedEvents: MutableList<CaseEvent>,
        intention: IntentionGeneratedEvent,
        namespaceId: UUID,
        caseId: UUID,
        shouldContinue: () -> Boolean,
        emitEvent: suspend (CaseEvent) -> Unit,
    ): GateOutcome {
        if (!shouldContinue()) return GateOutcome.ContinueLoop

        val toolRequestId = UUID.randomUUID().toString()
        val parameters =
            generateParameters(
                accumulatedEvents = accumulatedEvents,
                intentionEvent = intention,
                namespaceId = namespaceId,
                caseId = caseId,
                toolRequestId = toolRequestId,
                emitEvent = emitEvent,
            )
        emitEvent(parameters)
        accumulatedEvents.add(parameters)

        if (!shouldContinue()) return GateOutcome.ContinueLoop

        val tool = context.tools.firstOrNull { it.name == intention.toolName }
        val toolCtx = tool?.let { buildToolContext(it.name, namespaceId) }
        val confirmationMode =
            if (tool != null && toolCtx != null) {
                tool.getConfirmationMode(parameters.args, toolCtx)
            } else {
                ConfirmationMode.NONE
            }
        return when {
            tool != null && toolCtx != null && confirmationMode != ConfirmationMode.NONE -> {
                handleConfirmationGate(
                    tool = tool,
                    mode = confirmationMode,
                    argsJson = parameters.args,
                    toolRequestId = toolRequestId,
                    parameters = parameters,
                    toolCtx = toolCtx,
                    accumulatedEvents = accumulatedEvents,
                    namespaceId = namespaceId,
                    caseId = caseId,
                    emitEvent = emitEvent,
                )
            }

            else -> {
                // Standard path — tool doesn't require confirmation (or tool not found).
                if (!shouldContinue()) {
                    GateOutcome.ContinueLoop
                } else {
                    // executeTool always returns a ToolResponseEvent, even on AgentInterrupt.
                    // We must emit and accumulate the response before re-throwing so the event
                    // history stays well-formed (every ToolRequestEvent has a matching response).
                    var interrupt: AgentInterrupt? = null
                    val response =
                        try {
                            executeTool(parameters, namespaceId, caseId)
                        } catch (e: AgentInterrupt) {
                            interrupt = e
                            ToolResponseEvent(
                                namespaceId = namespaceId,
                                caseId = caseId,
                                toolRequestId = parameters.toolRequestId,
                                toolName = parameters.toolName,
                                output =
                                    MessageContent.Text(
                                        when (e) {
                                            is AgentInterrupt.Redirect -> "Redirecting to agent '${e.targetAgentName}'."
                                        },
                                    ),
                                success = true,
                            )
                        }
                    emitEvent(response)
                    accumulatedEvents.add(response)
                    interrupt?.let { throw it }
                    GateOutcome.ContinueLoop
                }
            }
        }
    }

    /**
     * Handles the confirmation gate for a tool whose resolved mode is not [NONE].
     * The mode is resolved by the caller via [StandardTool.getConfirmationMode] and
     * passed in as [mode], allowing the tool to return a dynamic mode based on args
     * and case events (e.g. bypass when an in-session create makes a follow-up update
     * implicit).
     *
     * - [ConfirmationMode.EVERY_TIME]: always emits a [PendingConfirmationEvent] +
     *   IN-CHANNEL [MessageEvent] and returns [GateOutcome.AwaitingConfirmation].
     * - [ConfirmationMode.INFER]: delegates to [ConfirmationManager.shouldConfirm];
     *   if implicit consent is detected, executes the tool directly and returns
     *   [GateOutcome.ContinueLoop]; otherwise behaves like [EVERY_TIME].
     */
    private suspend fun handleConfirmationGate(
        tool: StandardTool<*>,
        mode: ConfirmationMode,
        argsJson: String?,
        toolRequestId: String,
        parameters: ToolRequestEvent,
        toolCtx: ToolContext,
        accumulatedEvents: MutableList<CaseEvent>,
        namespaceId: UUID,
        caseId: UUID,
        emitEvent: suspend (CaseEvent) -> Unit,
    ): GateOutcome {
        val firstLevelHistory = context.buildMessages(accumulatedEvents.filter { it.type.isFirstLevel() })
        val needsExplicit =
            when (mode) {
                ConfirmationMode.EVERY_TIME -> {
                    true
                }

                ConfirmationMode.INFER -> {
                    context.confirmationManager.shouldConfirm(
                        chatClient = context.chatClient,
                        firstLevelHistory = firstLevelHistory,
                        actionLabel = "Tool ${tool.name}",
                        proposedData = argsJson ?: "{}",
                        toolInstructions = tool.getConfirmationInstructions(),
                    )
                }

                ConfirmationMode.NONE -> {
                    false
                }
            }

        return when {
            !needsExplicit -> {
                // User already implicitly confirmed — execute directly.
                executeUnderImplicitConsent(
                    tool = tool,
                    argsJson = argsJson,
                    toolRequestId = toolRequestId,
                    parameters = parameters,
                    toolCtx = toolCtx,
                    accumulatedEvents = accumulatedEvents,
                    namespaceId = namespaceId,
                    caseId = caseId,
                    emitEvent = emitEvent,
                )
            }

            else -> {
                // Explicit confirmation required — emit PendingConfirmationEvent + IN-CHANNEL question.
                val question =
                    context.confirmationManager.formulateQuestion(
                        context = context,
                        chatClient = context.chatClient,
                        accumulatedEvents = accumulatedEvents,
                        fallbackLabel = tool.name,
                        pendingData = argsJson ?: "{}",
                        guidelines = buildUserFacingGuidelines(accumulatedEvents),
                    )
                emitEvent(
                    PendingConfirmationEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        toolRequestId = toolRequestId,
                        toolName = parameters.toolName,
                        inputJson = argsJson ?: "{}",
                        toolsCAInstructions = tool.getConfirmationInstructions(),
                    ),
                )
                // IN-CHANNEL: emit a MessageEvent so the LLM sees the pause naturally
                // in the conversation context, and the user sees the question in the chat
                // UI as a regular agent message (no typed button — free-form reply).
                emitEvent(
                    MessageEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        actor = Actor(id.toString(), name, ActorRole.AGENT),
                        content = listOf(MessageContent.Text(question)),
                    ),
                )
                emitEvent(
                    AgentFinishedEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        agentId = id,
                        agentName = name,
                    ),
                )
                GateOutcome.AwaitingConfirmation
            }
        }
    }

    /**
     * Executes the tool directly when implicit consent was detected by [ConfirmationManager.shouldConfirm].
     * Emits a [ToolResponseEvent] reflecting the actual outcome (success or failure).
     *
     * @return always [GateOutcome.ContinueLoop] — the caller continues the intention loop normally.
     */
    private suspend fun executeUnderImplicitConsent(
        tool: StandardTool<*>,
        argsJson: String?,
        toolRequestId: String,
        parameters: ToolRequestEvent,
        toolCtx: ToolContext,
        accumulatedEvents: MutableList<CaseEvent>,
        namespaceId: UUID,
        caseId: UUID,
        emitEvent: suspend (CaseEvent) -> Unit,
    ): GateOutcome {
        val response =
            try {
                val result = tool.executeWithJson(argsJson, toolCtx)
                ToolResponseEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    toolRequestId = toolRequestId,
                    toolName = parameters.toolName,
                    output = MessageContent.Text(result.output),
                    success = result.success,
                    toolMetadata = result.metadata,
                )
            } catch (e: Exception) {
                logger.warn(e) {
                    "[AgentAdvanced] implicit-consent tool execution failed for ${tool.name}"
                }
                ToolResponseEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    toolRequestId = toolRequestId,
                    toolName = parameters.toolName,
                    output = MessageContent.Text("Error executing tool: ${e.message}"),
                    success = false,
                )
            }
        emitEvent(response)
        accumulatedEvents.add(response)
        return GateOutcome.ContinueLoop
    }

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
     * Closes an orphan [PendingConfirmationEvent] durably so it is not re-detected on
     * every subsequent run when the tool is missing or input is malformed.
     *
     * Emits three events: a [ConfirmationResolvedEvent] (confirmed=false), an IN-CHANNEL
     * [MessageEvent] so the LLM stops re-questioning, and a [WarnEvent] for ops.
     *
     * @return the accumulated events produced (caller must emit and append them).
     */
    private fun buildOrphanCloseEvents(
        pending: PendingConfirmationEvent,
        reason: String,
        namespaceId: UUID,
        caseId: UUID,
    ): List<CaseEvent> {
        logger.warn { "[AgentAdvanced] closing orphan pending '${pending.toolName}': $reason" }
        val errorText = "Cannot resolve confirmation: $reason"
        return listOf(
            ConfirmationResolvedEvent(
                namespaceId = namespaceId,
                caseId = caseId,
                pendingEventId = pending.metadata.id,
                confirmed = false,
                resultText = errorText,
            ),
            MessageEvent(
                namespaceId = namespaceId,
                caseId = caseId,
                actor = Actor(id.toString(), name, ActorRole.AGENT),
                content = listOf(MessageContent.Text(errorText)),
            ),
            WarnEvent(
                namespaceId = namespaceId,
                caseId = caseId,
                message = "Cannot resolve pending confirmation: $reason",
            ),
        )
    }

    /**
     * Resolves an unresolved [PendingConfirmationEvent].
     *
     * @return one of the [ConfirmationResolution] sealed cases — see the type's KDoc for
     *   the routing semantics. The caller closes the turn on [Unresolved]/[Aborted] and
     *   falls through to the normal intention loop on [Applied]/[Rejected].
     */
    private suspend fun handleConfirmationResolution(
        events: List<CaseEvent>,
        pending: PendingConfirmationEvent,
        namespaceId: UUID,
        caseId: UUID,
        shouldContinue: () -> Boolean,
        emitEvent: suspend (CaseEvent) -> Unit,
        appendTo: MutableList<CaseEvent>,
    ): ConfirmationResolution {
        val tool = context.tools.firstOrNull { it.name == pending.toolName }
        return when {
            tool == null -> {
                val orphanEvents =
                    buildOrphanCloseEvents(
                        pending = pending,
                        reason = "tool '${pending.toolName}' not available in this agent.",
                        namespaceId = namespaceId,
                        caseId = caseId,
                    )
                orphanEvents.forEach { emitEvent(it) }
                appendTo += orphanEvents
                ConfirmationResolution.Aborted
            }

            else -> {
                // CRITICAL (AC7): only events strictly AFTER the pending count as a reply.
                // Use index comparison (deterministic regardless of timestamp ordering).
                val pendingIndex = events.indexOfFirst { it.metadata.id == pending.metadata.id }
                val eventsAfterPending =
                    if (pendingIndex >= 0) events.subList(pendingIndex + 1, events.size) else emptyList()

                val freeFormText =
                    eventsAfterPending
                        .filterIsInstance<MessageEvent>()
                        .lastOrNull { it.actor.role == ActorRole.USER }
                        ?.content
                        ?.filterIsInstance<MessageContent.Text>()
                        ?.joinToString(" ") { it.content }

                when {
                    freeFormText.isNullOrBlank() -> {
                        // AC7: reload session without user reply. No reply yet.
                        ConfirmationResolution.Unresolved
                    }

                    else -> {
                        // Slice the history shown to analyzeConfirmation to events at-or-after the pending.
                        // Prevents the LLM judge from picking up a "yes" from an unrelated earlier turn
                        // (defense-in-depth for tools with confirmationMode=EVERY_TIME).
                        val caseEventsAtOrAfterPending =
                            events.subList(
                                pendingIndex,
                                events.size,
                            )
                        val firstLevelCaseEventsAtOrAfterPending =
                            caseEventsAtOrAfterPending.filter { it.type.isFirstLevel() }
                        val firstLevelHistoryFromPending = context.buildMessages(firstLevelCaseEventsAtOrAfterPending)
                        val decision =
                            context.confirmationManager.analyzeConfirmation(
                                chatClient = context.chatClient,
                                firstLevelHistory = firstLevelHistoryFromPending,
                                pendingPayload = pending.inputJson,
                                toolInstructions = pending.toolsCAInstructions,
                            )
                        when (decision) {
                            ConfirmationDecision.AMBIGUOUS -> {
                                // LLM-generated re-ask in the conversation's language, IN-CHANNEL — same
                                // mechanism as the initial confirmation prompt. Pending stays open so the
                                // next user reply re-runs analyzeConfirmation against this new clarification.
                                val clarificationQuestion =
                                    context.confirmationManager.formulateQuestion(
                                        context = context,
                                        chatClient = context.chatClient,
                                        accumulatedEvents = caseEventsAtOrAfterPending,
                                        fallbackLabel = pending.toolName,
                                        pendingData = pending.inputJson,
                                        guidelines = buildUserFacingGuidelines(events),
                                    )
                                val clarification =
                                    MessageEvent(
                                        namespaceId = namespaceId,
                                        caseId = caseId,
                                        actor = Actor(id.toString(), name, ActorRole.AGENT),
                                        content = listOf(MessageContent.Text(clarificationQuestion)),
                                    )
                                emitEvent(clarification)
                                appendTo += clarification
                                ConfirmationResolution.Unresolved
                            }

                            else -> {
                                val confirmed = decision == ConfirmationDecision.CONFIRMED
                                // Guard before destructive call — mitigate cancel mid-execution.
                                if (!shouldContinue()) {
                                    ConfirmationResolution.Unresolved
                                } else {
                                    executePostConfirmation(
                                        tool = tool,
                                        confirmed = confirmed,
                                        pending = pending,
                                        toolCtx = buildToolContext(pending.toolName, namespaceId),
                                        namespaceId = namespaceId,
                                        caseId = caseId,
                                        emitEvent = emitEvent,
                                        appendTo = appendTo,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Executes the tool (or [onRejected]) after the user's confirmation decision is known,
     * then emits the resolution events.
     *
     * @return [ConfirmationResolution.Applied] when the user confirmed AND the tool ran
     *   without throwing; [ConfirmationResolution.Rejected] when the user declined;
     *   [ConfirmationResolution.Aborted] when the tool threw post-confirmation.
     */
    private suspend fun executePostConfirmation(
        tool: StandardTool<*>,
        confirmed: Boolean,
        pending: PendingConfirmationEvent,
        toolCtx: ToolContext,
        namespaceId: UUID,
        caseId: UUID,
        emitEvent: suspend (CaseEvent) -> Unit,
        appendTo: MutableList<CaseEvent>,
    ): ConfirmationResolution {
        val resultText: String
        val toolResult: ToolExecutionResult?
        val executionFailed: Boolean

        if (confirmed) {
            var failed = false
            var result: ToolExecutionResult? = null
            val output =
                try {
                    result = tool.executeWithJson(pending.inputJson, toolCtx)
                    result.output
                } catch (e: Exception) {
                    logger.warn(e) {
                        "[AgentAdvanced] tool execution failed during confirmation resolution for ${pending.toolName}"
                    }
                    failed = true
                    "Error during tool execution: ${e.message}"
                }
            resultText = output
            toolResult = result
            executionFailed = failed
        } else {
            resultText = tool.onRejected()
            toolResult = null
            executionFailed = false
        }

        // Authoritative outcome: the action took effect only if the user confirmed AND the
        // tool ran without throwing AND the tool itself reported success.
        val effectiveSuccess = confirmed && !executionFailed && (toolResult?.success ?: false)

        val resolved =
            ConfirmationResolvedEvent(
                namespaceId = namespaceId,
                caseId = caseId,
                pendingEventId = pending.metadata.id,
                confirmed = effectiveSuccess,
                resultText = resultText,
            )
        emitEvent(resolved)
        appendTo += resolved

        // IN-CHANNEL: prefix decision explicitly so the LLM disambiguates the resolution.
        // "User confirmed." only when the action actually applied; otherwise "User declined."
        // (covers both explicit rejection and confirmed-but-failed execution).
        val decisionPrefix = if (effectiveSuccess) "User confirmed. " else "User declined. "
        val resolutionMessage =
            MessageEvent(
                namespaceId = namespaceId,
                caseId = caseId,
                actor = Actor(id.toString(), name, ActorRole.AGENT),
                content = listOf(MessageContent.Text("$decisionPrefix$resultText")),
            )
        emitEvent(resolutionMessage)
        appendTo += resolutionMessage

        // Synthetic ToolResponseEvent paired on the original toolRequestId so the next
        // intention turn sees a coherent lastToolResponse. success = action actually applied.
        val syntheticResponse =
            ToolResponseEvent(
                namespaceId = namespaceId,
                caseId = caseId,
                toolRequestId = pending.toolRequestId,
                toolName = pending.toolName,
                output = MessageContent.Text(resultText),
                success = effectiveSuccess,
                toolMetadata = toolResult?.metadata ?: emptyMap(),
            )
        emitEvent(syntheticResponse)
        appendTo += syntheticResponse

        return when {
            // User confirmed AND tool ran successfully → fall through, LLM can comment.
            confirmed && !executionFailed -> ConfirmationResolution.Applied

            // User explicitly refused → fall through, LLM can produce a natural follow-up.
            !confirmed -> ConfirmationResolution.Rejected

            // Confirmed but the tool threw (executionFailed=true) → no useful continuation;
            // the user just got bitten by a real failure, don't push the LLM to retry.
            else -> ConfirmationResolution.Aborted
        }
    }

    private fun buildToolContext(
        toolName: String,
        namespaceId: UUID,
    ): ToolContext =
        ToolContext(
            namespaceId = namespaceId,
            userId = userId,
            userExternalId = userExternalId,
            caseEvents = filterEventsByIntegration(toolName, caseEventsProvider()),
        )

    private fun filterEventsByIntegration(
        toolName: String,
        all: List<CaseEvent>,
    ): List<CaseEvent> {
        val integrationPrefix = toolName.substringBefore("__", missingDelimiterValue = "")
        return if (integrationPrefix.isEmpty()) {
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

    private suspend fun generateFinalResponse(
        accumulatedEvents: List<CaseEvent>,
        lastIntention: IntentionGeneratedEvent?,
        namespaceId: UUID,
        caseId: UUID,
        shouldContinue: () -> Boolean,
        emitEvent: suspend (CaseEvent) -> Unit,
    ) {
        val userFullName =
            accumulatedEvents
                .filterIsInstance<MessageEvent>()
                .lastOrNull()
                ?.sessionContext
                ?.get("userContext")
                ?.let { it as? Map<String, String> }
                ?.let { ctx ->
                    listOfNotNull(ctx["firstName"], ctx["lastName"])
                        .joinToString(" ")
                        .ifBlank { null }
                }

        // Build the final prompt in clear, composable sections
        val prompt =
            buildString {
                lastIntention?.let {
                    if (it.isFailedIntention) {
                        appendLine("Your analysis:")
                        appendLine()
                        appendLine("The system encountered an internal error and was unable to determine the next action to perform.")
                        appendLine("Part or all of the requested operation was not performed.")
                        appendLine()
                        appendLine("I must inform the user that something went wrong, and suggest they try again or contact the support.")
                    } else {
                        appendLine("Your analysis: ${it.intention}")
                    }
                    appendLine()
                }

                appendLine("Based on the above conversation and your analysis, provide your response to the user.")

                buildUserFacingGuidelines(accumulatedEvents)?.let {
                    appendLine()
                    appendLine(it)
                }

                appendLine()
                appendLine(
                    "- Base your response solely on the content of this conversation history. If information is missing, say so explicitly.",
                )
                userFullName?.let {
                    appendLine("Now, continue your conversation with $it.")
                } ?: appendLine("Now, continue your conversation with the user.")
            }

        val messages = context.buildMessages(accumulatedEvents, prompt)

        logger.debug { "[$name] generateFinalResponse — sending ${messages.size} messages" }
        logger.trace { "[$name] generateFinalResponse intentionContext:\n$prompt" }

        val contentBuilder = StringBuilder()
        context.chatClient
            .prompt(Prompt(messages))
            .stream()
            .content()
            .asFlow()
            .takeWhile { shouldContinue() }
            .collect { chunk ->
                contentBuilder.append(chunk)
                emitEvent(TextChunkEvent(namespaceId = namespaceId, caseId = caseId, chunk = chunk))
            }
        val content = contentBuilder.toString().stripConversationTags()
        if (content.isNotEmpty()) {
            val msg =
                MessageEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    actor = Actor(id.toString(), name, ActorRole.AGENT),
                    content = listOf(MessageContent.Text(content)),
                )
            emitEvent(msg)
        }
    }

    /**
     * Assembles the user-facing communication guidelines shared across any LLM-generated
     * text shown to the user (final response, confirmation prompts, re-ask questions).
     *
     * Combines:
     * - A language hint anchored on recent user messages (from [buildLanguageHint]).
     * - A non-discrimination rule.
     * - A no-technical-IDs rule.
     *
     * Returns `null` when no user messages are present and there is nothing to add
     * (defensive — the static rules make a non-null result almost certain in practice).
     */
    internal fun buildUserFacingGuidelines(events: List<CaseEvent>): String? {
        val lines =
            buildList {
                buildLanguageHint(events)?.let { add(it) }
                add(
                    "Do not discriminate based on gender, ethnicity, religion, age, physical appearance, " +
                        "or any other protected attribute. If the user's request implies such a step, " +
                        "clarify with the user that this cannot be done.",
                )
                add(
                    "Do not reference technical IDs unless explicitly asked. Instead, use a readable format " +
                        "such as the object's name, title, or a markdown representation.",
                )
            }
        return lines.joinToString("\n").ifBlank { null }
    }

    /**
     * Builds a language hint from the most recent user [MessageEvent]s in [events].
     *
     * Collects user messages from newest to oldest until the combined text reaches
     * [minChars], then returns an instruction anchored on the actual user text so the
     * LLM has a concrete language signal rather than an abstract directive.
     *
     * Returns `null` when no user messages are present (e.g. agent-only conversations),
     * so the caller can omit the hint entirely rather than defaulting to a hardcoded
     * language.
     */
    internal fun buildLanguageHint(
        events: List<CaseEvent>,
        targetChars: Int = LANGUAGE_HINT_TARGET_CHARS,
    ): String? {
        // Reverse the messages first so we collect newest-first, then extract text per message.
        // Reversing after flatMap would operate on individual text blocks, not on messages.
        val userMessages =
            events
                .filterIsInstance<MessageEvent>()
                .filter { it.actor.role == ActorRole.USER }
                .reversed()

        if (userMessages.isEmpty()) return null

        val collected = mutableListOf<String>()
        var total = 0
        for (message in userMessages) {
            val text =
                message.content
                    .filterIsInstance<MessageContent.Text>()
                    .joinToString(" ") { it.content.trim() }
                    .trim()
            if (text.isEmpty()) continue
            collected.add(text)
            total += text.length
            if (total >= targetChars) break
        }

        if (collected.isEmpty()) return null

        val sample = collected.reversed().joinToString(" / ") { "\"$it\"" }
        return "Respond in the same language the user is writing in (reference: $sample)."
    }

    internal fun detectRepetitionLoop(events: List<CaseEvent>): String? {
        // Identify synthetic ToolResponseEvent IDs (from confirmation resolution).
        val resolvedPendingIds =
            events
                .filterIsInstance<ConfirmationResolvedEvent>()
                .mapTo(mutableSetOf()) { it.pendingEventId }
        val syntheticToolRequestIds =
            events
                .filterIsInstance<PendingConfirmationEvent>()
                .filter { it.metadata.id in resolvedPendingIds }
                .mapTo(mutableSetOf()) { it.toolRequestId }

        // Build a lookup of toolRequestId → args for quick pairing with ToolResponseEvent.
        val argsByRequestId =
            events
                .filterIsInstance<ToolRequestEvent>()
                .associate { it.toolRequestId to (it.args?.trim() ?: "") }

        // Take last REPETITION_WINDOW ToolResponseEvent (success or failure) — a tool
        // that consistently fails with the same input is also a repetition loop.
        // If the window contains any synthetic (= confirmation resolution), do not signal
        // repetition — those are user-validated, not auto.
        val window =
            events
                .filterIsInstance<ToolResponseEvent>()
                .takeLast(REPETITION_WINDOW)

        if (window.size < REPETITION_WINDOW || window.any { it.toolRequestId in syntheticToolRequestIds }) return null

        // A repetition is detected when the same (toolName, args) pair appears at least
        // REPETITION_THRESHOLD times in the window. This catches two-tool alternation
        // loops (A, B, A, B, A) as well as straight repetition (A, A, A, ...).
        // Calls to the same tool with different payloads are legitimate exploration
        // and do not count toward the threshold (WZ-32262).
        return window
            .groupingBy { e -> e.toolName to (argsByRequestId[e.toolRequestId] ?: "") }
            .eachCount()
            .entries
            .firstOrNull { it.value >= REPETITION_THRESHOLD }
            ?.key
            ?.first
    }

    private suspend fun generateParameters(
        accumulatedEvents: List<CaseEvent>,
        intentionEvent: IntentionGeneratedEvent,
        namespaceId: UUID,
        caseId: UUID,
        toolRequestId: String,
        emitEvent: suspend (CaseEvent) -> Unit,
    ): ToolRequestEvent {
        val tool =
            context.tools.firstOrNull { it.name == intentionEvent.toolName }
                ?: throw ToolNotFoundException(intentionEvent.toolName)

        // Enrichment loop for multi-phase parameter preparation
        val enrichmentContent =
            if (tool.getIntermediatePhaseCount() > 0) {
                runEnrichmentPhases(tool, accumulatedEvents, intentionEvent, namespaceId)
            } else {
                null
            }

        val enrichmentBlock =
            enrichmentContent?.let {
                "\n\nContext from preparation phases:\n$it"
            } ?: ""

        val basePrompt =
            """
Generate the parameters for the tool call below. You must generate a JSON object corresponding to the given input json schema.

Tool: ${tool.name}
Description: ${tool.description}
Input JSON Schema:
```
${tool.inputSchema}
```

Intention: ${intentionEvent.intention}$enrichmentBlock

Output requirements:
- Wrap the JSON object in <parameter> tags: <parameter>{ ... }</parameter>
- Inside the tags, start your response with `{` and end it with `}`.
- Do not emit any text, whitespace, or tokens before `{` or after `}` inside the tags.
- Do not use XML, tool-call wrappers, function tags, markdown, code fences, or any structural syntax other than JSON.
- Do not include comments, explanations, or reasoning.
- Only include properties defined in the schema.
            """.trimIndent()
        val accumulatedEventsWithoutCurrentToolCall = accumulatedEvents.dropLast(1)

        logger.debug { "[$name] generateParameters for '${tool.name}'" }
        logger.trace { "[$name] generateParameters prompt:\n$basePrompt" }

        val result =
            retryWithFallback<String, ToolRequestEvent>(
                maxAttempts = MAX_PARAMETER_ATTEMPTS,
                fallback = { lastRaw ->
                    logger.error {
                        "[$name] generateParameters: all $MAX_PARAMETER_ATTEMPTS attempts produced invalid JSON for '${tool.name}'. Last raw: $lastRaw"
                    }
                    // Do NOT pass the invalid output to the tool — it would cause a server-side
                    // error (e.g. HTTP 500) instead of a clean failure the agent can reason about.
                    // args=null signals the failure; the WarnEvent is emitted below after returning
                    // from retryWithFallback (fallback lambda is not suspend).
                    ToolRequestEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        toolRequestId = toolRequestId,
                        toolName = tool.name,
                        args = null,
                    )
                },
            ) { previousRaw ->
                attemptParameterGeneration(
                    basePrompt = basePrompt,
                    events = accumulatedEventsWithoutCurrentToolCall,
                    toolName = tool.name,
                    namespaceId = namespaceId,
                    caseId = caseId,
                    toolRequestId = toolRequestId,
                    previousRaw = previousRaw,
                )
            }

        // args=null means all retries were exhausted — emit a WarnEvent so the failure
        // is visible in the case event stream (cannot be done inside the non-suspend fallback).
        if (result.args == null) {
            val message =
                "Failed to generate valid JSON parameters for '${tool.name}' after $MAX_PARAMETER_ATTEMPTS attempts."
            emitEvent(WarnEvent(namespaceId = namespaceId, caseId = caseId, message = message))
        }

        return result
    }

    private fun attemptParameterGeneration(
        basePrompt: String,
        events: List<CaseEvent>,
        toolName: String,
        namespaceId: UUID,
        caseId: UUID,
        toolRequestId: String,
        previousRaw: String?,
    ): AttemptResult<String, ToolRequestEvent> {
        val retryHint =
            previousRaw?.let {
                """
                PREVIOUS FAILED ATTEMPT(S):
                The following output(s) were produced but are NOT valid JSON. Do NOT reproduce them:
                ---
                $it
                ---
                Generate ONLY a valid JSON object matching the schema. No explanation, no markdown fences.
                """.trimIndent()
            }
        val prompt = listOfNotNull(basePrompt, retryHint).joinToString("\n\n")

        val messages = context.buildMessages(events, prompt)
        val raw = callLlmForParameters(messages, toolName)

        logger.trace { "[$name] generateParameters raw response for '$toolName': $raw" }

        return if (isValidJson(raw)) {
            AttemptSuccess(
                ToolRequestEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    toolRequestId = toolRequestId,
                    toolName = toolName,
                    args = raw,
                ),
            )
        } else {
            logger.warn { "[$name] generateParameters: invalid JSON for '$toolName'. Raw: $raw" }
            AttemptFailure(raw)
        }
    }

    private fun callLlmForParameters(
        messages: List<org.springframework.ai.chat.messages.Message>,
        toolName: String,
    ): String {
        val raw =
            context.chatClient
                .prompt(Prompt(messages))
                .call()
                .content()
                ?.trim() ?: "{}"
        return stripJsonFence(raw)
    }

    private fun isValidJson(raw: String): Boolean =
        runCatching {
            objectMapper.readTree(raw)
        }.isSuccess

    private suspend fun runEnrichmentPhases(
        tool: StandardTool<*>,
        accumulatedEvents: List<CaseEvent>,
        intentionEvent: IntentionGeneratedEvent,
        namespaceId: UUID,
    ): String? {
        var previousContent: String? = null

        for (i in 0 until tool.getIntermediatePhaseCount()) {
            val descriptor = tool.getIntermediatePhaseDescriptor(i, previousContent)

            val fullPrompt =
                """
${descriptor.prompt}

Input JSON Schema:
```
${descriptor.inputSchema}
```

Intention: ${intentionEvent.intention}

Generate ONLY the JSON object matching the input schema above, Output requirements:
- Start your response with the character `{` and end it with `}`.
- Do not emit any text, whitespace, or tokens before `{` or after `}`.
- Do not use XML, tool-call wrappers, function tags, markdown, code fences, or any structural syntax other than JSON.
- Do not include comments, explanations, or reasoning.
- Only include properties defined in the schema.
                """.trimIndent()

            val accumulatedEventsWithoutCurrentToolCall = accumulatedEvents.dropLast(1)
            val messages = context.buildMessages(accumulatedEventsWithoutCurrentToolCall, fullPrompt)

            logger.debug { "[$name] enrichment phase $i for '${tool.name}' — sending ${messages.size} messages" }

            val rawJson =
                context.chatClient
                    .prompt(Prompt(messages))
                    .call()
                    .content()
                    ?.trim() ?: "{}"
            val phaseJson = stripJsonFence(rawJson)

            logger.debug { "[$name] enrichment phase $i result for '${tool.name}': $phaseJson" }

            val toolCtx = buildToolContext(tool.name, namespaceId)
            val result = tool.enrich(i, phaseJson, toolCtx)

            if (!result.success) {
                logger.warn { "[$name] enrichment phase $i failed for '${tool.name}': ${result.errorMessage}" }
                return null
            }

            previousContent = result.content
        }

        return previousContent
    }

    /**
     * Strips formatting wrappers around a JSON payload, in priority order:
     * 1. `<parameter>...</parameter>` tags — the canonical format requested in the prompt
     * 2. Markdown code fences ` ```json ... ``` ` — tolerated as a fallback
     * 3. Raw string — last resort if neither wrapper is present
     */
    private fun stripJsonFence(raw: String): String =
        PARAMETER_TAG_REGEX
            .find(raw)
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?: JSON_FENCE_REGEX
                .matchEntire(raw)
                ?.groupValues
                ?.get(1)
                ?.trim()
            ?: raw

    private suspend fun executeTool(
        toolRequest: ToolRequestEvent,
        namespaceId: UUID,
        caseId: UUID,
    ): ToolResponseEvent {
        val tool = context.tools.firstOrNull { it.name == toolRequest.toolName }
        return when {
            tool == null -> {
                ToolResponseEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    toolRequestId = toolRequest.toolRequestId,
                    toolName = toolRequest.toolName,
                    output = MessageContent.Text("Tool not found: ${toolRequest.toolName}"),
                    success = false,
                )
            }

            else -> {
                val startMs = System.currentTimeMillis()
                try {
                    val filteredEvents = filterEventsByIntegration(toolRequest.toolName, caseEventsProvider())
                    val result: ToolExecutionResult =
                        tool.executeWithJson(
                            toolRequest.args,
                            ToolContext(
                                namespaceId = namespaceId,
                                userId = userId,
                                userExternalId = userExternalId,
                                caseEvents = filteredEvents,
                            ),
                        )
                    ToolResponseEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        toolRequestId = toolRequest.toolRequestId,
                        toolName = toolRequest.toolName,
                        output = MessageContent.Text(result.output),
                        success = result.success,
                        durationMs = System.currentTimeMillis() - startMs,
                        toolMetadata = result.metadata,
                    )
                } catch (e: AgentInterrupt) {
                    // Re-throw so handleToolExecution() can emit a proper ToolResponseEvent
                    // before the interrupt propagates to the run() catch block.
                    throw e
                } catch (e: Exception) {
                    logger.warn(e) {
                        "[AgentAdvanced] error during tool execution for ${tool.name}"
                    }
                    ToolResponseEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        toolRequestId = toolRequest.toolRequestId,
                        toolName = toolRequest.toolName,
                        output = MessageContent.Text("Error executing tool: ${e.message}"),
                        success = false,
                        durationMs = System.currentTimeMillis() - startMs,
                    )
                }
            }
        }
    }

    companion object : KLogging() {
        /** How many recent tool responses to inspect for repetition. */
        internal const val REPETITION_WINDOW = 5

        /**
         * Target character count for the language hint sample: collect user messages
         * newest-first until this threshold is reached, or until all messages are
         * exhausted — whichever comes first.
         */
        internal const val LANGUAGE_HINT_TARGET_CHARS = 200

        /** How many identical (toolName, args) calls within the window trigger a warning. */
        internal const val REPETITION_THRESHOLD = 3
        internal const val MAX_PARAMETER_ATTEMPTS = 3

        /**
         * Matches JSON content inside `<parameter>...</parameter>` tags.
         * This is the canonical output format requested in the parameter generation prompt.
         */
        private val PARAMETER_TAG_REGEX =
            Regex(
                """<parameter>\s*(.*?)\s*</parameter>""",
                setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
            )

        private val JSON_FENCE_REGEX =
            Regex(
                """^```(?:json)?\s*(.*?)\s*```$""",
                setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
            )
    }
}
