package io.whozoss.agentos.caseFlow

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
 * Owns only execution state: the event list, the SSE flow, and the stop/kill flags.
 * All business logic is delegated back to [CaseService] through callbacks:
 *
 * @param updateStatus called whenever the runtime transitions to a new [CaseStatus].
 * @param emitAndStoreEvent called for every event produced by this runtime.
 *   The service persists the event; the runtime then adds it to its list and emits it on the flow.
 * @param resolveAgent resolves an agent @mention to its canonical name, or null if not found.
 * @param getDefaultAgentName returns the name of the default agent, or null if none configured.
 * @param findAgent instantiates an [Agent] by canonical name for execution.
 */
class CaseRuntime(
    val id: UUID,
    val projectId: UUID,
    private val updateStatus: (UUID, CaseStatus) -> Unit,
    private val emitAndStoreEvent: (CaseEvent, CaseRuntime) -> Unit,
    private val resolveAgent: (String) -> String?,
    private val getDefaultAgentName: () -> String?,
    private val findAgent: (String) -> Agent,
    inputEvents: List<CaseEvent> = emptyList(),
) : CaseEventEmitter by DefaultCaseEventEmitter() {
    private val eventList = InMemoryCaseEventList(inputEvents)

    private val stopRequested = AtomicBoolean(false)
    private val killRequested = AtomicBoolean(false)

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

    fun isRunning(): Boolean = !stopRequested.get() && !killRequested.get() && iterationCount > 0

    fun pushEvents(events: Collection<CaseEvent>) {
        events.forEach { eventList.add(it) }
    }

    // -------------------------------------------------------------------------
    // Event emission
    // -------------------------------------------------------------------------

    fun emitEventFromThisCase(event: CaseEvent) {
        eventList.add(event)
        emit(event)
    }

    fun emitEventFromOtherCase(event: CaseEvent) {
        emit(event)
    }

    private fun storeAndEmitEvent(event: CaseEvent) {
        emitAndStoreEvent(event, this)
    }

    // -------------------------------------------------------------------------
    // User message entry point
    // -------------------------------------------------------------------------

    suspend fun addUserMessage(
        actor: Actor,
        content: List<MessageContent>,
        answerToEventId: UUID? = null,
    ) {
        logger.info {
            "[CaseRuntime $id] addUserMessage - actor: ${actor.displayName}, content: ${content.size} part(s), answerTo: $answerToEventId"
        }

        if (answerToEventId != null) {
            val questionEvent = eventList.getById(answerToEventId)
            when {
                questionEvent == null -> {
                    logger.warn { "[CaseRuntime $id] Question event $answerToEventId not found, treating as regular message" }
                }

                questionEvent !is QuestionEvent -> {
                    logger.warn {
                        "[CaseRuntime $id] Event $answerToEventId is not a QuestionEvent (${questionEvent::class.simpleName}), treating as regular message"
                    }
                }

                else -> {
                    val answerText = content.filterIsInstance<MessageContent.Text>().joinToString(" ") { it.content }
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
        detectAgentSelection(content)
        logger.info { "[CaseRuntime $id] Starting run()" }
        run()
    }

    // -------------------------------------------------------------------------
    // Agent selection
    // -------------------------------------------------------------------------

    private fun detectAgentSelection(content: List<MessageContent>) {
        val firstText =
            content
                .filterIsInstance<MessageContent.Text>()
                .firstOrNull()
                ?.content
                ?.trim() ?: return
        val agentName = """^@(\S+)""".toRegex().find(firstText)?.groupValues?.get(1) ?: return

        logger.debug { "[CaseRuntime $id] Agent mention detected: @$agentName" }
        val resolvedName = resolveAgent(agentName)
        if (resolvedName != null) {
            storeAndEmitEvent(
                AgentSelectedEvent(
                    projectId = projectId,
                    caseId = id,
                    agentId = UUID.nameUUIDFromBytes(resolvedName.toByteArray()),
                    agentName = resolvedName,
                ),
            )
            logger.info { "[CaseRuntime $id] Agent selected: $resolvedName" }
        } else {
            storeAndEmitEvent(WarnEvent(projectId = projectId, caseId = id, message = "Agent '$agentName' not found"))
            logger.warn { "[CaseRuntime $id] Agent '@$agentName' not found" }
        }
    }

    // -------------------------------------------------------------------------
    // Main execution loop
    // -------------------------------------------------------------------------

    suspend fun run() {
        logger.info { "[CaseRuntime $id] run() called" }

        if (isRunning()) {
            logger.warn { "[CaseRuntime $id] Already running, skipping run()" }
            return
        }

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
                    runAgent(findAgent(event.agentName))
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
        val defaultAgentName = getDefaultAgentName()
        if (defaultAgentName != null) {
            val agentId = UUID.nameUUIDFromBytes(defaultAgentName.toByteArray())
            logger.info { "[CaseRuntime $id] Selecting default agent: $defaultAgentName (id: $agentId)" }
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

    companion object : KLogging()
}
