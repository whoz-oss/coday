
package io.biznet.agentos.orchestration

import io.biznet.agentos.agents.service.AgentRegistry
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// todo: focus on getting status of cases and prepare replay and observability (jmx)

/**
 * Service responsible for managing the lifecycle of cases.
 * Handles case creation, execution threading, and SSE broadcasting.
 */
@Service
class CaseService(
    private val agentRegistry: AgentRegistry,
) {
    private val logger = LoggerFactory.getLogger(CaseService::class.java)

    // Thread pool for executing cases
    private val executor: ExecutorService =
        Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "case-executor").apply {
                isDaemon = true
            }
        }

    // Coroutine scope for flow collectors
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Active cases indexed by ID
    private val activeCases = ConcurrentHashMap<String, Case>()

    /**
     * Get an active case by ID
     * @param caseId The case identifier
     * @return The case if active, null otherwise
     */
    fun getCase(caseId: String): Case? = activeCases[caseId]

    /**
     * Stop a running case
     * @param caseId The case identifier
     */
    fun stopCase(caseId: String) {
        activeCases[caseId]?.let { case ->
            logger.info("Stopping case: $caseId")
            case.stop()
        } ?: logger.warn("Attempted to stop non-existent case: $caseId")
    }

    /**
     * Check if a case is currently running
     * @param caseId The case identifier
     * @return true if the case is active
     */
    fun isCaseRunning(caseId: String): Boolean = activeCases.containsKey(caseId)

    /**
     * Get all active case IDs
     * @return Set of active case IDs
     */
    fun getActiveCaseIds(): Set<String> = activeCases.keys.toSet()

    /**
     * Create an SSE emitter for a case
     * The emitter will receive all events from the case via SharedFlow
     *
     * @param caseId The case identifier
     * @return A new SseEmitter instance
     */
    fun createSseEmitter(caseId: String): SseEmitter {
        val emitter = SseEmitter(0L) // Infinite timeout

        val case =
            activeCases[caseId]
                ?: throw IllegalArgumentException("Case $caseId not found")

        logger.debug("SSE emitter created for case $caseId")

        // Collect events from the SharedFlow in a coroutine
        val collectorJob =
            scope.launch {
                case.events
                    .catch { error ->
                        logger.error("Error in event stream for case $caseId", error)
                        emitter.completeWithError(error)
                    }.collect { event ->
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
