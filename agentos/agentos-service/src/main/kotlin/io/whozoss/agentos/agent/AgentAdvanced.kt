package io.whozoss.agentos.agent

import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.agent.Agent
import io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent
import io.whozoss.agentos.sdk.caseEvent.AgentRunningEvent
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
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.reactive.asFlow
import mu.KLogging
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt
import java.util.UUID

/**
 * Outcome of [AgentAdvanced.handleConfirmationResolution].
 *
 * Discriminates four runtime situations so that [AgentAdvanced.run] can route each
 * to the correct post-resolution behaviour: close the turn cleanly vs fall through
 * to the normal intention loop.
 *
 * - [Unresolved]: the pending is still alive (no user reply yet, or an AMBIGUOUS
 *   re-ask was just emitted, or the run was cancelled mid-flight). Close the turn.
 * - [Applied]: the user confirmed AND the tool ran successfully. Fall through so
 *   the LLM can comment naturally on the outcome.
 * - [Rejected]: the user explicitly refused. Fall through so the LLM can produce
 *   a clarifying follow-up. The hardened `shouldConfirm` clauses prevent an
 *   autonomous retry of the same destructive intention without a fresh explicit
 *   prompt.
 * - [Aborted]: tool threw post-confirm OR the pending was orphan-closed (tool
 *   missing, etc.). Action did not apply AND there's nothing meaningful to invite
 *   the user to do next — close the turn cleanly.
 */
private sealed interface ConfirmationResolution {
    data object Unresolved : ConfirmationResolution
    data object Applied : ConfirmationResolution
    data object Rejected : ConfirmationResolution
    data object Aborted : ConfirmationResolution
}

