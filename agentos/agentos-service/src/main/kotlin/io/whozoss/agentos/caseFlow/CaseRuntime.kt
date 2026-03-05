package io.whozoss.agentos.caseFlow

import io.whozoss.agentos.caseEvent.DefaultCaseEventEmitter
import io.whozoss.agentos.caseEvent.InMemoryCaseEventList
import io.whozoss.agentos.orchestration.CaseEventEmitter
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent
import io.whozoss.agentos.sdk.caseEvent.AgentRunningEvent
import io.whozoss.agentos.sdk.caseEvent.AgentSelectedEvent
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.QuestionEvent
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import mu.KLogging
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runtime execution engine for a case.
 *
 * Owns only execution state: the event list, the SSE flow, and the stop/kill flags.
 * All business logic is delegated back to [CaseService] through four callbacks:
 *
 * @param updateStatus called whenever the runtime transitions to a new [CaseStatus].
 * @param storeEvent called for every event produced by this runtime.
 *   The service persists the event and returns the saved copy (with stable id).
 *   The runtime then adds it to its list and emits it on the SSE flow itself —
 *   no reference to the runtime is ever passed outward.
 * @param selectAgent resolves which agent should handle the current message.
 *   Receives the message content and returns an ordered list of events to store+emit
 *   (e.g. an optional [io.whozoss.agentos.sdk.caseEvent.WarnEvent] followed by an
 *   [AgentSelectedEvent], or just an [AgentSelectedEvent] for the default agent).
 *   Returns an empty list when no agent is configured and the loop should stop.
 * @param runAgent fetches the named agent, runs it against the current event history,
 *   and pipes each produced event through [storeEvent]. Error handling is the
 *   responsibility of the service implementation.
 */
