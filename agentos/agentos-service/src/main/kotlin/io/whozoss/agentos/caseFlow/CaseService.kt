package io.whozoss.agentos.caseFlow

import io.whozoss.agentos.entity.EntityService
import io.whozoss.agentos.exception.ResourceNotFoundException
import java.util.UUID

/**
 * Service for managing cases and their runtime lifecycle.
 *
 * Extends [EntityService] for persistence operations on [Case] (the data model).
 * Adds runtime management for active [CaseRuntime] instances.
 *
 * Responsibilities:
 * - Persist case metadata via [EntityService] methods
 * - Build and manage active [CaseRuntime] instances
 * - Expose event streams from runtimes for consumption by controllers
 * - Coordinate case execution (stop, kill)
 *
 * This service acts as the bridge between the HTTP layer and the [CaseRuntime],
 * owning all business logic (persistence, status transitions, event storage).
 *
 * Parent type is UUID representing the projectId.
 */
interface CaseService : EntityService<Case, UUID> {
    // ========================================
    // Runtime Instance Management
    // ========================================

    /**
     * Retrieve an active [CaseRuntime] instance by ID.
     * Rehydrates from persistence if no live instance exists.
     *
     * @param caseId The unique identifier of the case
     * @return The active [CaseRuntime] instance
     * @throws ResourceNotFoundException if no persisted [Case] exists for [caseId]
     */
    fun getCaseRuntime(caseId: UUID): CaseRuntime

    /**
     * Retrieve all active [CaseRuntime] instances for a given project.
     */
    fun getActiveCasesByProject(projectId: UUID): List<CaseRuntime>

    /**
     * Retrieve all active [CaseRuntime] instances.
     */
    fun getAllActiveCases(): List<CaseRuntime>

    // ========================================
    // Message handling
    // ========================================

    /**
     * Store a user message on the case and launch the execution loop in the background.
     * Returns immediately — the caller is never blocked by agent execution.
     */
    fun addMessage(
        caseId: UUID,
        actor: io.whozoss.agentos.sdk.actor.Actor,
        content: List<io.whozoss.agentos.sdk.caseEvent.MessageContent>,
        answerToEventId: UUID? = null,
    )

    // ========================================
    // Execution Control
    // ========================================

    /**
     * Request a case to stop gracefully.
     * Preserves case state and allows clean completion of the current operation.
     *
     * @param caseId The unique identifier of the case to stop
     * @throws ResourceNotFoundException if no active runtime exists for [caseId]
     */
    fun stopCase(caseId: UUID)

    /**
     * Immediately terminate a case and cleanup resources.
     * Unlike [stopCase], this method does not preserve state.
     *
     * @param caseId The unique identifier of the case to kill
     * @throws ResourceNotFoundException if no active runtime exists for [caseId]
     */
    fun killCase(caseId: UUID)
}
