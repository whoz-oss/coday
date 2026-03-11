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
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runtime execution engine for a case.
 *
 * Owns only execution state: the event list, the SSE flow, and the kill flag.
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

    /**
     * Set by [processNextStep] when it finds an [AgentFinishedEvent] for the current
     * turn (exits the loop, transitions to [CaseStatus.IDLE]), by [requestInterrupt]
     * (same exit path, also transitions to [CaseStatus.IDLE]), and by [requestKill]
     * (exits the loop, transitions to [CaseStatus.KILLED]).
     * [killRequested] distinguishes the kill path from the idle path.
     */
    private val interruptRequested = AtomicBoolean(false)

    /**
     * Set only by [requestKill]. Lets [run] distinguish a normal turn-end
     * (transition to [CaseStatus.IDLE], runtime stays alive) from an explicit kill
     * (transition to [CaseStatus.KILLED], service evicts the runtime).
     */
    private val killRequested = AtomicBoolean(false)

    /**
     * Guards against concurrent [run] invocations.
     * Claimed atomically at the top of [run] via [AtomicBoolean.compareAndSet];
     * released in the finally block. Callers launch [run] unconditionally —
     * the second invocation returns immediately without doing any work.
     */
    private val runInFlight = AtomicBoolean(false)

    private val maxIterations = 100
    private var iterationCount = 0

    // -------------------------------------------------------------------------
    // Public state
    // -------------------------------------------------------------------------

    /**
     * Interrupt the current agent turn and return to [CaseStatus.IDLE].
     *
     * Sets [stopRequested] so the while-loop exits after the current [processNextStep]
     * returns. [killRequested] is NOT set, so [run] transitions to [CaseStatus.IDLE]
     * rather than [CaseStatus.KILLED] — the runtime stays alive and the SSE flow
     * stays open for the next user message.
     *
     * Note: if [runAgent] is currently suspended mid-LLM-stream, the interrupt takes
     * effect only after that stream completes. True mid-stream cancellation would
     * require coroutine cancellation propagated into the agent flow.
     */
    fun requestInterrupt() {
        interruptRequested.set(true)
    }

    /**
     * Request permanent termination of this runtime.
     * Sets both [stopRequested] (to break the run loop) and [killRequested] (so
     * [run] transitions to [CaseStatus.KILLED] rather than [CaseStatus.IDLE]).
     */
    fun requestKill() {
        killRequested.set(true)
        interruptRequested.set(true)
    }

    fun isRunning(): Boolean = runInFlight.get()

    fun pushEvents(events: Collection<CaseEvent>) {
        events.forEach { eventList.add(it) }
    }

    // -------------------------------------------------------------------------
    // Event emission
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
    // Main execution loop
    // -------------------------------------------------------------------------

    /**
     * Run the execution loop for one agent turn.
     *
     * Self-guarding: if a run is already in-flight, returns immediately.
     * Callers do not need to check [isRunning] before launching — launching
     * unconditionally via a coroutine scope is the intended pattern.
     *
     * ## Lifecycle
     * - Normal turn-end ([AgentFinishedEvent] found): transitions to [CaseStatus.IDLE].
     *   The runtime stays in `activeRuntimes` and the SSE flow stays open, ready for
     *   the next user message.
     * - Kill requested ([requestKill] called): transitions to [CaseStatus.KILLED].
     *   Terminal — the service evicts the runtime.
     * - Max iterations reached: transitions to [CaseStatus.ERROR]. Terminal.
     */
    suspend fun run() {
        if (!runInFlight.compareAndSet(false, true)) {
            logger.debug { "[CaseRuntime $id] run() already in-flight, skipping" }
            return
        }

        logger.info { "[CaseRuntime $id] run() started" }
        interruptRequested.set(false)
        killRequested.set(false)
        updateStatus(id, CaseStatus.RUNNING)
        iterationCount = 0

        try {
            while (!interruptRequested.get() && iterationCount < maxIterations) {
                logger.debug { "[CaseRuntime $id] Processing iteration $iterationCount" }
                processNextStep()
                iterationCount++
            }
            logger.info {
                "[CaseRuntime $id] Exited loop - iterations: $iterationCount, " +
                    "interrupt: ${interruptRequested.get()}, kill: ${killRequested.get()}"
            }
            when {
                iterationCount >= maxIterations -> {
                    logger.error { "[CaseRuntime $id] Maximum iterations ($maxIterations) reached" }
                    updateStatus(id, CaseStatus.ERROR)
                }
                killRequested.get() -> {
                    // Explicit kill: permanent termination. Service will evict the runtime.
                    updateStatus(id, CaseStatus.KILLED)
                }
                else -> {
                    // Normal turn-end: agent finished, waiting for next user message.
                    // Non-terminal — runtime stays alive, SSE flow stays open.
                    updateStatus(id, CaseStatus.IDLE)
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "[CaseRuntime $id] Error during execution" }
            updateStatus(id, CaseStatus.ERROR)
        } finally {
            runInFlight.set(false)
        }
    }

    private suspend fun processNextStep() {
        val events = eventList.getAll()
        logger.debug { "[CaseRuntime $id] processNextStep - total events: ${events.size}" }

        val lastUserMessageIndex = events.indexOfLast { it is MessageEvent && it.actor.role == ActorRole.USER }

        for (i in events.lastIndex downTo 0) {
            val event = events[i]

            // Skip orchestration events that predate the most recent user message:
            // they belong to a previous turn and must not re-trigger execution.
            if (i < lastUserMessageIndex &&
                (event is AgentFinishedEvent || event is AgentRunningEvent || event is AgentSelectedEvent)
            ) {
                logger.debug { "[CaseRuntime $id] Skipping prior-turn ${event::class.simpleName} at index $i (lastUserMsg=$lastUserMessageIndex)" }
                continue
            }

            when (event) {
                is AgentFinishedEvent -> {
                    // Agent turn complete. Signal the while-loop to exit by setting
                    // interruptRequested. killRequested is intentionally NOT set here,
                    // so run() transitions to IDLE rather than KILLED.
                    logger.info { "[CaseRuntime $id] Agent finished turn, yielding until next user message" }
                    interruptRequested.set(true)
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
        interruptRequested.set(true)
    }

    companion object : KLogging()
}
