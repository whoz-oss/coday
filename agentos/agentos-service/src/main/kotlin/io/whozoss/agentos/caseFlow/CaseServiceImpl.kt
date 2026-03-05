package io.whozoss.agentos.caseFlow

import io.whozoss.agentos.agent.AgentService
import io.whozoss.agentos.caseEvent.CaseEventService
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.CaseStatusEvent
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.entity.EntityMetadata
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import mu.KLogging
import org.springframework.stereotype.Service
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Service
class CaseServiceImpl(
    private val agentService: AgentService,
    private val caseRepository: CaseRepository,
    private val caseEventService: CaseEventService,
    private val caseService: CaseService,
) : CaseService {
    private val executor: ExecutorService =
        Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "case-executor").apply { isDaemon = true }
        }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeRuntimes = ConcurrentHashMap<UUID, CaseRuntime>()

    // ========================================
    // EntityService
    // ========================================

    override fun create(entity: Case): Case {
        val saved = caseRepository.save(Case(metadata = entity.metadata, projectId = entity.projectId))
        val runtime = buildRuntime(saved)
        activeRuntimes[runtime.id] = runtime
        logger.info { "[CaseService] Case created: ${runtime.id} for project ${entity.projectId}" }
        return saved
    }

    override fun update(entity: Case): Case {
        val saved = caseRepository.save(entity)
        if (saved.status.isTerminal()) {
            activeRuntimes.remove(saved.id)
            logger.info { "[CaseService] Case ${saved.id} reached terminal status ${saved.status}, evicted from active runtimes" }
        }
        return saved
    }

    override fun findByIds(ids: Collection<UUID>): List<Case> = caseRepository.findByIds(ids)

    override fun findByParent(parentId: UUID): List<Case> = caseRepository.findByParent(parentId)

    override fun delete(id: UUID): Boolean {
        activeRuntimes.remove(id)?.stop()
        return caseRepository.delete(id)
    }

    override fun deleteByParent(parentId: UUID): Int {
        findByParent(parentId).forEach { activeRuntimes.remove(it.id)?.stop() }
        return caseRepository.deleteByParent(parentId)
    }

    // ========================================
    // Runtime Instance Management
    // ========================================

    override fun getCaseRuntime(caseId: UUID): CaseRuntime = activeRuntimes.computeIfAbsent(caseId) { rehydrate(it) }

    /**
     * Rehydrates a [CaseRuntime] from the repository for a case that exists on disk
     * but has no live runtime instance (e.g. after a restart or for a past case
     * that is being resumed).
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

    /**
     * Constructs a [CaseRuntime] wired with the service callbacks for status changes and event storage.
     */
    private fun buildRuntime(
        case: Case,
        inputEvents: List<CaseEvent> = emptyList(),
    ): CaseRuntime =
        CaseRuntime(
            id = case.id,
            projectId = case.projectId,
            agentService = agentService,
            updateStatus = { caseId, newStatus -> handleStatusChange(caseId, newStatus) },
            emitAndStoreEvent = { event, caseRuntime -> handleEvent(event, caseRuntime) },
            inputEvents = inputEvents,
        )

    /**
     * Persists the new status and emits a [CaseStatusEvent] on the runtime's flow.
     * Called by the runtime via the [CaseRuntime.updateStatus] callback.
     */
    private fun handleStatusChange(
        caseId: UUID,
        newStatus: CaseStatus,
    ) {
        val case = getById(caseId)
        val oldStatus = case.status
        val updated = caseRepository.save(case.copy(status = newStatus))
        if (newStatus.isTerminal()) {
            activeRuntimes.remove(caseId)
            logger.info { "[CaseService] Case $caseId reached terminal status $newStatus, evicted from active runtimes" }
        }
        val statusEvent =
            CaseStatusEvent(
                metadata = EntityMetadata(),
                caseId = caseId,
                projectId = updated.projectId,
                status = newStatus,
            )
        // Emit the status event through the runtime's own flow so SSE clients receive it
        activeRuntimes[caseId]?.let { handleEvent(statusEvent, it) }
        if (newStatus == CaseStatus.ERROR) {
            logger.error { "Case $caseId status: $oldStatus -> $newStatus" }
        } else {
            logger.info { "Case $caseId status: $oldStatus -> $newStatus" }
        }
    }

    /**
     * Persists the event via [CaseEventService], emit into the case runtime and returns the saved copy.
     */
    private fun handleEvent(
        event: CaseEvent,
        caseRuntime: CaseRuntime,
    ) {
        val id = caseRuntime.id
        logger.trace { "[CaseRuntime $id] storeAndEmitEvent - event type: ${event::class.simpleName}, event caseId: ${event.caseId}" }
        if (id == event.caseId) {
            // Only store events that belong to this case.
            // Sub-case events that bubble up are expected to be saved by their own runtime.
            logger.debug { "[CaseRuntime $id] Saving event: ${event::class.simpleName}" }
            val savedEvent = caseEventService.create(event)
            caseRuntime.emitEventFromThisCase(savedEvent)
            logger.trace { "[CaseRuntime $id] Event emitted successfully" }
        } else {
            // Let the event bubble up to a parent case.
            logger.debug { "[CaseRuntime $id] Bubbling up event from different case: ${event.caseId}" }
            caseRuntime.emitEventFromOtherCase(event)
        }
    }

    override fun getActiveCasesByProject(projectId: UUID): List<CaseRuntime> = activeRuntimes.values.filter { it.projectId == projectId }

    override fun getAllActiveCases(): List<CaseRuntime> = activeRuntimes.values.toList()

    // ========================================
    // Execution Control
    // ========================================

    override fun stopCase(caseId: UUID) {
        val runtime =
            activeRuntimes[caseId]
                ?: throw ResourceNotFoundException("No active case runtime found: $caseId")
        logger.info { "Stopping case: $caseId" }
        runtime.stop()
    }

    override fun killCase(caseId: UUID) {
        val runtime =
            activeRuntimes.remove(caseId)
                ?: throw ResourceNotFoundException("No active case runtime found: $caseId")
        logger.info { "Killing case: $caseId" }
        runBlocking { runtime.kill() }
    }

    // ========================================
    // Lifecycle
    // ========================================

    @PreDestroy
    fun shutdown() {
        logger.info { "Shutting down CaseService..." }
        activeRuntimes.values.forEach { it.stop() }
        activeRuntimes.clear()
        scope.cancel()
        executor.shutdown()
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                logger.warn { "Executor did not terminate in time, forcing shutdown" }
                executor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            logger.error(e) { "Interrupted while waiting for executor shutdown" }
            executor.shutdownNow()
            Thread.currentThread().interrupt()
        }
        logger.info { "CaseService shutdown complete" }
    }

    companion object : KLogging()
}
