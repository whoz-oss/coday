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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mu.KLogging
import java.util.UUID

/**
 * Runtime execution engine for a case.
 *
 * ## Coroutine model
 *
 * Each [CaseRuntime] owns a [caseScope] ([SupervisorJob] + [Dispatchers.IO]).
 * A single long-lived "case loop" coroutine runs inside that scope for the
 * lifetime of the runtime, waiting on [workChannel] between turns.
 *
 * When a turn starts, it is launched as a **child job** ([turnJob]) of [caseScope]:
 *
 * - **Interrupt** cancels only [turnJob]. The case loop catches [CancellationException],
 *   sees that [caseScope] is still active (so this was not a kill), transitions to
 *   [CaseStatus.IDLE], and resumes waiting for the next message.
 *
 * - **Kill** cancels [caseScope] itself. Every child job (including any in-flight
 *   [turnJob]) is cancelled. [onKilled] is invoked before the scope is cancelled so
 *   the service can persist [CaseStatus.KILLED] and evict the runtime while it is
 *   still reachable.
 *
 * ## shouldContinue
 *
 * The [shouldContinue] lambda passed to [runAgent] is derived purely from the
 * [turnJob]'s [Job.isActive] flag — no separate [AtomicBoolean] is needed.
 * When [turnJob] is cancelled (by either interrupt or kill), [Job.isActive] becomes
 * false inside the job, so agents stop cooperatively at the same time coroutine
 * cancellation propagates through suspend points.
 *
 * ## Callbacks
 *
 * All business logic is delegated back to [CaseService] through four callbacks:
 *
 * @param updateStatus called whenever the runtime transitions to a new [CaseStatus].
 * @param storeEvent called for every event produced by this runtime.
 * @param selectAgent resolves which agent should handle the current message.
 * @param runAgent fetches the named agent, runs it, and pipes events through [storeEvent].
 * @param onKilled called by [requestKill] before the scope is cancelled, so the service
 *   can persist [CaseStatus.KILLED] and evict the runtime while it is still reachable.
 */
