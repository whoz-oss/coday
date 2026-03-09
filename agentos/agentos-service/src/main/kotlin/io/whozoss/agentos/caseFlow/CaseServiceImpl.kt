package io.whozoss.agentos.caseFlow

import io.whozoss.agentos.agent.AgentService
import io.whozoss.agentos.caseEvent.CaseEventService
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.runBlocking
import mu.KLogging
import org.springframework.stereotype.Service
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Service responsible for managing the lifecycle of cases.
 *
 * Responsibilities:
 * - Persist case metadata via CaseRepository
 * - Manage runtime Case instances
 * - Handle case execution threading
 * - Expose event streams for SSE broadcasting
 * - Coordinate case lifecycle (create, start, stop, kill)
 */
@Service
class CaseServiceImpl(
    private val agentService: AgentService,
    private val caseRepository: CaseRepository,
    private val caseEventService: CaseEventService,
) : CaseService {
    // Thread pool for executing cases
    private val executor: ExecutorService =
        Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "case-executor").apply {
                isDaemon = true
            }
        }

    // Coroutine scope for flow collectors
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Active runtime case instances indexed by ID
    private val activeCases = ConcurrentHashMap<UUID, Case>()

    // ========================================
    // EntityService Implementation (Persistence)
    // ========================================

    override fun create(entity: CaseModel): CaseModel {
        val saved =
            caseRepository.save(
                CaseModel(projectId = entity.projectId),
            )
        logger.debug { "[CaseService] CaseModel persisted: ${saved.metadata.id}" }
        val case =
            Case(
                id = saved.metadata.id,
                projectId = entity.projectId,
                agentService = agentService,
                caseService = this,
                caseEventService = caseEventService,
                inputEvents = emptyList(),
                caseModel = saved,
            )
        activeCases[case.id] = case
        logger.info { "[CaseService] Case created and registered: ${case.id} for project ${entity.projectId}" }
        return saved
    }

    override fun update(entity: CaseModel): CaseModel {
        val saved = caseRepository.save(entity)
        activeCases[saved.id]?.updateModel(saved)
        logger.debug { "[CaseService] CaseModel updated: ${saved.metadata.id} status=${saved.status}" }
        return saved
    }

    override fun findByIds(ids: Collection<UUID>): List<CaseModel> = caseRepository.findByIds(ids)

    override fun findByParent(parentId: UUID): List<CaseModel> = caseRepository.findByParent(parentId)

    override fun delete(id: UUID): Boolean {
        // Stop any active runtime instance before deleting
        activeCases[id]?.let { case ->
            logger.info { "Stopping case $id before deletion" }
            case.stop()
            activeCases.remove(id)
        }
        return caseRepository.delete(id)
    }

    override fun deleteByParent(parentId: UUID): Int {
        // Find all cases for this project
        val cases = findByParent(parentId)
        // Stop any active runtime instances before deleting
        cases.forEach { caseModel ->
            activeCases[caseModel.id]?.let { case ->
                logger.info { "Stopping case ${caseModel.id} before deletion (parent $parentId)" }
                case.stop()
                activeCases.remove(caseModel.id)
            }
        }
        return caseRepository.deleteByParent(parentId)
    }

    override fun getCaseInstance(caseId: UUID): Case? {
        val case = activeCases[caseId]
        if (case == null) {
            logger.warn { "[CaseService] Case instance not found: $caseId (active cases: ${activeCases.keys})" }
        } else {
            logger.debug { "[CaseService] Retrieved case instance: $caseId" }
        }
        return case
    }

    override fun getActiveCasesByProject(projectId: UUID): List<Case> = activeCases.values.filter { it.projectId == projectId }

    override fun getAllActiveCases(): List<Case> = activeCases.values.toList()

    // ========================================
    // Event Stream Access
    // ========================================

    override fun getCaseEventStream(caseId: UUID): SharedFlow<CaseEvent>? {
        val case = activeCases[caseId]
        if (case == null) {
            logger.warn { "[CaseService] Cannot get event stream - case not found: $caseId" }
        } else {
            logger.debug { "[CaseService] Event stream retrieved for case: $caseId" }
        }
        return case?.events
    }

    // ========================================
    // Execution Control
    // ========================================

    override fun stopCase(caseId: UUID): Boolean =
        activeCases[caseId]?.let { case ->
            logger.info { "Stopping case: $caseId" }
            case.stop()
            true
        } ?: run {
            logger.warn { "Attempted to stop non-existent case: $caseId" }
            false
        }

    override fun killCase(caseId: UUID): Boolean =
        activeCases[caseId]?.let { case ->
            logger.info { "Killing case: $caseId" }
            runBlocking {
                case.kill()
            }
            activeCases.remove(caseId)
            true
        } ?: run {
            logger.warn { "Attempted to kill non-existent case: $caseId" }
            false
        }

    /**
     * Shutdown the case service and cleanup all resources
     */
    @PreDestroy
    fun shutdown() {
        logger.info { "Shutting down CaseService..." }

        // Stop all active cases
        activeCases.keys.forEach { caseId ->
            stopCase(caseId)
        }

        // Cancel all coroutines
        scope.cancel()

        // Shutdown executor
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
