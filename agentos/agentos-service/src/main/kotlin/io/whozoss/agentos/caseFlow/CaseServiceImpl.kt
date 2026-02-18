package io.whozoss.agentos.caseFlow

import io.whozoss.agentos.agent.AgentService
import io.whozoss.agentos.caseEvent.CaseEventService
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.entity.EntityMetadata
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
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
    private val logger = LoggerFactory.getLogger(CaseServiceImpl::class.java)

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

    override fun save(entity: CaseModel): CaseModel {
        val saved = caseRepository.save(entity)
        // Initialize the runtime Case instance if not already active
        if (!activeCases.containsKey(saved.id)) {
            val case = Case(
                id = saved.id,
                projectId = saved.projectId,
                status = saved.status,
                agentService = agentService,
                caseService = this,
                caseEventService = caseEventService,
                inputEvents = emptyList(),
            )
            activeCases[case.id] = case
            logger.info("[CaseService] Runtime instance created for case: ${saved.id}")
        }
        return saved
    }

    override fun findByIds(ids: Collection<UUID>): List<CaseModel> = caseRepository.findByIds(ids)

    override fun findByParent(parentId: UUID): List<CaseModel> = caseRepository.findByParent(parentId)

    override fun delete(id: UUID): Boolean {
        // Stop any active runtime instance before deleting
        activeCases[id]?.let { case ->
            logger.info("Stopping case $id before deletion")
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
                logger.info("Stopping case ${caseModel.id} before deletion (parent $parentId)")
                case.stop()
                activeCases.remove(caseModel.id)
            }
        }
        return caseRepository.deleteByParent(parentId)
    }

    // ========================================
    // Runtime Instance Management
    // ========================================

    override suspend fun addMessage(
        caseId: UUID,
        userId: String,
        content: String,
        answerToEventId: UUID?,
    ) {
        val case = getCaseInstance(caseId)
            ?: throw IllegalArgumentException("Case not found: $caseId")

        val actor = Actor(
            id = userId,
            displayName = userId,
            role = ActorRole.USER,
        )

        case.addUserMessage(
            actor = actor,
            content = listOf(MessageContent.Text(content)),
            answerToEventId = answerToEventId,
        )
    }

    override fun getCaseInstance(caseId: UUID): Case? {
        val case = activeCases[caseId]
        if (case == null) {
            logger.warn("[CaseService] Case instance not found: $caseId (active cases: ${activeCases.keys})")
        } else {
            logger.debug("[CaseService] Retrieved case instance: $caseId")
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
            logger.warn("[CaseService] Cannot get event stream - case not found: $caseId")
        } else {
            logger.debug("[CaseService] Event stream retrieved for case: $caseId")
        }
        return case?.events
    }

    // ========================================
    // Execution Control
    // ========================================

    override fun stopCase(caseId: UUID): Boolean =
        activeCases[caseId]?.let { case ->
            logger.info("Stopping case: $caseId")
            case.stop()
            true
        } ?: run {
            logger.warn("Attempted to stop non-existent case: $caseId")
            false
        }

    override fun killCase(caseId: UUID): Boolean =
        activeCases[caseId]?.let { case ->
            logger.info("Killing case: $caseId")
            runBlocking {
                case.kill()
            }
            activeCases.remove(caseId)
            true
        } ?: run {
            logger.warn("Attempted to kill non-existent case: $caseId")
            false
        }

    // ========================================
    // Legacy/Helper Methods
    // ========================================

    /**
     * Check if a case is currently running
     * @param caseId The case identifier
     * @return true if the case is active
     */
    fun isCaseRunning(caseId: UUID): Boolean = activeCases.containsKey(caseId)

    /**
     * Get all active case IDs
     * @return Set of active case IDs
     */
    fun getActiveCaseIds(): Set<UUID> = activeCases.keys.toSet()

    /**
     * Create an SSE emitter for a case.
     * The emitter will receive all events from the case via SharedFlow.
     *
     * @param caseId The case identifier
     * @return A new SseEmitter instance
     */
    fun createSseEmitter(caseId: UUID): SseEmitter {
        val emitter = SseEmitter(0L) // Infinite timeout

        val case =
            activeCases[caseId]
                ?: throw IllegalArgumentException("Case $caseId not found")

        logger.debug("SSE emitter created for case $caseId")

        // Collect events from the SharedFlow in a coroutine
        val collectorJob =
            scope.launch {
                try {
                    case.events.collect { event ->
                        try {
                            emitter.send(
                                SseEmitter
                                    .event()
                                    .id(event.timestamp.toString())
                                    .name(event::class.simpleName!!)
                                    .data(event),
                            )
                            logger.trace("Event ${event::class.simpleName} sent to SSE for case $caseId")
                        } catch (e: Exception) {
                            logger.debug("Failed to send event to SSE for case $caseId: ${e.message}")
                            throw e // Stop collecting
                        }
                    }
                } catch (error: Exception) {
                    logger.error("Error in event stream for case $caseId", error)
                    emitter.completeWithError(error)
                }
            }

        // Setup cleanup handlers
        emitter.onCompletion {
            logger.debug("SSE emitter completed for case $caseId")
            collectorJob.cancel()
        }

        emitter.onTimeout {
            logger.debug("SSE emitter timed out for case $caseId")
            collectorJob.cancel()
        }

        emitter.onError { throwable ->
            logger.warn("SSE emitter error for case $caseId: ${throwable.message}")
            collectorJob.cancel()
        }

        return emitter
    }

    /**
     * Shutdown the case service and cleanup all resources
     */
    @PreDestroy
    fun shutdown() {
        logger.info("Shutting down CaseService...")

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
                logger.warn("Executor did not terminate in time, forcing shutdown")
                executor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            logger.error("Interrupted while waiting for executor shutdown", e)
            executor.shutdownNow()
            Thread.currentThread().interrupt()
        }

        logger.info("CaseService shutdown complete")
    }
}
