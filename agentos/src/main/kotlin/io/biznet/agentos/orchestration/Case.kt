package io.biznet.agentos.orchestration

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
    private val agentService: IAgentService,
    private val caseService: ICaseService,
    private val caseEventService: ICaseEventService,
    /**
     * List sorted by timestamp of the events on the case. Sort order to keep.
     */
    inputEvents: List<CaseEvent> = emptyList(),
) : CaseEventEmitter by DefaultCaseEventEmitter() {
    private val eventList = InMemoryCaseEventList(inputEvents)

    private val logger = LoggerFactory.getLogger(Case::class.java)

    // Stop flag for graceful shutdown
    private val stopRequested = AtomicBoolean(false)

    private fun storeAndEmitEvent(event: CaseEvent) {
        val savedEvent = caseEventService.save(event)
        eventList.add(savedEvent)
        emit(savedEvent)
    }

    /**
     * Request the case to stop gracefully
     */
    fun stop() {
        updateStatus(CaseStatus.STOPPING)
        stopRequested.set(true)
    }

    fun save() {
        caseService.save(
            CaseModel(
                id = id,
                projectId = projectId,
                status = status,
            ),
        )
    }

    private fun updateStatus(newStatus: CaseStatus) {
        val oldStatus = status
        status = newStatus
        save()
        emit(CaseStatusEvent(caseId = id, projectId = projectId, status = status))
        if (newStatus == CaseStatus.ERROR) {
            logger.error("Case $id status: $oldStatus -> $newStatus")
        } else {
            logger.info("Case $id status: $oldStatus -> $newStatus")
        }
    }

    fun addUserMessage(
        actor: Actor,
        content: List<MessageContent>,
    ) {
        val userMessageEvent =
            MessageEvent(
                caseId = id,
                projectId = projectId,
                actor = actor,
                content = content,
            )
        storeAndEmitEvent(userMessageEvent)
        detectAgentSelection(content)
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
                    agentId = agent.id,
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
    fun run() {
        // todo: blocking method, think about making it async from here ?
        // Case is already running, do nothing
        if (status == CaseStatus.RUNNING) {
            return
        }

        updateStatus(CaseStatus.RUNNING)

        try {
            // Main processing loop
            while (!stopRequested.get()) {
                processNextStep()
                TODO("start adding loop count and safe guards, or at least placeholder in algorithm")
            }

            if (stopRequested.get()) {
                updateStatus(CaseStatus.STOPPED)
            } else {
                updateStatus(CaseStatus.STOPPED)
            }
        } catch (e: Exception) {
            updateStatus(CaseStatus.ERROR)
        }
    }

    private fun runAgent(agent: IAgent) {
        agent.run(eventList.getAll())
        TODO("handle the expected flow of CaseEvents")
    }

    /**
     * Process the next step in the case resolution, can be:
     * - an agent was selected, need to retrieve it and start running it ?
     * - an agent was running, duplicate of selected ? actually trigger the agent.run() ?
     * - an agent finished, just stop ?
     *
     * tool calls should be emitted and seen in the case, but handled and built in the agent.run()
     */
    private fun processNextStep() {
        // get through events by last one
        val events = eventList.getAll()
        for (i in events.lastIndex downTo 0) {
            val event = events[i]
            when (event) {
                // if agent finished, just stop
                is AgentFinishedEvent -> {
                    stopRequested.set(true)
                    break
                }
                // if agent running, make it run
                is AgentRunningEvent -> {
                    val agent = agentService.findAgentByName(event.agentName)
                    runAgent(agent)
                    break
                }

                // if agent selected, make it run
                is AgentSelectedEvent -> {
                    val agentRunningEvent =
                        AgentRunningEvent(
                            projectId = projectId,
                            caseId = id,
                            agentId = event.agentId,
                            agentName = event.agentName,
                        )
                    storeAndEmitEvent(agentRunningEvent)
                    break
                }

                // then select agent from last user message continuous sequence
                else -> {
                    // just loop back again
                }
            }
        }
        TODO("handle default agent selection here ?")
    }
}