class AgentAdvanced(
    override val metadata: EntityMetadata = EntityMetadata(),
    override val name: String,
    private val context: AgentAdvancedContext,
    private val intentionGenerator: AgentIntentionGenerator,
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

            emit(
                AgentRunningEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    agentId = id,
                    agentName = name,
                ),
            )

            val accumulatedEvents = events.toMutableList()

            // WZ-31596 IN-CHANNEL pre-flight: resolve unresolved PendingConfirmationEvent
            // before entering the intention loop. Mirrors Copilote.handlePendingConfirmation.
            val unresolvedPending = findUnresolvedPendingConfirmation(accumulatedEvents)
            if (unresolvedPending != null) {
                val resolution = handleConfirmationResolution(
                    events = accumulatedEvents,
                    pending = unresolvedPending,
                    namespaceId = namespaceId,
                    caseId = caseId,
                    shouldContinue = shouldContinue,
                    appendTo = accumulatedEvents,
                    emitEvent = { event -> emit(event) },
                )
                when (resolution) {
                    ConfirmationResolution.Unresolved,
                    ConfirmationResolution.Aborted -> {
                        // Unresolved: no reply yet / AMBIGUOUS re-ask in flight / run cancelled.
                        // Aborted:    tool threw post-confirm or orphan-closed pending.
                        // Both cases: no actionable continuation — close the turn cleanly.
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
                    ConfirmationResolution.Rejected -> {
                        // User explicitly refused. Fall through to the intention loop so the LLM
                        // can produce a natural conversational follow-up (e.g. "alors quel
                        // fichier veux-tu supprimer ?"). The hardened shouldConfirm clauses
                        // (target ambiguity, destructive-exact-match) act as the safety net
                        // against an autonomous retry of the just-refused destructive action.
                    }
                    ConfirmationResolution.Applied -> {
                        // Confirmed and the tool applied — fall through so the LLM can comment
                        // naturally (e.g. "OK, deleted") and continue conversationally.
                    }
                }
            }

            var iteration = 0
            var continueLoop = true
            var lastIntention: IntentionGeneratedEvent? = null
            var repetitionWarningEmitted = false
            var interruptedByConfirmation = false

            try {
                while (continueLoop && iteration < maxIterations && shouldContinue()) {
                    iteration++

                    if (!shouldContinue()) break
                    emit(ThinkingEvent(namespaceId = namespaceId, caseId = caseId))

                    val repetitionWarning =
                        handleRepetitionDetection(
                            accumulatedEvents,
                            namespaceId,
                            caseId,
                            repetitionWarningEmitted,
                        ) { event -> emit(event) }
                    repetitionWarningEmitted = repetitionWarning != null

                    val intention =
                        intentionGenerator.generate(context, accumulatedEvents, namespaceId, caseId, repetitionWarning)
                    emit(intention)
                    accumulatedEvents.add(intention)
                    lastIntention = intention

                    logger.debug {
                        "[$name] iteration $iteration/${maxIterations}\n\nintention='${intention.intention}\n\n'tool='${intention.toolName}\n\n'"
                    }

                    if (intention.toolName == AgentIntentionGenerator.ANSWER_TOOL) {
                        continueLoop = false
                    } else {
                        val awaiting =
                            handleToolExecution(
                                accumulatedEvents,
                                intention,
                                namespaceId,
                                caseId,
                                shouldContinue,
                            ) { event -> emit(event) }
                        if (awaiting) {
                            interruptedByConfirmation = true
                            continueLoop = false
                        }
                    }
                }
                if (iteration >= maxIterations && !interruptedByConfirmation) {
                    emit(
                        WarnEvent(
                            namespaceId = namespaceId,
                            caseId = caseId,
                            message = "Agent reached maximum iterations ($maxIterations) without completing",
                        ),
                    )
                }
                if (shouldContinue() && !interruptedByConfirmation) {
                    generateFinalResponse(
                        accumulatedEvents,
                        lastIntention,
                        namespaceId,
                        caseId,
                        shouldContinue,
                    ) { event -> emit(event) }
                }
                if (!interruptedByConfirmation) {
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
            } catch (e: AgentInterrupt) {
                // Not an error: a tool requested a structured interruption of this agent run.
                emitInterruptEvents(this@AgentAdvanced, e, namespaceId, caseId, logger)
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
        val repeatedTool = detectRepetitionLoop(events) ?: return null
        val msg =
            "Calling a tool with the same parameters will produce the same results.\n" +
                "You have called the tool $repeatedTool $REPETITION_DETECTION_WINDOW times consecutively. " +
                "If the tool has not added meaningful information to the conversation, " +
                "stop calling it and consider the next step toward achieving the user's goal. " +
                "If you do not have enough information to proceed, use ${AgentIntentionGenerator.ANSWER_TOOL} to ask the user for further instructions."
        if (!warningAlreadyEmitted) {
            logger.warn { "Repetition loop detected: $repeatedTool called $REPETITION_DETECTION_WINDOW consecutive times" }
            emitEvent(WarnEvent(namespaceId = namespaceId, caseId = caseId, message = msg))
        }
        return msg
    }

    /**
     * Returns true if a confirmation flow was triggered (the agent must exit and wait for
     * the user's reply); false if the tool was executed normally.
     */
    private suspend fun handleToolExecution(
        accumulatedEvents: MutableList<CaseEvent>,
        intention: IntentionGeneratedEvent,
        namespaceId: UUID,
        caseId: UUID,
        shouldContinue: () -> Boolean,
        emitEvent: suspend (CaseEvent) -> Unit,
    ): Boolean {
        if (!shouldContinue()) return false
        val toolRequestId = UUID.randomUUID().toString()
        val parameters =
            generateParameters(
                accumulatedEvents = accumulatedEvents,
                intentionEvent = intention,
                namespaceId = namespaceId,
                caseId = caseId,
                toolRequestId = toolRequestId,
            )
        emitEvent(parameters)
        accumulatedEvents.add(parameters)
        if (!shouldContinue()) return false

        val tool = context.tools.firstOrNull { it.name == intention.toolName }
        if (tool != null) {
            val toolCtx = buildToolContext(tool.name, namespaceId)
            val argsJson = parameters.args

            if (tool.requiresConfirmation(argsJson, toolCtx)) {
                val confirmationManager =
                    context.confirmationManager
                        ?: throw ConfirmationConfigurationException(
                            "Tool '${tool.name}' requires confirmation but no ConfirmationManager configured for AgentAdvanced.",
                        )

                val history = context.buildMessages(accumulatedEvents)
                val needsExplicit =
                    tool.bypassImplicitConsent ||
                        confirmationManager.shouldConfirm(
                            chatClient = context.chatClient,
                            history = history,
                            actionLabel = "Tool ${tool.name}",
                            proposedData = argsJson ?: "{}",
                        )
                if (!needsExplicit) {
                    // User already implicitly confirmed — fall through to direct execution.
                    // success reflects whether the tool actually applied (mirror executeTool
                    // and the post-confirmation path's executionFailed pattern).
                    var executionFailed = false
                    var toolResult: ToolExecutionResult? = null
                    val resultText: String =
                        try {
                            val r = tool.executeWithJson(argsJson, toolCtx)
                            toolResult = r
                            r.output
                        } catch (e: Exception) {
                            logger.warn(e) {
                                "[AgentAdvanced] implicit-consent tool execution failed for ${tool.name}"
                            }
                            executionFailed = true
                            "Error executing tool: ${e.message}"
                        }
                    val effectiveSuccess = !executionFailed && (toolResult?.success ?: false)
                    val response =
                        ToolResponseEvent(
                            namespaceId = namespaceId,
                            caseId = caseId,
                            toolRequestId = toolRequestId,
                            toolName = parameters.toolName,
                            output = MessageContent.Text(resultText),
                            success = effectiveSuccess,
                            toolMetadata = toolResult?.metadata ?: emptyMap(),
                        )
                    emitEvent(response)
                    accumulatedEvents.add(response)
                    return false
                }

                val question =
                    confirmationManager.formulateQuestion(
                        chatClient = context.chatClient,
                        history = history,
                        fallbackLabel = tool.name,
                        pendingData = argsJson ?: "{}",
                    )
                emitEvent(
                    PendingConfirmationEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        toolRequestId = toolRequestId,
                        toolName = parameters.toolName,
                        inputJson = argsJson ?: "{}",
                        analysisInstructions = tool.getConfirmationInstructions(),
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
                return true
            }
        }

        // Standard path — tool doesn't require confirmation.
        if (!shouldContinue()) return false
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
        return false
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
        appendTo: MutableList<CaseEvent>,
        emitEvent: suspend (CaseEvent) -> Unit,
    ): ConfirmationResolution {
        // Helper: close orphan pending durably with an error so it is not re-detected on
        // every subsequent run when the tool is missing / input is malformed. Also emits
        // an IN-CHANNEL MessageEvent so the LLM stops re-questioning.
        suspend fun closePendingWithError(reason: String): ConfirmationResolution {
            logger.warn { "[AgentAdvanced] closing orphan pending '${pending.toolName}': $reason" }
            val errorText = "Cannot resolve confirmation: $reason"
            val resolved =
                ConfirmationResolvedEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    pendingEventId = pending.metadata.id,
                    confirmed = false,
                    resultText = errorText,
                )
            emitEvent(resolved)
            appendTo += resolved
            val errorMessage =
                MessageEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    actor = Actor(id.toString(), name, ActorRole.AGENT),
                    content = listOf(MessageContent.Text(errorText)),
                )
            emitEvent(errorMessage)
            appendTo += errorMessage
            emitEvent(
                WarnEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    message = "Cannot resolve pending confirmation: $reason",
                ),
            )
            // Orphan-closed: the pending is broken (tool missing / DI misconfigured), no
            // natural conversational follow-up to invite — caller closes the turn cleanly.
            return ConfirmationResolution.Aborted
        }

        val tool =
            context.tools.firstOrNull { it.name == pending.toolName }
                ?: return closePendingWithError("tool '${pending.toolName}' not available in this agent.")

        // CRITICAL (AC7): only events strictly AFTER the pending count as a reply.
        // Use index comparison (deterministic regardless of timestamp ordering).
        val pendingIndex = events.indexOfFirst { it.metadata.id == pending.metadata.id }
        val eventsAfterPending = if (pendingIndex >= 0) events.subList(pendingIndex + 1, events.size) else emptyList()

        // Free-form user reply via LLM analysis — no typed button click anymore.
        val confirmationManager =
            context.confirmationManager
                ?: return closePendingWithError("ConfirmationManager not available.")
        val freeFormText =
            eventsAfterPending
                .filterIsInstance<MessageEvent>()
                .lastOrNull { it.actor.role == ActorRole.USER }
                ?.content
                ?.filterIsInstance<MessageContent.Text>()
                ?.joinToString(" ") { it.content }
        if (freeFormText.isNullOrBlank()) {
            // AC7: reload session without user reply. No reply yet.
            return ConfirmationResolution.Unresolved
        }

        // Slice the history shown to analyzeConfirmation to events at-or-after the pending.
        // Prevents the LLM judge from picking up a "yes" from an unrelated earlier turn
        // (defense-in-depth for tools with bypassImplicitConsent=true).
        val historyFromPending = context.buildMessages(events.subList(pendingIndex, events.size))
        val decision =
            confirmationManager.analyzeConfirmation(
                chatClient = context.chatClient,
                history = historyFromPending,
                pendingPayload = pending.inputJson,
                specificInstructions = pending.analysisInstructions,
            )
        if (decision == ConfirmationDecision.AMBIGUOUS) {
            // LLM-generated re-ask in the conversation's language, IN-CHANNEL — same
            // mechanism as the initial confirmation prompt. Pending stays open so the
            // next user reply re-runs analyzeConfirmation against this new clarification.
            val clarificationQuestion =
                confirmationManager.formulateQuestion(
                    chatClient = context.chatClient,
                    history = historyFromPending,
                    fallbackLabel = pending.toolName,
                    pendingData = pending.inputJson,
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
            return ConfirmationResolution.Unresolved
        }
        val confirmed = decision == ConfirmationDecision.CONFIRMED

        // Guard before destructive call — mitigate cancel mid-execution.
        if (!shouldContinue()) return ConfirmationResolution.Unresolved

        val toolCtx = buildToolContext(pending.toolName, namespaceId)
        var executionFailed = false
        var toolResult: ToolExecutionResult? = null
        val resultText: String =
            try {
                if (confirmed) {
                    val r = tool.executeWithJson(pending.inputJson, toolCtx)
                    toolResult = r
                    r.output
                } else {
                    tool.onRejected()
                }
            } catch (e: Exception) {
                logger.warn(e) { "[AgentAdvanced] tool execution failed during confirmation resolution for ${pending.toolName}" }
                executionFailed = true
                "Error during tool execution: ${e.message}"
            }

        // Authoritative outcome: the action took effect only if the user confirmed, the
        // tool did not throw AND the tool itself reported success. ConfirmationResolvedEvent.confirmed
        // and the synthetic ToolResponseEvent.success both reflect that combined state.
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
        val finalPromptText = "Based on the above conversation and your analysis, provide your response to the user."
        val intentionContext = lastIntention?.let { "Your analysis: ${it.intention}\n\n$finalPromptText" } ?: finalPromptText
        val messages = context.buildMessages(accumulatedEvents) + UserMessage(intentionContext)

        logger.debug { "[$name] generateFinalResponse — sending ${messages.size} messages" }
        logger.trace { "[$name] generateFinalResponse intentionContext:\n$intentionContext" }

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

        // Take last N raw ToolResponseEvent (success only) — preserves the original
        // "consecutive" semantics. If the window contains any synthetic (= confirmation
        // resolution), do not signal repetition — those are user-validated, not auto.
        val window =
            events
                .filterIsInstance<ToolResponseEvent>()
                .filter { it.success }
                .takeLast(REPETITION_DETECTION_WINDOW)

        if (window.size != REPETITION_DETECTION_WINDOW) return null
        if (window.any { it.toolRequestId in syntheticToolRequestIds }) return null

        return window.map { it.toolName }.toSet().singleOrNull()
    }

    private fun generateParameters(
        accumulatedEvents: List<CaseEvent>,
        intentionEvent: IntentionGeneratedEvent,
        namespaceId: UUID,
        caseId: UUID,
        toolRequestId: String,
    ): ToolRequestEvent {
        val tool =
            context.tools.firstOrNull { it.name == intentionEvent.toolName }
                ?: throw ToolNotFoundException(intentionEvent.toolName)

        val parametersPrompt =
            """
Generate the parameters for the tool call below.
Tool: ${tool.name}
Description: ${tool.description}
Input Schema: 
```
${tool.inputSchema}
```

Intention: ${intentionEvent.intention}

**Generate ONLY the JSON object matching the input schema above. No explanation, no markdown fences.**
            """.trimIndent()
        val accumulatedEventsWithoutCurrentToolCall = accumulatedEvents.dropLast(1)
        val messages = context.buildMessages(accumulatedEventsWithoutCurrentToolCall) + UserMessage(parametersPrompt)

        logger.debug { "[$name] generateParameters for '${tool.name}' — sending ${messages.size} messages" }
        logger.trace { "[$name] generateParameters prompt:\n$parametersPrompt" }

        val rawParameters =
            context.chatClient
                .prompt(Prompt(messages))
                .call()
                .content()
                ?.trim() ?: "{}"
        val parameters = stripJsonFence(rawParameters)

        logger.trace { "[$name] generateParameters raw response for '${tool.name}': $parameters" }

        return ToolRequestEvent(
            namespaceId = namespaceId,
            caseId = caseId,
            toolRequestId = toolRequestId,
            toolName = tool.name,
            args = parameters,
        )
    }

    private fun stripJsonFence(raw: String): String = JSON_FENCE_REGEX.matchEntire(raw)?.groupValues?.get(1)?.trim() ?: raw

    private suspend fun executeTool(
        toolRequest: ToolRequestEvent,
        namespaceId: UUID,
        caseId: UUID,
    ): ToolResponseEvent {
        val tool =
            context.tools.firstOrNull { it.name == toolRequest.toolName }
                ?: return ToolResponseEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    toolRequestId = toolRequest.toolRequestId,
                    toolName = toolRequest.toolName,
                    output = MessageContent.Text("Tool not found: ${toolRequest.toolName}"),
                    success = false,
                )

        val startMs = System.currentTimeMillis()
        return try {
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

    companion object : KLogging() {
        internal const val REPETITION_DETECTION_WINDOW = 3

        private val JSON_FENCE_REGEX =
            Regex(
                """^```(?:json)?\s*(.*?)\s*```$""",
                setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
            )
    }
}
