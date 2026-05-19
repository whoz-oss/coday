package io.whozoss.agentos.agent

import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.agent.Agent
import io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent
import io.whozoss.agentos.sdk.caseEvent.AgentRunningEvent
import io.whozoss.agentos.sdk.caseEvent.AnswerEvent
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.ConfirmationResolvedEvent
import io.whozoss.agentos.sdk.caseEvent.IntentionGeneratedEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.PendingConfirmationEvent
import io.whozoss.agentos.sdk.caseEvent.QuestionEvent
import io.whozoss.agentos.sdk.caseEvent.TextChunkEvent
import io.whozoss.agentos.sdk.caseEvent.ThinkingEvent
import io.whozoss.agentos.sdk.caseEvent.ToolRequestEvent
import io.whozoss.agentos.sdk.caseEvent.ToolResponseEvent
import io.whozoss.agentos.sdk.caseEvent.WarnEvent
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.reactive.asFlow
import mu.KLogging
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt
import java.util.UUID

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
                val resolved = handleConfirmationResolution(
                    events = accumulatedEvents,
                    pending = unresolvedPending,
                    namespaceId = namespaceId,
                    caseId = caseId,
                    shouldContinue = shouldContinue,
                    appendTo = accumulatedEvents,
                    emitEvent = { event -> emit(event) },
                )
                if (!resolved) {
                    // Either no reply yet (reload session) or ambiguous (WarnEvent emitted).
                    // Close the turn — user must reply (again) for the pending to be resolved.
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
                // Resolved (confirmed or rejected): fall through to the normal intention loop.
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

                    if (intention.toolName == AgentIntentionGenerator.ANSWER_TOOL) {
                        continueLoop = false
                    } else {
                        val outcome =
                            handleToolExecution(
                                accumulatedEvents,
                                intention,
                                namespaceId,
                                caseId,
                                shouldContinue,
                            ) { event -> emit(event) }
                        if (outcome == ToolFlowOutcome.AwaitingConfirmation) {
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

    private suspend fun handleToolExecution(
        accumulatedEvents: MutableList<CaseEvent>,
        intention: IntentionGeneratedEvent,
        namespaceId: UUID,
        caseId: UUID,
        shouldContinue: () -> Boolean,
        emitEvent: suspend (CaseEvent) -> Unit,
    ): ToolFlowOutcome {
        if (!shouldContinue()) return ToolFlowOutcome.Completed
        val toolRequestId = UUID.randomUUID().toString()
        val parameters = generateParameters(accumulatedEvents, intention, namespaceId, caseId, toolRequestId)
        emitEvent(parameters)
        accumulatedEvents.add(parameters)
        if (!shouldContinue()) return ToolFlowOutcome.Completed

        val tool = context.tools.firstOrNull { it.name == intention.toolName }

        // WZ-31596 pre-flight: tools that opt-in route through the confirmation flow.
        if (tool != null && tool.supportsConfirmation) {
            val outcome =
                try {
                    maybeRunConfirmationFlow(tool, parameters.args, namespaceId, caseId, accumulatedEvents)
                } catch (e: IllegalStateException) {
                    // Config misuse (no ConfirmationManager injected). Fail loudly — do not swallow.
                    throw e
                } catch (e: Exception) {
                    // Validation failure (e.g. "Cannot remove directories").
                    // Surface as a tool error so the LLM can retry with corrected input.
                    val errorMsg = "Error: ${e.message}"
                    val errResponse =
                        ToolResponseEvent(
                            namespaceId = namespaceId,
                            caseId = caseId,
                            toolRequestId = toolRequestId,
                            toolName = parameters.toolName,
                            output = MessageContent.Text(errorMsg),
                            success = false,
                        )
                    emitEvent(errResponse)
                    accumulatedEvents.add(errResponse)
                    return ToolFlowOutcome.Completed
                }
            when (outcome) {
                is ConfirmationOutcome.Skip -> Unit
                is ConfirmationOutcome.ExecutedDirectly -> {
                    val response =
                        ToolResponseEvent(
                            namespaceId = namespaceId,
                            caseId = caseId,
                            toolRequestId = toolRequestId,
                            toolName = parameters.toolName,
                            output = MessageContent.Text(outcome.result),
                            success = true,
                        )
                    emitEvent(response)
                    accumulatedEvents.add(response)
                    return ToolFlowOutcome.Completed
                }
                is ConfirmationOutcome.Await -> {
                    emitEvent(
                        PendingConfirmationEvent(
                            namespaceId = namespaceId,
                            caseId = caseId,
                            toolRequestId = toolRequestId,
                            toolName = parameters.toolName,
                            pendingPayloadJson = outcome.payloadJson,
                            confirmationLabel = outcome.label,
                            analysisInstructions = outcome.instructions,
                            questionId = outcome.questionId,
                        ),
                    )
                    emitEvent(
                        QuestionEvent(
                            metadata = EntityMetadata(id = outcome.questionId),
                            namespaceId = namespaceId,
                            caseId = caseId,
                            agentId = id,
                            agentName = name,
                            question = outcome.question,
                            options = CONFIRMATION_OPTIONS,
                        ),
                    )
                    // IN-CHANNEL: emit a MessageEvent so the LLM sees the pause naturally
                    // in the conversation context (visible via convertEventsToMessages).
                    emitEvent(
                        MessageEvent(
                            namespaceId = namespaceId,
                            caseId = caseId,
                            actor = Actor(id.toString(), name, ActorRole.AGENT),
                            content = listOf(MessageContent.Text(outcome.question)),
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
                    return ToolFlowOutcome.AwaitingConfirmation
                }
            }
        }

        // Standard path — no confirmation needed or tool doesn't opt-in.
        val response = executeTool(parameters, namespaceId, caseId)
        emitEvent(response)
        accumulatedEvents.add(response)
        return ToolFlowOutcome.Completed
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

    private suspend fun handleConfirmationResolution(
        events: List<CaseEvent>,
        pending: PendingConfirmationEvent,
        namespaceId: UUID,
        caseId: UUID,
        shouldContinue: () -> Boolean,
        appendTo: MutableList<CaseEvent>,
        emitEvent: suspend (CaseEvent) -> Unit,
    ): Boolean {
        // Helper (F6 + v3-F3): close orphan pending durably with an error result so it is
        // not re-detected on every subsequent run when the tool is missing / payload is
        // corrupted. Also emits an IN-CHANNEL MessageEvent so the LLM stops re-questioning.
        suspend fun closePendingWithError(reason: String): Boolean {
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
            return true
        }

        val mapper =
            context.objectMapper
                ?: return closePendingWithError("ObjectMapper not available.")
        val tool =
            context.tools.firstOrNull { it.name == pending.toolName }
                ?: return closePendingWithError("tool '${pending.toolName}' not available in this agent.")
        val payload =
            try {
                mapper.readValue(pending.pendingPayloadJson, Map::class.java)
            } catch (e: Exception) {
                logger.warn(e) { "[AgentAdvanced] failed to deserialize pending payload for ${pending.toolName}" }
                return closePendingWithError("payload deserialization failed (${e.message}).")
            }

        // CRITICAL (F2/AC7): only events strictly AFTER the pending count as a reply.
        // Use index comparison (deterministic regardless of timestamp ordering).
        val pendingIndex = events.indexOfFirst { it.metadata.id == pending.metadata.id }
        val eventsAfterPending = if (pendingIndex >= 0) events.subList(pendingIndex + 1, events.size) else emptyList()

        val toolCtx =
            ToolContext(
                namespaceId = namespaceId,
                userId = userId,
                userExternalId = userExternalId,
                caseEvents = filterEventsByIntegration(pending.toolName, caseEventsProvider()),
            )

        // Path 1: AnswerEvent linked to the pending's questionId (button click).
        val answerEvent =
            pending.questionId?.let { qid ->
                eventsAfterPending.filterIsInstance<AnswerEvent>().firstOrNull { it.questionId == qid }
            }
        // F10 fix: split AnswerEvent.answer into three buckets (Confirm / Reject / Other → defer to LLM judge).
        // Avoids silently rejecting unexpected answer values (typo, label mismatch, custom UI).
        val answerDecision: Pair<Boolean, String>? =
            answerEvent?.let { evt ->
                val ans = evt.answer.trim()
                when {
                    ans.equals(CONFIRMATION_ANSWER_CONFIRM, ignoreCase = true) -> true to evt.answer
                    ans.equals(CONFIRMATION_ANSWER_REJECT, ignoreCase = true) -> false to evt.answer
                    else -> null
                }
            }

        val confirmed: Boolean
        val replyText: String
        if (answerDecision != null) {
            confirmed = answerDecision.first
            replyText = answerDecision.second
        } else {
            // Path 2: free-form user MessageEvent + LLM lenient matching.
            // Also entered when AnswerEvent.answer is unrecognized (F10).
            val manager =
                context.confirmationManager
                    ?: return closePendingWithError("ConfirmationManager not available for free-form reply.")
            val freeFormText =
                answerEvent?.answer
                    ?: eventsAfterPending
                        .filterIsInstance<MessageEvent>()
                        .lastOrNull { it.actor.role == ActorRole.USER }
                        ?.content
                        ?.filterIsInstance<MessageContent.Text>()
                        ?.joinToString(" ") { it.content }
            if (freeFormText.isNullOrBlank()) {
                // AC7: reload session without user reply. No reply yet.
                return false
            }
            // NOTE (F8): history is full conversation; the LLM judge could read pre-pending context.
            // Mitigation lives in tool.getConfirmationAnalysisInstructions().
            val history = context.buildMessages(events)
            try {
                confirmed =
                    manager.analyzeConfirmation(
                        chatClient = context.chatClient,
                        history = history,
                        pendingPayload = payload,
                        specificInstructions = pending.analysisInstructions,
                    )
                replyText = freeFormText
            } catch (e: AmbiguousConfirmationException) {
                emitEvent(
                    WarnEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        message = "Could not interpret your reply. Please confirm or reject explicitly.",
                    ),
                )
                return false
            }
        }

        // Guard before destructive call — mitigate cancel mid-execution.
        if (!shouldContinue()) return false

        val resultText: String =
            try {
                if (confirmed) {
                    @Suppress("UNCHECKED_CAST")
                    (tool as StandardTool<Any?>).executeWithConfirmation(payload, toolCtx)
                } else {
                    @Suppress("UNCHECKED_CAST")
                    (tool as StandardTool<Any?>).onRejected(payload, replyText, toolCtx)
                }
            } catch (e: Exception) {
                logger.warn(e) { "[AgentAdvanced] tool execution failed during confirmation resolution for ${pending.toolName}" }
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
        emitEvent(resolved)
        appendTo += resolved

        // F2 fix: prefix decision explicitly so the LLM disambiguates the resolution
        // without needing a dedicated user MessageEvent (button clicks emit none).
        val decisionPrefix = if (confirmed) "User confirmed. " else "User declined. "
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
        // intention turn sees a coherent lastToolResponse. v3-F2: success reflects the
        // user's decision (true=executed, false=rejected/cancelled).
        val syntheticResponse =
            ToolResponseEvent(
                namespaceId = namespaceId,
                caseId = caseId,
                toolRequestId = pending.toolRequestId,
                toolName = pending.toolName,
                output = MessageContent.Text(resultText),
                success = confirmed,
            )
        emitEvent(syntheticResponse)
        appendTo += syntheticResponse
        return true
    }

    private fun maybeRunConfirmationFlow(
        tool: StandardTool<*>,
        toolInput: String?,
        namespaceId: UUID,
        caseId: UUID,
        accumulatedEvents: List<CaseEvent>,
    ): ConfirmationOutcome {
        val confirmationManager = context.confirmationManager
        val mapper = context.objectMapper
        if (confirmationManager == null || mapper == null) {
            throw IllegalStateException(
                "Tool '${tool.name}' requires confirmation but no ConfirmationManager / ObjectMapper configured for AgentAdvanced.",
            )
        }
        @Suppress("UNCHECKED_CAST")
        val typed = tool as StandardTool<Any?>
        val parsedInput = typed.parseInput(toolInput)
        val toolCtx =
            ToolContext(
                namespaceId = namespaceId,
                userId = userId,
                userExternalId = userExternalId,
                caseEvents = filterEventsByIntegration(tool.name, caseEventsProvider()),
            )
        if (!typed.requiresConfirmation(parsedInput, toolCtx)) return ConfirmationOutcome.Skip

        val payload = typed.getConfirmationPayload(parsedInput, toolCtx)
        val history = context.buildMessages(accumulatedEvents)
        val needsExplicit =
            tool.bypassImplicitConsent ||
                confirmationManager.shouldConfirm(
                    chatClient = context.chatClient,
                    history = history,
                    actionLabel = "Tool ${tool.name}",
                    proposedData = payload,
                )
        if (!needsExplicit) {
            return ConfirmationOutcome.ExecutedDirectly(typed.executeWithConfirmation(payload, toolCtx))
        }
        val payloadJson = mapper.writeValueAsString(payload)
        val sanitizedLabel = sanitizeForLlm(typed.confirmationLabel(payload))
        val question =
            confirmationManager.formulateQuestion(
                chatClient = context.chatClient,
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

    private fun sanitizeForLlm(s: String): String = s.filter { it.isLetterOrDigit() || it in " _-./" }.take(200)

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
        val content = contentBuilder.toString()
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
        events: List<CaseEvent>,
        intentionEvent: IntentionGeneratedEvent,
        namespaceId: UUID,
        caseId: UUID,
        toolRequestId: String,
    ): ToolRequestEvent {
        val messages = context.buildMessages(events)
        val tool =
            context.tools.firstOrNull { it.name == intentionEvent.toolName }
                ?: throw IllegalStateException("Tool not found: ${intentionEvent.toolName}")

        val parametersPrompt =
            """
Tool: ${tool.name}
Description: ${tool.description}
Input Schema: ${tool.inputSchema}

Intention: ${intentionEvent.intention}

Generate ONLY the JSON object matching the input schema above. No explanation, no markdown fences.
            """.trimIndent()

        val parameters =
            context.chatClient
                .prompt(Prompt(messages + UserMessage(parametersPrompt)))
                .call()
                .content()
                ?.trim() ?: "{}"

        return ToolRequestEvent(
            namespaceId = namespaceId,
            caseId = caseId,
            toolRequestId = toolRequestId,
            toolName = tool.name,
            args = parameters,
        )
    }

    private fun executeTool(
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
            val result =
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
                output = MessageContent.Text(result),
                success = true,
                durationMs = System.currentTimeMillis() - startMs,
            )
        } catch (e: AgentInterrupt) {
            // Re-throw so the run() catch block can handle it — do not swallow as a tool error.
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

    private enum class ToolFlowOutcome { Completed, AwaitingConfirmation }

    private sealed class ConfirmationOutcome {
        object Skip : ConfirmationOutcome()

        data class ExecutedDirectly(val result: String) : ConfirmationOutcome()

        data class Await(
            val payloadJson: String,
            val label: String,
            val question: String,
            val instructions: String,
            val questionId: UUID,
        ) : ConfirmationOutcome()
    }

    companion object : KLogging() {
        internal const val REPETITION_DETECTION_WINDOW = 3
    }
}
