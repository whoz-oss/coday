package io.whozoss.agentos.caseFlow

import io.whozoss.agentos.agent.AgentService
import io.whozoss.agentos.caseEvent.CaseEventService
import io.whozoss.agentos.exception.ResourceNotFoundException
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import mu.KLogging
import org.springframework.stereotype.Service
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Service
class CaseServiceImpl(
    private val agentService: AgentService,
    private val caseRepository: CaseRepository,
    private val caseEventService: CaseEventService,
) : CaseService {
    private val executor: ExecutorService =
        Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "case-executor").apply { isDaemon = true }
        }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeCases = ConcurrentHashMap<UUID, Case>()

    // ========================================
    // EntityService
    // ========================================

    override fun create(entity: CaseModel): CaseModel {
        val saved = caseRepository.save(CaseModel(metadata = entity.metadata, projectId = entity.projectId))
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
        logger.info { "[CaseService] Case created: ${case.id} for project ${entity.projectId}" }
        return saved
    }

    override fun update(entity: CaseModel): CaseModel {
        val saved = caseRepository.save(entity)
        activeCases[saved.id]?.updateModel(saved)
        return saved
    }

    override fun findByIds(ids: Collection<UUID>): List<CaseModel> = caseRepository.findByIds(ids)

    override fun findByParent(parentId: UUID): List<CaseModel> = caseRepository.findByParent(parentId)

    override fun delete(id: UUID): Boolean {
        activeCases.remove(id)?.stop()
        return caseRepository.delete(id)
    }

    override fun deleteByParent(parentId: UUID): Int {
        findByParent(parentId).forEach { activeCases.remove(it.id)?.stop() }
        return caseRepository.deleteByParent(parentId)
    }

    // ========================================
    // Runtime Instance Management
    // ========================================

    override fun getCaseInstance(caseId: UUID): Case =
        activeCases[caseId]
            ?: throw ResourceNotFoundException("No active case instance found: $caseId")

    override fun getActiveCasesByProject(projectId: UUID): List<Case> = activeCases.values.filter { it.projectId == projectId }

    override fun getAllActiveCases(): List<Case> = activeCases.values.toList()

    // ========================================
    // Execution Control
    // ========================================

    override fun stopCase(caseId: UUID) {
        val case =
            activeCases[caseId]
                ?: throw ResourceNotFoundException("No active case instance found: $caseId")
        logger.info { "Stopping case: $caseId" }
        case.stop()
    }

    override fun killCase(caseId: UUID) {
        val case =
            activeCases.remove(caseId)
                ?: throw ResourceNotFoundException("No active case instance found: $caseId")
        logger.info { "Killing case: $caseId" }
        runBlocking { case.kill() }
    }

    // ========================================
    // Lifecycle
    // ========================================

    @PreDestroy
    fun shutdown() {
        logger.info { "Shutting down CaseService..." }
        // Iterate activeCases directly — stopCase throws for missing IDs so we don't use it here
        activeCases.values.forEach { it.stop() }
        activeCases.clear()
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
