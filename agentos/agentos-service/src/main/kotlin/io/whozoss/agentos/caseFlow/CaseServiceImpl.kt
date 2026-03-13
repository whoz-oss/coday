package io.whozoss.agentos.caseFlow

import io.whozoss.agentos.agent.AgentExecutionContext
import io.whozoss.agentos.agent.AgentService
import io.whozoss.agentos.caseEvent.CaseEventService
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.caseEvent.AgentSelectedEvent
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.CaseStatusEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.WarnEvent
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.entity.EntityMetadata
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import mu.KLogging
import org.springframework.stereotype.Service
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Service
class CaseServiceImpl(
    private val agentService: AgentService,
    private val caseRepository: CaseRepository,
    private val caseEventService: CaseEventService,
) : CaseService {
    /**
     * Coroutine scope used to run case execution loops in the background.
     * Each [run] call is launched here so HTTP threads are never blocked.
     */
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val activeRuntimes = ConcurrentHashMap<UUID, CaseRuntime>()

    // ========================================
    // EntityService
    // ========================================

    override fun create(entity: Case): Case {
        require(findById(entity.id) == null) { "Duplicate entity id: ${entity.id}" }
        val saved = caseRepository.save(Case(metadata = entity.metadata, namespaceId = entity.namespaceId))
        activeRuntimes[saved.id] = buildRuntime(saved)
        logger.info { "[CaseService] Case created: ${saved.id} for namespace ${entity.namespaceId}" }
        return saved
    }

    override fun update(entity: Case): Case {
        val current =
            findById(entity.id)
                ?: throw ResourceNotFoundException("Case not found: ${entity.id}")
        return if (entity.status != current.status) {
            // Route status changes through handleStatusChange so the runtime and
            // SSE clients stay consistent with the persisted state.
            handleStatusChange(entity.id, entity.status)
            // handleStatusChange already persisted the new status; return fresh view.
            findById(entity.id) ?: entity
        } else {
            caseRepository.save(entity)
        }
    }

    override fun findByIds(ids: Collection<UUID>): List<Case> = caseRepository.findByIds(ids)

    override fun findByParent(parentId: UUID): List<Case> = caseRepository.findByParent(parentId)

    override fun delete(id: UUID): Boolean {
        if (activeRuntimes.containsKey(id)) {
            killCase(id)
        }
        return caseRepository.delete(id)
    }

    override fun deleteByParent(parentId: UUID): Int {
        findByParent(parentId).forEach { killCase(it.id) }
        return caseRepository.deleteByParent(parentId)
    }

    // ========================================
    // Runtime lifecycle
    // ========================================

    override fun getCaseRuntime(caseId: UUID): CaseRuntime = activeRuntimes.computeIfAbsent(caseId) { rehydrate(it) }

    override fun findActiveRuntime(caseId: UUID): CaseRuntime? = activeRuntimes[caseId]

    /**
     * Rehydrates a [CaseRuntime] for a case that exists on disk but has no live
     * runtime instance (e.g. after a restart or reconnection to a past case).
     *
     * @throws ResourceNotFoundException if no persisted [Case] exists for [caseId]
     */
    private fun rehydrate(caseId: UUID): CaseRuntime {
        val case =
            caseRepository.findByIds(listOf(caseId)).firstOrNull()
                ?: throw ResourceNotFoundException("Case not found: $caseId")
        val pastEvents = caseEventService.findByParent(caseId)
        logger.info { "[CaseService] Rehydrating case $caseId with ${pastEvents.size} past events" }
        return buildRuntime(case, pastEvents)
    }

    /** Constructs a [CaseRuntime] wired with all service callbacks. */
    private fun buildRuntime(
        case: Case,
        inputEvents: List<CaseEvent> = emptyList(),
    ): CaseRuntime =
        CaseRuntime(
            id = case.id,
            namespaceId = case.namespaceId,
            updateStatus = { caseId, newStatus -> handleStatusChange(caseId, newStatus) },
            storeEvent = { event -> storeEvent(event) },
            selectAgent = { content -> selectAgent(content, case.namespaceId, case.id) },
            runAgent = { agentName, events -> runAgent(agentName, case.id, events) },
            inputEvents = inputEvents,
        )

    // ========================================
    // Message handling (called by controller)
    // ========================================

    override fun addMessage(
        caseId: UUID,
        actor: Actor,
        content: List<MessageContent>,
        answerToEventId: UUID?,
    ) {
        val runtime = getCaseRuntime(caseId)
        runtime.addUserMessage(actor, content, answerToEventId)
        // run() is self-guarding via an AtomicBoolean — launch unconditionally.
        scope.launch { runtime.run() }
    }

    // ========================================
    // Agent selection (business logic)
    // ========================================

    /**
     * Resolves which agent should handle a message and returns the ordered list of
     * events to store+emit on the runtime.
     *
     * Returns an empty list when no agent is configured (signals the runtime to stop).
     *
     * @param content the message content to inspect for @mention syntax.
     */
    private fun selectAgent(
        content: List<MessageContent>,
        namespaceId: UUID,
        caseId: UUID,
    ): List<CaseEvent> {
        val firstText =
            content
                .filterIsInstance<MessageContent.Text>()
                .firstOrNull()
                ?.content
                ?.trim()

        val mentionedName = firstText?.let { """^@(\S+)""".toRegex().find(it)?.groupValues?.get(1) }

        if (mentionedName != null) {
            val resolvedName = agentService.resolveAgentName(mentionedName)
            if (resolvedName != null) {
                logger.info { "[CaseService] Agent mention resolved: @$mentionedName -> $resolvedName" }
                return listOf(agentSelectedEvent(resolvedName, namespaceId, caseId))
            } else {
                logger.warn { "[CaseService] Agent '@$mentionedName' not found, falling back to default" }
                val warn =
                    WarnEvent(namespaceId = namespaceId, caseId = caseId, message = "Agent '$mentionedName' not found")
                val defaultName = agentService.getDefaultAgentName() ?: return listOf(warn)
                return listOf(warn, agentSelectedEvent(defaultName, namespaceId, caseId))
            }
        }

        val defaultName = agentService.getDefaultAgentName()
            ?: run {
                logger.warn { "[CaseService] No AI model configured — cannot select a default agent" }
                return listOf(
                    WarnEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        message = "No AI model is configured. Load a plugin that provides an AiModel.",
                    ),
                )
            }
        logger.info { "[CaseService] Selecting default agent: $defaultName" }
        return listOf(agentSelectedEvent(defaultName, namespaceId, caseId))
    }

    private fun agentSelectedEvent(
        agentName: String,
        namespaceId: UUID,
        caseId: UUID,
    ) = AgentSelectedEvent(
        namespaceId = namespaceId,
        caseId = caseId,
        agentId = UUID.nameUUIDFromBytes(agentName.toByteArray()),
        agentName = agentName,
    )

    // ========================================
    // Agent execution (business logic)
    // ========================================

    private suspend fun runAgent(
        agentName: String,
        caseId: UUID,
        events: List<CaseEvent>,
    ) {
        val runtime = activeRuntimes[caseId] ?: throw ResourceNotFoundException("No active case runtime found: $caseId")
        logger.info { "[CaseService] Running agent: $agentName for case $caseId" }
        val context = AgentExecutionContext(namespaceId = runtime.namespaceId, caseId = caseId)
        agentService
            .findAgentByName(agentName, context)
            .run(events)
            .catch { error ->
                logger.error(error) { "[CaseService] Error in agent $agentName for case $caseId" }
                storeEvent(
                    WarnEvent(
                        namespaceId = runtime.namespaceId,
                        caseId = caseId,
                        message = "Agent $agentName error: ${error.message}",
                    ),
                ).also { saved ->
                    runtime.emitEvent(saved)
                }
            }.collect { event ->
                val saved = storeEvent(event)
                if (event.caseId == caseId) {
                    // Push into the runtime's event list so processNextStep can see it
                    // (e.g. AgentFinishedEvent stops the loop)
                    runtime.pushEvents(listOf(saved))
                }
                // emit on the SSE flow.
                runtime.emitEvent(saved)
            }
        logger.info { "[CaseService] Agent $agentName finished for case $caseId" }
    }

    /**
     * Persists an event via [CaseEventService] and returns the saved copy.
     * Called by the runtime's [CaseRuntime.storeEvent] callback —
     * the runtime itself handles adding to its list and emitting on the SSE flow.
     */
    private fun storeEvent(event: CaseEvent): CaseEvent = caseEventService.create(event)

    // ========================================
    // Status transitions
    // ========================================

    /**
     * Persists the new status, emits a [CaseStatusEvent] to SSE clients,
     * and evicts the runtime when a terminal status is reached.
     *
     * The runtime is evicted *after* the status event is emitted so SSE clients
     * always receive the final status before the stream closes.
     */
    private fun handleStatusChange(
        caseId: UUID,
        newStatus: CaseStatus,
    ) {
        val case = getById(caseId)
        val oldStatus = case.status
        val updated = caseRepository.save(case.copy(status = newStatus))

        if (newStatus == CaseStatus.ERROR) {
            logger.error { "Case $caseId status: $oldStatus -> $newStatus" }
        } else {
            logger.info { "Case $caseId status: $oldStatus -> $newStatus" }
        }

        val statusEvent =
            CaseStatusEvent(
                metadata = EntityMetadata(),
                caseId = caseId,
                namespaceId = updated.namespaceId,
                status = newStatus,
            )
        val savedStatusEvent = caseEventService.create(statusEvent)

        // Emit the status event before eviction so SSE clients receive it.
        activeRuntimes[caseId]?.let {
            it.emitEvent(savedStatusEvent)
            if (newStatus.isTerminal()) {
                activeRuntimes.remove(caseId)
                logger.info { "[CaseService] Case $caseId reached terminal status $newStatus, evicted" }
            }
        }
    }

    // ========================================
    // Execution control
    // ========================================

    override fun getActiveCasesByNamespace(namespaceId: UUID): List<CaseRuntime> =
        activeRuntimes.values.filter { it.namespaceId == namespaceId }

    override fun getAllActiveCases(): List<CaseRuntime> = activeRuntimes.values.toList()

    override fun interruptCase(caseId: UUID) {
        val runtime =
            activeRuntimes[caseId]
                ?: throw ResourceNotFoundException("No active case runtime found: $caseId")
        logger.info { "Interrupting case: $caseId" }
        runtime.requestInterrupt()
    }

    override fun killCase(caseId: UUID) {
        logger.info { "Killing case: $caseId" }
        // Signal the runtime loop to exit cleanly if it is currently running,
        // then let handleStatusChange evict it via the isTerminal() path.
        activeRuntimes[caseId]?.requestKill()
        handleStatusChange(caseId, CaseStatus.KILLED)
    }

    // ========================================
    // Lifecycle
    // ========================================

    @PreDestroy
    fun shutdown() {
        logger.info { "Shutting down CaseService..." }
        activeRuntimes.keys.toList().forEach {
            try {
                killCase(it)
            } catch (e: Exception) {
                logger.warn(e) { "Error killing case $it during shutdown" }
            }
        }
        activeRuntimes.clear()
        scope.cancel()
        logger.info { "CaseService shutdown complete" }
    }

    companion object : KLogging()
}
