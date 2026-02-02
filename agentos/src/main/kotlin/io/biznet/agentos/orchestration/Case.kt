package io.biznet.agentos.orchestration

import kotlinx.coroutines.flow.catch
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

// todo: study load-balancing and controller - service dissociation
//  (controller reactive with user, service keeping cases running)
// todo: load-balancing nightmare

/**
 * Represents a case to be run.
 * The case runs independently in its own thread (managed by CaseService) and continues
 * execution even if no collectors are attached (hot observable) to the output events.
 */
class Case(
    val id: UUID = UUID.randomUUID(),
    val projectId: UUID,
    @Volatile
    private var status: CaseStatus = CaseStatus.PENDING,
    private val agentService: AgentService,
    private val caseService: CaseService,
    private val caseEventService: CaseEventService,
    /**
     * List sorted by timestamp of the events on the case. Sort order to keep.
     */
    inputEvents: List<CaseEvent> = emptyList(),
) : CaseEventEmitter by DefaultCaseEventEmitter() {
    private val eventList = InMemoryCaseEventList(inputEvents)

    private val logger = LoggerFactory.getLogger(Case::class.java)

    // Stop flag for graceful shutdown
    private val stopRequested = AtomicBoolean(false)

    // Kill flag for immediate termination
    private val killRequested = AtomicBoolean(false)

    // Maximum iterations to prevent infinite loops
    private val maxIterations = 100
    private var iterationCount = 0

    private fun storeAndEmitEvent(event: CaseEvent) {
        logger.trace("[Case $id] storeAndEmitEvent - event type: ${event::class.simpleName}, event caseId: ${event.caseId}")
        if (id == event.caseId) {
            // do only store here the events of this case.
            // Sub-case events that bubble up are expected to be saved by their case instance
            logger.debug("[Case $id] Saving event: ${event::class.simpleName}")
            val savedEvent = caseEventService.save(event)
            eventList.add(savedEvent)
            logger.debug("[Case $id] Emitting event to flow: ${event::class.simpleName}")
            emit(savedEvent)
            logger.trace("[Case $id] Event emitted successfully")
        } else {
            // let the event bubble up.
            logger.debug("[Case $id] Bubbling up event from different case: ${event.caseId}")
            emit(event)
        }
    }

    /**
     * Request the case to stop gracefully.
     * Preserves case state and allows clean completion of current operation.
     */
    fun stop() {
        logger.info("[Case $id] Stop requested")
        updateStatus(CaseStatus.STOPPING)
        stopRequested.set(true)
    }

    /**
     * Cleanup resources used by this case (agents, etc.).
     * Called when case completes normally.
     */
    suspend fun cleanup() {
        logger.info("[Case $id] Cleaning up resources")
        try {
            agentService.cleanup()
        } catch (e: Exception) {
            logger.error("[Case $id] Error during cleanup", e)
            // Don't throw - cleanup should be best-effort
        }
    }

    /**
     * Immediately terminate case execution and cleanup resources.
     * Unlike stop(), this method does not preserve state.
     */
    suspend fun kill() {
        logger.info("[Case $id] Kill requested")
        killRequested.set(true)
        stop()
        try {
            agentService.kill()
            cleanup()
        } catch (e: Exception) {
            logger.error("[Case $id] Error during kill", e)
        }
    }

    fun save() {
        caseService.save(
            CaseModel(
                // fixme: this looses all other audit fields, not good
                metadata = EntityMetadata(id = id),
                projectId = projectId,
                status = status,
            ),
        )
    }

    private fun updateStatus(newStatus: CaseStatus) {
        val oldStatus = status
        status = newStatus
        save()
        emit(CaseStatusEvent(metadata = EntityMetadata(), caseId = id, projectId = projectId, status = status))
        if (newStatus == CaseStatus.ERROR) {
            logger.error("Case $id status: $oldStatus -> $newStatus")
        } else {
            logger.info("Case $id status: $oldStatus -> $newStatus")
        }
    }

    suspend fun addUserMessage(
        actor: Actor,
        content: List<MessageContent>,
        answerToEventId: UUID? = null,
    ) {
        logger.info(
            "[Case $id] addUserMessage called - actor: ${actor.displayName}, content size: ${content.size}, answerTo: $answerToEventId",
        )
        logger.debug("[Case $id] Current status: $status")
        // If this is an answer to a question, create an AnswerEvent
        if (answerToEventId != null) {
            val questionEvent = eventList.getById(answerToEventId)

            if (questionEvent == null) {
                logger.warn("[Case $id] Question event $answerToEventId not found, treating as regular message")
            } else if (questionEvent !is QuestionEvent) {
                logger.warn(
                    "[Case $id] Event $answerToEventId is not a QuestionEvent (${questionEvent::class.simpleName}), treating as regular message",
                )
            } else {
                // Extract answer text from content
                val answerText =
                    content
                        .filterIsInstance<MessageContent.Text>()
                        .joinToString(" ") { it.content }

                if (answerText.isBlank()) {
                    logger.warn("[Case $id] Answer text is blank for question $answerToEventId")
                } else {
                    val answerEvent = questionEvent.createAnswer(actor, answerText)
                    storeAndEmitEvent(answerEvent)
                    logger.info("[Case $id] Answer added for question: ${questionEvent.question}")
                    // Don't run() here - answer is passive, waits for agent to process it
                    return
                }
            }
        }

        // Regular message flow
        logger.debug("[Case $id] Creating MessageEvent")
        val userMessageEvent =
            MessageEvent(
                caseId = id,
                projectId = projectId,
                actor = actor,
                content = content,
            )
        logger.debug("[Case $id] Storing and emitting MessageEvent")
        storeAndEmitEvent(userMessageEvent)
        logger.debug("[Case $id] Detecting agent selection")
        detectAgentSelection(content)
        logger.info("[Case $id] Starting case run()")
        run()
    }

    /**
     * Parse message content to detect agent mentions (@agentName) and handle them.
     * If an agent is mentioned and found, emits an AgentSelectedEvent.
     * If an agent is mentioned but not found, emits a WarnEvent.
     */
    private fun detectAgentSelection(content: List<MessageContent>) {
        val textContent = content.filterIsInstance<MessageContent.Text>()
        if (textContent.isEmpty()) return

        val firstText = textContent.first().content.trim()
        val mentionPattern = """^@(\S+)""".toRegex()
        val matchResult = mentionPattern.find(firstText) ?: return

        val agentName = matchResult.groupValues[1]
        logger.debug("[Case $id] Agent mention detected: @$agentName")

        try {
            val agent = agentService.findAgentByName(agentName)
            val agentSelectedEvent =
                AgentSelectedEvent(
                    projectId = projectId,
                    caseId = id,
                    agentId = agent.metadata.id,
                    agentName = agent.name,
                )
            storeAndEmitEvent(agentSelectedEvent)
            logger.info("[Case $id] Agent selected: ${agent.name}")
        } catch (e: Exception) {
            val warnEvent =
                WarnEvent(
                    projectId = projectId,
                    caseId = id,
                    message = "Agent '$agentName' not found",
                )
            storeAndEmitEvent(warnEvent)
            logger.warn("[Case $id] Agent '@$agentName' not found: ${e.message}")
        }
    }

    /**
     * Main execution method for the case.
     * This method blocks until the case is resolved or stopped.
     * It should be called in a dedicated thread by the CaseService.
     */
    suspend fun run() {
        logger.info("[Case $id] run() called - current status: $status")

        // Case is already running, do nothing
        if (status == CaseStatus.RUNNING) {
            logger.warn("[Case $id] Already running, skipping run()")
            return
        }

        logger.info("[Case $id] Starting case execution")

        // Reset stop flags when restarting
        stopRequested.set(false)
        killRequested.set(false)

        updateStatus(CaseStatus.RUNNING)
        iterationCount = 0

        try {
            logger.debug("[Case $id] Entering main processing loop")
            // Main processing loop
            while (!stopRequested.get() && !killRequested.get() && iterationCount < maxIterations) {
                logger.debug("[Case $id] Processing iteration $iterationCount")
                processNextStep()
                iterationCount++
            }
            logger.info(
                "[Case $id] Exited main loop - iterations: $iterationCount, stopRequested: ${stopRequested.get()}, killRequested: ${killRequested.get()}",
            )

            if (iterationCount >= maxIterations) {
                logger.error("[Case $id] Maximum iterations ($maxIterations) reached")
                updateStatus(CaseStatus.ERROR)
            } else if (killRequested.get()) {
                updateStatus(CaseStatus.STOPPED)
            } else if (stopRequested.get()) {
                updateStatus(CaseStatus.STOPPED)
            } else {
                // Case completed normally - all agents finished
                updateStatus(CaseStatus.STOPPED)
            }
        } catch (e: Exception) {
            logger.error("[Case $id] Error during execution", e)
            updateStatus(CaseStatus.ERROR)
        } finally {
            cleanup()
        }
    }

    private suspend fun runAgent(agent: Agent) {
        logger.info("[Case $id] Running agent: ${agent.name}")

        agent
            .run(eventList.getAll())
            .catch { error ->
                logger.error("[Case $id] Error in agent ${agent.name}", error)
                // Emit error event but continue processing
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

        logger.info("[Case $id] Agent ${agent.name} finished")
    }

    /**
     * Process the next step in the case resolution, can be:
     * - an agent was selected, need to retrieve it and start running it ?
     * - an agent was running, duplicate of selected ? actually trigger the agent.run() ?
     * - an agent finished, just stop ?
     *
     * tool calls should be emitted and seen in the case, but handled and built in the agent.run()
     */
    private suspend fun processNextStep() {
        val events = eventList.getAll()
        logger.debug("[Case $id] processNextStep - total events: ${events.size}")

        // Find the last user message to know where to start looking for agent events
        val lastUserMessageIndex = events.indexOfLast { it is MessageEvent && it.actor.role == ActorRole.USER }
        logger.debug("[Case $id] Last user message at index: $lastUserMessageIndex")

        // get through events by last one, but only after the last user message
        for (i in events.lastIndex downTo 0) {
            val event = events[i]
            logger.trace("[Case $id] Checking event $i: ${event::class.simpleName}")

            // Skip AgentFinishedEvent if it's before the last user message (old conversation)
            if (event is AgentFinishedEvent && i < lastUserMessageIndex) {
                logger.debug("[Case $id] Skipping old AgentFinishedEvent at index $i (before last user message)")
                continue
            }

            when (event) {
                // if agent finished, just stop
                is AgentFinishedEvent -> {
                    logger.info("[Case $id] Found AgentFinishedEvent, stopping case")
                    stopRequested.set(true)
                    return
                }
                // if agent running, make it run
                is AgentRunningEvent -> {
                    logger.info("[Case $id] Found AgentRunningEvent for agent: ${event.agentName}")
                    val agent = agentService.findAgentByName(event.agentName)
                    runAgent(agent)
                    return
                }

                // if agent selected, make it run
                is AgentSelectedEvent -> {
                    logger.info("[Case $id] Found AgentSelectedEvent for agent: ${event.agentName}, transitioning to running")
                    val agentRunningEvent =
                        AgentRunningEvent(
                            projectId = projectId,
                            caseId = id,
                            agentId = event.agentId,
                            agentName = event.agentName,
                        )
                    storeAndEmitEvent(agentRunningEvent)
                    return
                }

                // Continue looking for relevant events
                else -> {
                    // just loop back again
                }
            }
        }

        logger.debug("[Case $id] No relevant events found, selecting default agent")

        selectDefaultAgent()
    }

    private fun selectDefaultAgent() {
        logger.info("[Case $id] Selecting default agent")
        val defaultAgent = agentService.getDefaultAgent()
        if (defaultAgent != null) {
            logger.info("[Case $id] Default agent found: ${defaultAgent.name} (id: ${defaultAgent.metadata.id})")
            val agentSelectedEvent =
                AgentSelectedEvent(
                    projectId = projectId,
                    caseId = id,
                    agentId = defaultAgent.metadata.id,
                    agentName = defaultAgent.name,
                )
            logger.debug("[Case $id] Emitting AgentSelectedEvent")
            storeAndEmitEvent(agentSelectedEvent)
            logger.info("[Case $id] AgentSelectedEvent emitted for ${defaultAgent.name}")
        } else {
            logger.error("[Case $id] No default agent configured, stopping case")
            stopRequested.set(true)
        }
    }
}
