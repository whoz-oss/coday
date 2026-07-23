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
import io.whozoss.agentos.sdk.caseEvent.WarnEvent
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import mu.KLogging
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/** Holds a pending command to be executed sequentially after the current agent turn. */
private data class PendingCommand(val content: List<MessageContent>)

/**
 * Runtime execution engine for a case.
 *
 * Owns only execution state: the event list, the SSE flow, and the kill flag.
 * All business logic is delegated back to [CaseService] through four callbacks:
 *
 * @param updateStatusCallback called whenever the runtime transitions to a new [CaseStatus].
 * @param storeEvent called for every event produced by this runtime.
 *   The service persists the event and returns the saved copy (with stable id).
 *   The runtime then adds it to its list and emits it on the SSE flow itself —
 *   no reference to the runtime is ever passed outward.
 * @param selectAgent resolves which agent should handle the current message.
 *   Receives the message content and the full current event history, and returns an
 *   ordered list of events to store+emit (e.g. an optional
 *   [io.whozoss.agentos.sdk.caseEvent.WarnEvent] followed by an [AgentSelectedEvent],
 *   or just an [AgentSelectedEvent] for the default agent).
 *   Returns an empty list when no agent is configured and the loop should stop.
 * @param isAgentAuthorized defensive check called when [processNextStep] encounters an
 *   [AgentSelectedEvent] emitted by an agent (redirect). Returns true if the target agent
 *   is accessible to the current user. Called at redirect time — not pre-computed.
 * @param runAgent fetches the named agent, runs it against the current event history,
 *   and pipes each produced event through [storeEvent]. The implementation is responsible
 *   for deciding whether to emit [AgentRunningEvent] by inspecting the event history
 *   (e.g. skip if already the most recent orchestration event, to avoid duplicates on
 *   rehydration). Error handling is the responsibility of the service implementation.
 */
