package io.whozoss.agentos.caseFlow

import io.whozoss.agentos.agent.AgentService
import io.whozoss.agentos.caseEvent.DefaultCaseEventEmitter
import io.whozoss.agentos.caseEvent.InMemoryCaseEventList
import io.whozoss.agentos.orchestration.CaseEventEmitter
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.agent.Agent
import io.whozoss.agentos.sdk.caseEvent.*
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import kotlinx.coroutines.flow.catch
import mu.KLogging
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runtime execution engine for a case.
 *
 * Runs independently in its own coroutine (managed by [CaseService]) and continues
 * execution even if no collectors are attached to the output events (hot observable).
 *
 * All business logic (persistence, status tracking) is delegated back to the service
 * through two callbacks:
 *
 * @param updateStatus called whenever the runtime transitions to a new [CaseStatus].
 *   The service is responsible for persisting the change and emitting a [CaseStatusEvent].
 * @param emitAndStoreEvent called for every event produced by this runtime.
 *   The service persists the event and returns the saved copy (with a stable id);
 *   the runtime then emits that copy on its hot [events] flow.
 */
class CaseRuntime(
    val id: UUID,
    val projectId: UUID,
    private val agentService: AgentService,
    private val updateStatus: (UUID, CaseStatus) -> Unit,
    private val emitAndStoreEvent: (CaseEvent, CaseRuntime) -> Unit,
    inputEvents: List<CaseEvent> = emptyList(),
) : CaseEventEmitter by DefaultCaseEventEmitter() {
    private val eventList = InMemoryCaseEventList(inputEvents)

    // Stop flag for graceful shutdown
    private val stopRequested = AtomicBoolean(false)

    // Kill flag for immediate termination
    private val killRequested = AtomicBoolean(false)

    // Maximum iterations to prevent infinite loops
    private val maxIterations = 100
    private var iterationCount = 0

    private fun storeAndEmitEvent(event: CaseEvent) {
        emitAndStoreEvent(event, this)
    }

    /**
     * Request the runtime to stop gracefully.
     * Preserves case state and allows clean completion of the current operation.
     */
    fun stop() {
        logger.info { "[CaseRuntime $id] Stop requested" }
        updateStatus(id, CaseStatus.STOPPING)
        stopRequested.set(true)
    }

    /**
     * Cleanup resources used by this runtime (agents, etc.).
     * Called when the case completes normally.
     */
    suspend fun cleanup() {
        logger.info { "[CaseRuntime $id] Cleaning up resources" }
        try {
            agentService.cleanup()
        } catch (e: Exception) {
            logger.error(e) { "[CaseRuntime $id] Error during cleanup" }
            // Don't throw - cleanup should be best-effort
        }
    }

    /**
     * Immediately terminate case execution and cleanup resources.
     * Unlike [stop], this method does not preserve state.
     */
    suspend fun kill() {
        logger.info { "[CaseRuntime $id] Kill requested" }
        killRequested.set(true)
        stop()
        try {
            agentService.kill()
            cleanup()
        } catch (e: Exception) {
            logger.error(e) { "[CaseRuntime $id] Error during kill" }
        }
    }

    fun pushEvents(events: Collection<CaseEvent>) {
        events.forEach { eventList.add(it) }
    }

    private fun updateStatus(newStatus: CaseStatus) {
        logger.info { "CaseRuntime $id status -> $newStatus" }
        updateStatus(id, newStatus)
    }

    suspend fun addUserMessage(
        actor: Actor,
        content: List<MessageContent>,
        answerToEventId: UUID? = null,
    ) {
        logger.info {
            "[CaseRuntime $id] addUserMessage called - actor: ${actor.displayName}, content size: ${content.size}, answerTo: $answerToEventId"
        }
        // If this is an answer to a question, create an AnswerEvent
        if (answerToEventId != null) {
            val questionEvent = eventList.getById(answerToEventId)

            if (questionEvent == null) {
                logger.warn { "[CaseRuntime $id] Question event $answerToEventId not found, treating as regular message" }
            } else if (questionEvent !is QuestionEvent) {
                logger.warn {
                    "[CaseRuntime $id] Event $answerToEventId is not a QuestionEvent (${questionEvent::class.simpleName}), treating as regular message"
                }
            } else {
                val answerText =
                    content
                        .filterIsInstance<MessageContent.Text>()
                        .joinToString(" ") { it.content }

                if (answerText.isBlank()) {
                    logger.warn { "[CaseRuntime $id] Answer text is blank for question $answerToEventId" }
                } else {
                    val answerEvent = questionEvent.createAnswer(actor, answerText)
                    storeAndEmitEvent(answerEvent)
                    logger.info { "[CaseRuntime $id] Answer added for question: ${questionEvent.question}" }
                    // Don't run() here — answer is passive, waits for agent to process it
                    return
                }
            }
        }

        // Regular message flow
        val userMessageEvent =
            MessageEvent(
                caseId = id,
                projectId = projectId,
                actor = actor,
                content = content,
            )
        storeAndEmitEvent(userMessageEvent)
        detectAgentSelection(content)
        logger.info { "[CaseRuntime $id] Starting run()" }
        run()
    }

    /**
     * Parse message content to detect agent mentions (@agentName) and handle them.
     * If an agent is mentioned and found, emits an [AgentSelectedEvent].
     * If an agent is mentioned but not found, emits a [WarnEvent].
     */
    private fun detectAgentSelection(content: List<MessageContent>) {
        val textContent = content.filterIsInstance<MessageContent.Text>()
        if (textContent.isEmpty()) return

        val firstText = textContent.first().content.trim()
        val mentionPattern = """^@(\S+)""".toRegex()
        val matchResult = mentionPattern.find(firstText) ?: return

        val agentName = matchResult.groupValues[1]
        logger.debug { "[CaseRuntime $id] Agent mention detected: @$agentName" }

        val resolvedName = agentService.resolveAgentName(agentName)
        if (resolvedName != null) {
            val agentId = UUID.nameUUIDFromBytes(resolvedName.toByteArray())
            val agentSelectedEvent =
                AgentSelectedEvent(
                    projectId = projectId,
                    caseId = id,
                    agentId = agentId,
                    agentName = resolvedName,
                )
            storeAndEmitEvent(agentSelectedEvent)
            logger.info { "[CaseRuntime $id] Agent selected: $resolvedName" }
        } else {
            val warnEvent =
                WarnEvent(
                    projectId = projectId,
                    caseId = id,
                    message = "Agent '$agentName' not found",
                )
            storeAndEmitEvent(warnEvent)
            logger.warn { "[CaseRuntime $id] Agent '@$agentName' not found" }
        }
    }

    /**
     * Main execution loop for the case.
     * Blocks until the case is resolved, stopped, or hits the iteration limit.
     * Should be called in a dedicated coroutine by [CaseService].
     */
    suspend fun run() {
        logger.info { "[CaseRuntime $id] run() called" }

        if (isRunning()) {
            logger.warn { "[CaseRuntime $id] Already running, skipping run()" }
            return
        }

        stopRequested.set(false)
        killRequested.set(false)

        updateStatus(CaseStatus.RUNNING)
        iterationCount = 0

        try {
            while (!stopRequested.get() && !killRequested.get() && iterationCount < maxIterations) {
                logger.debug { "[CaseRuntime $id] Processing iteration $iterationCount" }
                processNextStep()
                iterationCount++
            }
            logger.info {
                "[CaseRuntime $id] Exited main loop - iterations: $iterationCount, " +
                    "stopRequested: ${stopRequested.get()}, killRequested: ${killRequested.get()}"
            }

            if (iterationCount >= maxIterations) {
                logger.error { "[CaseRuntime $id] Maximum iterations ($maxIterations) reached" }
                updateStatus(CaseStatus.ERROR)
            } else {
                updateStatus(CaseStatus.STOPPED)
            }
        } catch (e: Exception) {
            logger.error(e) { "[CaseRuntime $id] Error during execution" }
            updateStatus(CaseStatus.ERROR)
        } finally {
            cleanup()
        }
    }

    private fun isRunning(): Boolean = !stopRequested.get() && !killRequested.get() && iterationCount > 0

    private suspend fun runAgent(agent: Agent) {
        logger.info { "[CaseRuntime $id] Running agent: ${agent.name}" }

        agent
            .run(eventList.getAll())
            .catch { error ->
                logger.error(error) { "[CaseRuntime $id] Error in agent ${agent.name}" }
                storeAndEmitEvent(
                    WarnEvent(
                        projectId = projectId,
                        caseId = id,
                        message = "Agent ${agent.name} error: ${error.message}",
                    ),
                )
            }.collect { event ->
                storeAndEmitEvent(event)
            }

        logger.info { "[CaseRuntime $id] Agent ${agent.name} finished" }
    }

    /**
     * Determines the next step based on the current event history:
     * - [AgentFinishedEvent] → stop the loop
     * - [AgentRunningEvent]  → retrieve the agent and run it
     * - [AgentSelectedEvent] → transition to running by emitting [AgentRunningEvent]
     * - nothing relevant     → select the default agent
     */
    private suspend fun processNextStep() {
        val events = eventList.getAll()
        logger.debug { "[CaseRuntime $id] processNextStep - total events: ${events.size}" }

        val lastUserMessageIndex = events.indexOfLast { it is MessageEvent && it.actor.role == ActorRole.USER }
        logger.debug { "[CaseRuntime $id] Last user message at index: $lastUserMessageIndex" }

        for (i in events.lastIndex downTo 0) {
            val event = events[i]

            if (event is AgentFinishedEvent && i < lastUserMessageIndex) {
                logger.debug { "[CaseRuntime $id] Skipping old AgentFinishedEvent at index $i" }
                continue
            }

            when (event) {
                is AgentFinishedEvent -> {
                    logger.info { "[CaseRuntime $id] Found AgentFinishedEvent, stopping" }
                    stopRequested.set(true)
                    return
                }

                is AgentRunningEvent -> {
                    logger.info { "[CaseRuntime $id] Found AgentRunningEvent for agent: ${event.agentName}" }
                    val agent = agentService.findAgentByName(event.agentName)
                    runAgent(agent)
                    return
                }

                is AgentSelectedEvent -> {
                    logger.info { "[CaseRuntime $id] Found AgentSelectedEvent for agent: ${event.agentName}, transitioning to running" }
                    storeAndEmitEvent(
                        AgentRunningEvent(
                            projectId = projectId,
                            caseId = id,
                            agentId = event.agentId,
                            agentName = event.agentName,
                        ),
                    )
                    return
                }

                else -> { // keep scanning
                }
            }
        }

        logger.debug { "[CaseRuntime $id] No relevant events found, selecting default agent" }
        selectDefaultAgent()
    }

    private fun selectDefaultAgent() {
        logger.info { "[CaseRuntime $id] Selecting default agent" }
        val defaultAgentName = agentService.getDefaultAgentName()
        if (defaultAgentName != null) {
            val agentId = UUID.nameUUIDFromBytes(defaultAgentName.toByteArray())
            logger.info { "[CaseRuntime $id] Default agent found: $defaultAgentName (id: $agentId)" }
            storeAndEmitEvent(
                AgentSelectedEvent(
                    projectId = projectId,
                    caseId = id,
                    agentId = agentId,
                    agentName = defaultAgentName,
                ),
            )
        } else {
            logger.error { "[CaseRuntime $id] No default agent configured, stopping" }
            stopRequested.set(true)
        }
    }

    fun emitEventFromThisCase(event: CaseEvent) {
        eventList.add(event)
        emit(event)
    }

    fun emitEventFromOtherCase(event: CaseEvent) {
        emit(event)
    }

    companion object : KLogging()
}