class CaseRuntime(
    val id: UUID,
    val namespaceId: UUID,
    private val updateStatus: (UUID, CaseStatus) -> Unit,
    private val storeEvent: (CaseEvent) -> CaseEvent,
    private val selectAgent: (content: List<MessageContent>) -> List<CaseEvent>,
    private val runAgent: suspend (agentName: String, events: List<CaseEvent>, shouldContinue: () -> Boolean) -> Unit,
    private val onKilled: (UUID) -> Unit = {},
    inputEvents: List<CaseEvent> = emptyList(),
) : CaseEventEmitter by DefaultCaseEventEmitter() {

    private val eventList = InMemoryCaseEventList(inputEvents)

    // -------------------------------------------------------------------------
    // Coroutine infrastructure
    // -------------------------------------------------------------------------

    /**
     * Long-lived scope for this case. Cancelling it kills all work permanently.
     * Uses [SupervisorJob] so a failing [turnJob] does not cancel the case loop itself.
     */
    private val caseScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * The currently running agent-turn job, or null between turns.
     * Cancelling this job interrupts the current turn without killing the case scope.
     */
    @Volatile
    private var turnJob: Job? = null

    /**
     * Signals the case loop that new work is available.
     * [Channel.CONFLATED] means if the loop hasn't consumed the previous signal yet
     * the new one replaces it — no duplicate runs.
     */
    private val workChannel = Channel<Unit>(capacity = Channel.CONFLATED)

    private val maxIterations = 100

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Interrupt the current agent turn and return to [CaseStatus.IDLE].
     *
     * Cancels [turnJob] if one is running. The case loop catches [CancellationException],
     * checks that [caseScope] is still active (confirming this is an interrupt, not a kill),
     * transitions to IDLE, and resumes waiting. The runtime and SSE flow stay alive.
     */
    fun requestInterrupt() {
        logger.info { "[CaseRuntime $id] Interrupt requested" }
        turnJob?.cancel(CancellationException("interrupt"))
    }

    /**
     * Permanently terminate this runtime.
     *
     * [onKilled] is called first so the service can emit [CaseStatus.KILLED] and evict
     * the runtime while it is still in `activeRuntimes`. Then [caseScope] is cancelled,
     * which cancels every child job including any in-flight [turnJob].
     */
    fun requestKill() {
        logger.info { "[CaseRuntime $id] Kill requested" }
        // onKilled must be called BEFORE caseScope.cancel() so the service can emit
        // the KILLED status event while the runtime is still in activeRuntimes.
        onKilled(id)
        caseScope.cancel(CancellationException("kill"))
    }

    /** True while the case-loop coroutine is active (not yet killed). */
    fun isAlive(): Boolean = caseScope.isActive

    /** True while a turn is executing (i.e. [turnJob] is active). */
    fun isRunning(): Boolean = turnJob?.isActive == true

    fun pushEvents(events: Collection<CaseEvent>) {
        events.forEach { eventList.add(it) }
    }

    // -------------------------------------------------------------------------
    // Event emission
    // -------------------------------------------------------------------------

    private fun storeAndEmitEvent(event: CaseEvent) {
        val saved = storeEvent(event)
        eventList.add(saved)
        emit(saved)
    }

    fun emitEvent(event: CaseEvent) {
        emit(event)
    }

    // -------------------------------------------------------------------------
    // User message entry point
    // -------------------------------------------------------------------------

    /**
     * Store a user message, emit agent-selection events, and signal the case loop.
     *
     * The case loop wakes up on [workChannel] and starts a new turn. This method
     * returns immediately — no coroutine is launched here.
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
                        return
                    }
                }
            }
        }

        storeAndEmitEvent(MessageEvent(caseId = id, namespaceId = namespaceId, actor = actor, content = content))
        selectAgent(content).forEach { storeAndEmitEvent(it) }

        // Wake the case loop.
        workChannel.trySend(Unit)
    }

    // -------------------------------------------------------------------------
    // Case loop
    // -------------------------------------------------------------------------

    /**
     * Start the long-lived case loop inside [caseScope].
     *
     * Called once by [CaseServiceImpl] when the runtime is created or rehydrated.
     * If the scope is already cancelled (killed runtime) the call is a no-op.
     *
     * Loop lifecycle:
     * 1. Waits on [workChannel] for a signal from [addUserMessage].
     * 2. Launches a child [turnJob] and suspends until it completes or is cancelled.
     * 3. Normal completion → IDLE, wait for next signal.
     * 4. [CancellationException] while [caseScope] is still active → interrupt path → IDLE.
     * 5. [CancellationException] while [caseScope] is cancelled → kill path → exit.
     */
    fun startLoop() {
        if (!caseScope.isActive) {
            logger.warn { "[CaseRuntime $id] startLoop() called on a killed runtime, ignoring" }
            return
        }
        caseScope.launch {
            logger.info { "[CaseRuntime $id] Case loop started" }
            try {
                if (hasPendingWork()) workChannel.trySend(Unit)
                while (isActive) {
                    workChannel.receive()
                    if (!isActive) break

                    logger.info { "[CaseRuntime $id] Work signal received, starting turn" }
                    updateStatus(id, CaseStatus.RUNNING)

                    try {
                        runTurn()
                        if (isActive) {
                            logger.info { "[CaseRuntime $id] Turn complete, transitioning to IDLE" }
                            updateStatus(id, CaseStatus.IDLE)
                        }
                    } catch (e: CancellationException) {
                        // Distinguish interrupt (caseScope still active) from kill.
                        if (isActive) {
                            logger.info { "[CaseRuntime $id] Turn interrupted, transitioning to IDLE" }
                            updateStatus(id, CaseStatus.IDLE)
                        } else {
                            throw e // kill path: exit the loop
                        }
                    } catch (e: Exception) {
                        logger.error(e) { "[CaseRuntime $id] Turn error" }
                        if (isActive) updateStatus(id, CaseStatus.ERROR)
                    }
                }
            } catch (e: CancellationException) {
                logger.info { "[CaseRuntime $id] Case loop exiting due to cancellation (kill)" }
            }
            logger.info { "[CaseRuntime $id] Case loop terminated" }
        }
    }

    /**
     * Run one complete agent turn as a cancellable child [Job] of [caseScope].
     *
     * [shouldContinue] is derived from [Job.isActive] of the [turnJob] itself —
     * no separate flag is required. When [turnJob] is cancelled (by either
     * [requestInterrupt] or [requestKill]), [Job.isActive] becomes false inside the
     * job, so agents stop cooperatively at the same time coroutine cancellation
     * propagates through their suspend points.
     *
     * Throws [CancellationException] if [turnJob] was cancelled, so the case loop
     * can handle the IDLE/kill transition.
     */
    private suspend fun runTurn() {
        var iterationCount = 0

        val job =
            caseScope.launch {
                while (isActive && iterationCount < maxIterations) {
                    // Honour cancellation at the top of every iteration before any
                    // suspend call is made. ensureActive() throws CancellationException
                    // immediately if the turn job (or case scope) has been cancelled.
                    currentCoroutineContext().ensureActive()
                    logger.debug { "[CaseRuntime $id] Turn iteration $iterationCount" }
                    val finished = processNextStep()
                    if (finished) break
                    iterationCount++
                }
                if (iterationCount >= maxIterations) {
                    logger.error { "[CaseRuntime $id] Maximum iterations ($maxIterations) reached" }
                    updateStatus(id, CaseStatus.ERROR)
                }
            }
        turnJob = job

        try {
            job.join()
        } finally {
            turnJob = null
        }

        // Propagate cancellation so the case loop can distinguish interrupt
        // (caseScope still active) from kill. We throw a fresh CancellationException
        // rather than using the internal Job.getCancellationException() API.
        if (job.isCancelled) throw CancellationException("turn cancelled")
    }

    /**
     * Process one step of the event list and return true when the turn is complete.
     *
     * The [shouldContinue] lambda passed to [runAgent] is derived directly from the
     * coroutine context — no captured Job reference or AtomicBoolean needed.
     */
    private suspend fun processNextStep(): Boolean {
        val events = eventList.getAll()
        logger.debug { "[CaseRuntime $id] processNextStep - total events: ${events.size}" }

        val lastUserMessageIndex = events.indexOfLast { it is MessageEvent && it.actor.role == ActorRole.USER }

        for (i in events.lastIndex downTo 0) {
            val event = events[i]

            if (i < lastUserMessageIndex &&
                (event is AgentFinishedEvent || event is AgentRunningEvent || event is AgentSelectedEvent)
            ) {
                logger.debug {
                    "[CaseRuntime $id] Skipping prior-turn ${event::class.simpleName} at index $i " +
                        "(lastUserMsg=$lastUserMessageIndex)"
                }
                continue
            }

            when (event) {
                is AgentFinishedEvent -> {
                    logger.info { "[CaseRuntime $id] Agent finished turn" }
                    return true
                }

                is AgentRunningEvent -> {
                    logger.info { "[CaseRuntime $id] Running agent: ${event.agentName}" }
                    // The lambda is evaluated inside runAgent's suspend body,
                    // which runs in the turn job's coroutine context, so
                    // currentCoroutineContext() is valid there.
                    val ctx = currentCoroutineContext()
                    runAgent(event.agentName, eventList.getAll()) { ctx.isActive }
                    return false
                }

                is AgentSelectedEvent -> {
                    logger.info { "[CaseRuntime $id] Agent selected: ${event.agentName}" }
                    storeAndEmitEvent(
                        AgentRunningEvent(
                            namespaceId = namespaceId,
                            caseId = id,
                            agentId = event.agentId,
                            agentName = event.agentName,
                        ),
                    )
                    return false
                }

                else -> {}
            }
        }

        logger.error { "[CaseRuntime $id] No agent selection found in history, stopping turn" }
        return true
    }

    /**
     * Returns true if the event list already contains an [AgentRunningEvent] or
     * [AgentSelectedEvent] after the last user message (rehydration path).
     */
    private fun hasPendingWork(): Boolean {
        val events = eventList.getAll()
        val lastUserIdx = events.indexOfLast { it is MessageEvent && it.actor.role == ActorRole.USER }
        if (lastUserIdx < 0) return false
        return events.drop(lastUserIdx).any { it is AgentSelectedEvent || it is AgentRunningEvent }
    }

    /**
     * Cancel the case scope and release all coroutine resources.
     * Called by [CaseServiceImpl] after terminal status transitions and on shutdown.
     * Safe to call multiple times (subsequent calls are no-ops).
     */
    fun cancel() {
        caseScope.cancel()
    }

    companion object : KLogging()
}