class CaseRuntime(
    val id: UUID,
    val namespaceId: UUID,
    /**
     * The case's immutable creation timestamp — used to resolve the date-sharded exchange root.
     * Required (no default) so every runtime resolves the same exchange shard as the controller
     * ([io.whozoss.agentos.exchange.ExchangeController.caseRootFor]); production supplies it via [buildRuntime].
     */
    val caseCreatedAt: Instant,
    private val updateStatusCallback: (UUID, CaseStatus) -> Unit,
    private val storeEvent: (CaseEvent) -> CaseEvent,
    private val selectAgent: (content: List<MessageContent>, pastEvents: List<CaseEvent>) -> List<CaseEvent>,
    private val isAgentAuthorized: (agentName: String, userId: UUID?) -> Boolean,
    private val runAgent: suspend (
        agentName: String,
        events: List<CaseEvent>,
        eventsProvider: () -> List<CaseEvent>,
        userId: UUID?,
        shouldContinue: () -> Boolean,
    ) -> Unit,
    inputEvents: List<CaseEvent> = emptyList(),
    initialStatus: CaseStatus = CaseStatus.PENDING,
    private val emitter: DefaultCaseEventEmitter = DefaultCaseEventEmitter(),
) : CaseEventEmitter by emitter {
    private val eventList = InMemoryCaseEventList(inputEvents)

    /**
     * Set by [processNextStep] when it finds an [AgentFinishedEvent] for the current
     * turn (exits the inner loop), by [requestInterrupt] (same exit, transitions to
     * [CaseStatus.IDLE]), and by [requestKill] (transitions to [CaseStatus.KILLED]).
     */
    private val interruptRequested = AtomicBoolean(false)

    /**
     * Set only by [requestKill]. Distinguishes a normal turn-end (→ IDLE)
     * from an explicit kill (→ KILLED).
     */
    private val killRequested = AtomicBoolean(false)

    /** What [processNextStep] signals back to the run loop. */
    private enum class StepResult { CONTINUE, AGENT_FINISHED, STOP }

    /**
     * Guards against concurrent [run] invocations.
     * Claimed atomically at the top of [run] via [AtomicBoolean.compareAndSet];
     * released in the finally block. Callers launch [run] unconditionally —
     * the second invocation returns immediately without doing any work.
     */
    private val runInFlight = AtomicBoolean(false)

    private val maxIterations = 100
    private var iterationCount = 0

    /**
     * Queue of commands to execute sequentially after each agent turn completes.
     * Commands are already fully resolved by [CaseServiceImpl] before being enqueued —
     * they are plain text, never slash-commands.
     */
    private val commandQueue = ConcurrentLinkedQueue<PendingCommand>()

    private val _statusFlow = MutableStateFlow(initialStatus)

    // -------------------------------------------------------------------------
    // Public state
    // -------------------------------------------------------------------------

    /**
     * Reactive view of the current [CaseStatus].
     * Updated synchronously inside [run] before and after each transition.
     * Initialised to [initialStatus] so rehydrated runtimes start with the
     * correct persisted status rather than always [CaseStatus.PENDING].
     * Consumers can combine this with [subscriptionCount] to react to the
     * conjunction of "case is idle" and "no SSE subscribers".
     */
    val statusFlow: StateFlow<CaseStatus> = _statusFlow.asStateFlow()

    /**
     * Interrupt the current agent turn, clear the command queue, and return to [CaseStatus.IDLE].
     *
     * Takes effect after the current [processNextStep] returns. The runtime stays alive
     * and the SSE flow stays open for the next user message.
     */
    fun requestInterrupt() {
        commandQueue.clear()
        interruptRequested.set(true)
    }

    /**
     * Request permanent termination of this runtime.
     * Sets both [stopRequested] (to break the run loop) and [killRequested] (so
     * [run] transitions to [CaseStatus.KILLED] rather than [CaseStatus.IDLE]).
     * Also clears the command queue so no orphaned commands are left behind.
     */
    fun requestKill() {
        killRequested.set(true)
        interruptRequested.set(true)
        commandQueue.clear()
    }

    /**
     * Enqueue a command for sequential execution after the current agent turn.
     * Used when a prompt resolves to multiple commands.
     */
    fun enqueueCommand(content: List<MessageContent>) {
        commandQueue.add(PendingCommand(content))
    }

    fun isRunning(): Boolean = runInFlight.get()

    /** Number of active SSE subscribers. Useful as a synchronisation barrier in tests. */
    val subscriptionCount get() = emitter.subscriptionCount

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
     * Emit an event on this runtime's SSE flow
     * without persisting or adding it to the local event list.
     */
    fun emitEvent(event: CaseEvent) {
        emit(event)
    }

    // -------------------------------------------------------------------------
    // User message entry point
    // -------------------------------------------------------------------------

    /**
     * Store a user message and emit the agent-selection events.
     * Does NOT start the execution loop — the caller ([CaseService]) is responsible
     * for launching [run] in a background coroutine.
     *
     * When [sessionContext] is non-null, it is embedded directly in the [MessageEvent]
     * as [io.whozoss.agentos.sdk.caseEvent.MessageEvent.sessionContext].
     */
    fun addUserMessage(
        actor: Actor,
        content: List<MessageContent>,
        answerToEventId: UUID? = null,
        sessionContext: Map<String, Any?>? = null,
    ) {
        logger.info {
            "[CaseRuntime $id] addUserMessage - actor: ${actor.id}, " +
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

        storeAndEmitEvent(
            MessageEvent(caseId = id, namespaceId = namespaceId, actor = actor, content = content, sessionContext = sessionContext),
        )
        selectAgent(content, eventList.getAll()).forEach { storeAndEmitEvent(it) }
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
        updateStatus(CaseStatus.RUNNING)
        iterationCount = 0

        try {
            val finalStatus = runTurns()
            logger.info { "[CaseRuntime $id] Exited loop → $finalStatus, iterations: $iterationCount" }
            if (finalStatus != CaseStatus.IDLE) commandQueue.clear()
            updateStatus(finalStatus)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "[CaseRuntime $id] Unexpected error during execution" }
            commandQueue.clear()
            updateStatus(CaseStatus.ERROR)
        } finally {
            runInFlight.set(false)
        }
    }

    /**
     * Runs agent turns until a terminal condition is reached.
     *
     * Each call to [processNextStep] returns a [StepResult]:
     * - [StepResult.CONTINUE]: agent is mid-turn, keep stepping.
     * - [StepResult.AGENT_FINISHED]: agent completed its turn; drain the command queue.
     * - [StepResult.STOP]: kill or unrecoverable error; exit immediately.
     *
     * @return the target [CaseStatus] to transition to.
     */
    private suspend fun runTurns(): CaseStatus {
        while (iterationCount < maxIterations) {
            if (interruptRequested.get()) return CaseStatus.IDLE
            logger.debug { "[CaseRuntime $id] Processing iteration $iterationCount" }
            when (processNextStep()) {
                StepResult.CONTINUE -> iterationCount++
                StepResult.STOP -> return CaseStatus.KILLED
                StepResult.AGENT_FINISHED -> {
                    if (interruptRequested.get()) return CaseStatus.IDLE
                    val nextCommand = commandQueue.poll()
                        ?: return CaseStatus.IDLE
                    logger.info {
                        "[CaseRuntime $id] Draining command queue, ${commandQueue.size} command(s) remaining"
                    }
                    val actor = resolveLastUserActor(eventList.getAll())
                        ?: return CaseStatus.ERROR
                    addUserMessage(actor, nextCommand.content)
                    iterationCount = 0
                }
            }
        }
        logger.error { "[CaseRuntime $id] Maximum iterations ($maxIterations) reached" }
        return CaseStatus.ERROR
    }

    private fun updateStatus(caseStatus: CaseStatus) {
        // _statusFlow is updated first so reactive watchers (e.g. eviction watcher)
        // see the new status immediately, before the persistence round-trip completes.
        _statusFlow.value = caseStatus
        updateStatusCallback(id, caseStatus)
    }

    /**
     * Inspects the event history and executes the next logical step.
     *
     * Scans backward from the most recent event, skipping orchestration events that
     * belong to prior turns. Returns:
     * - [StepResult.CONTINUE] after running the agent (more steps may follow in this turn).
     * - [StepResult.AGENT_FINISHED] when [AgentFinishedEvent] is found (turn is complete).
     * - [StepResult.STOP] when a kill is requested or no agent can be found.
     */
    private suspend fun processNextStep(): StepResult {
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
                logger.debug {
                    "[CaseRuntime $id] Skipping prior-turn ${event::class.simpleName} at index $i (lastUserMsg=$lastUserMessageIndex)"
                }
                continue
            }

            when (event) {
                is AgentFinishedEvent -> {
                    logger.info { "[CaseRuntime $id] Agent finished turn, yielding until next user message" }
                    return StepResult.AGENT_FINISHED
                }

                is AgentRunningEvent -> {
                    // Rehydration: agent was running when the case crashed.
                    logger.info { "[CaseRuntime $id] Rehydrating from AgentRunningEvent for agent: ${event.agentName}" }
                    runAgent(
                        event.agentName,
                        eventList.getAll(),
                        { eventList.getAll() },
                        resolveUserId(events),
                    ) { !killRequested.get() }
                    return StepResult.CONTINUE
                }

                is AgentSelectedEvent -> {
                    logger.info { "[CaseRuntime $id] Found AgentSelectedEvent for agent: ${event.agentName}" }
                    val userId = resolveUserId(events)
                    if (!isAgentAuthorized(event.agentName, userId)) {
                        logger.warn {
                            "[CaseRuntime $id] Agent '${event.agentName}' is not accessible " +
                                "to user $userId — redirect blocked"
                        }
                        storeAndEmitEvent(
                            WarnEvent(
                                namespaceId = namespaceId,
                                caseId = id,
                                message = "Agent '${event.agentName}' is not accessible to the current user",
                            ),
                        )
                        return StepResult.STOP
                    }
                    runAgent(
                        event.agentName,
                        eventList.getAll(),
                        { eventList.getAll() },
                        userId,
                    ) { !killRequested.get() }
                    return if (killRequested.get()) StepResult.STOP else StepResult.CONTINUE
                }

                else -> { // keep scanning
                }
            }
        }

        // No AgentSelectedEvent found after the last user message.
        // selectAgent stored only a WarnEvent — stop cleanly, return to IDLE.
        logger.warn { "[CaseRuntime $id] No agent selection found in history, stopping" }
        return StepResult.AGENT_FINISHED
    }

    /**
     * Scans the event history backward and returns the UUID of the last user actor,
     * or null if no user message is found or the actor id is not a valid UUID.
     */
    private fun resolveUserId(events: List<CaseEvent>): UUID? =
        resolveLastUserActor(events)
            ?.id
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    /**
     * Scans the event history backward and returns the last user [Actor],
     * or null if no user message is found.
     */
    private fun resolveLastUserActor(events: List<CaseEvent>): Actor? =
        events
            .filterIsInstance<MessageEvent>()
            .lastOrNull { it.actor.role == ActorRole.USER }
            ?.actor

    companion object : KLogging()
}