class CaseRuntime(
    val id: UUID,
    val projectId: UUID,
    private val updateStatus: (UUID, CaseStatus) -> Unit,
    private val storeEvent: (CaseEvent) -> CaseEvent,
    private val selectAgent: (content: List<MessageContent>) -> List<CaseEvent>,
    private val runAgent: suspend (agentName: String, events: List<CaseEvent>) -> Unit,
    inputEvents: List<CaseEvent> = emptyList(),
) : CaseEventEmitter by DefaultCaseEventEmitter() {
    private val eventList = InMemoryCaseEventList(inputEvents)

    private val stopRequested = AtomicBoolean(false)
    private val killRequested = AtomicBoolean(false)

    /**
     * True from the moment [CaseService] launches a [run] coroutine until [run] returns.
     * Set *before* the coroutine is launched (in [CaseService.addMessage]) via [markLaunched],
     * so the guard is effective even if the scheduler hasn't started the coroutine yet.
     */
    private val runLaunched = AtomicBoolean(false)

    private val maxIterations = 100
    private var iterationCount = 0

    // -------------------------------------------------------------------------
    // Public state
    // -------------------------------------------------------------------------

    fun requestStop() {
        stopRequested.set(true)
    }

    fun requestKill() {
        killRequested.set(true)
    }

    /**
     * Atomically claim the run slot.
     * Returns true if this call successfully claimed it (caller should launch [run]).
     * Returns false if a run is already in-flight (caller should skip launching).
     */
    fun markLaunched(): Boolean = runLaunched.compareAndSet(false, true)

    fun pushEvents(events: Collection<CaseEvent>) {
        events.forEach { eventList.add(it) }
    }

    // -------------------------------------------------------------------------
    // Event emission — internal only
    // -------------------------------------------------------------------------

    /**
     * Persist an event via the service callback, then add it to the local list and
     * emit it on the SSE flow. The runtime never passes itself to the service.
     */
    private fun storeAndEmitEvent(event: CaseEvent) {
        val saved = storeEvent(event)
        eventList.add(saved)
        emit(saved)
    }

    /**
     * Emit an event that belongs to a different (sub-)case on this runtime's SSE flow
     * without persisting or adding it to the local event list.
     */
    fun emitEventFromOtherCase(event: CaseEvent) {
        emit(event)
    }

    // -------------------------------------------------------------------------
    // User message entry point
    // -------------------------------------------------------------------------

    /**
     * Store a user message and emit the agent-selection events.
     * Does NOT start the execution loop — the caller ([CaseService]) is responsible
     * for launching [run] in a background coroutine.
     */
    fun addUserMessage(
        actor: Actor,
        content: List<MessageContent>,
        answerToEventId: UUID? = null,
    ) {
        logger.info {
            "[CaseRuntime $id] addUserMessage - actor: ${actor.displayName}, " +
                "content: ${content.size} part(s), answerTo: $answerToEventId"
        }

        if (answerToEventId != null) {
            val questionEvent = eventList.getById(answerToEventId)
            when {
                questionEvent == null -> {
                    logger.warn { "[CaseRuntime $id] Question event $answerToEventId not found, treating as regular message" }
                }

                questionEvent !is QuestionEvent -> {
                    logger.warn {
                        "[CaseRuntime $id] Event $answerToEventId is not a QuestionEvent " +
                            "(${questionEvent::class.simpleName}), treating as regular message"
                    }
                }

                else -> {
                    val answerText =
                        content.filterIsInstance<MessageContent.Text>().joinToString(" ") { it.content }
                    if (answerText.isBlank()) {
                        logger.warn { "[CaseRuntime $id] Answer text is blank for question $answerToEventId" }
                    } else {
                        storeAndEmitEvent(questionEvent.createAnswer(actor, answerText))
                        logger.info { "[CaseRuntime $id] Answer added for question: ${questionEvent.question}" }
                        return // answer is passive — waits for agent to process it
                    }
                }
            }
        }

        storeAndEmitEvent(MessageEvent(caseId = id, projectId = projectId, actor = actor, content = content))
        selectAgent(content).forEach { storeAndEmitEvent(it) }
    }

    // -------------------------------------------------------------------------
    // Main execution loop — must be called by the service in a background coroutine
    // -------------------------------------------------------------------------

    suspend fun run() {
        logger.info { "[CaseRuntime $id] run() called" }

        stopRequested.set(false)
        killRequested.set(false)
        updateStatus(id, CaseStatus.RUNNING)
        iterationCount = 0

        try {
            while (!stopRequested.get() && !killRequested.get() && iterationCount < maxIterations) {
                logger.debug { "[CaseRuntime $id] Processing iteration $iterationCount" }
                processNextStep()
                iterationCount++
            }
            logger.info {
                "[CaseRuntime $id] Exited loop - iterations: $iterationCount, " +
                    "stop: ${stopRequested.get()}, kill: ${killRequested.get()}"
            }
            if (iterationCount >= maxIterations) {
                logger.error { "[CaseRuntime $id] Maximum iterations ($maxIterations) reached" }
                updateStatus(id, CaseStatus.ERROR)
            } else {
                updateStatus(id, CaseStatus.STOPPED)
            }
        } catch (e: Exception) {
            logger.error(e) { "[CaseRuntime $id] Error during execution" }
            updateStatus(id, CaseStatus.ERROR)
        } finally {
            runLaunched.set(false)
        }
    }

    private suspend fun processNextStep() {
        val events = eventList.getAll()
        logger.debug { "[CaseRuntime $id] processNextStep - total events: ${events.size}" }

        val lastUserMessageIndex = events.indexOfLast { it is MessageEvent && it.actor.role == ActorRole.USER }

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
                    runAgent(event.agentName, eventList.getAll())
                    return
                }

                is AgentSelectedEvent -> {
                    logger.info {
                        "[CaseRuntime $id] Found AgentSelectedEvent for agent: ${event.agentName}, " +
                            "transitioning to running"
                    }
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

        // No relevant event found in history — this should not happen in normal flow
        // (addUserMessage always stores an AgentSelectedEvent before run() is called).
        // Treat it as a configuration error and stop.
        logger.error { "[CaseRuntime $id] No agent selection found in history, stopping" }
        stopRequested.set(true)
    }

    companion object : KLogging()
}
