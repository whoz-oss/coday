package io.whozoss.agentos.caseFlow

import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.sdk.entity.EntityService
import java.util.UUID

/**
 * Service for managing Case instances and their lifecycle.
 *
 * Extends EntityService for persistence operations (CaseModel).
 * Adds runtime management for active Case instances.
 *
 * Responsibilities:
 * - Persist case metadata (via EntityService methods)
 * - Manage active Case runtime instances
 * - Expose event streams from Cases for consumption by controllers
 * - Coordinate Case execution (start, stop, kill)
 *
 * This service acts as a bridge between the HTTP layer (controllers)
 * and the Case runtime, managing both persistence and instance lifecycle.
 *
 * Parent type is UUID representing the projectId.
 */
interface CaseService : EntityService<CaseModel, UUID> {
    // ========================================
    // Runtime Instance Management
    // ========================================

    /**
     * Retrieve an active case runtime instance by ID.
     *
     * @param caseId The unique identifier of the case
     * @return The active Case instance
     * @throws ResourceNotFoundException if no active instance exists for [caseId]
     */
    fun getCaseInstance(caseId: UUID): Case

    /**
     * Retrieve all active case instances for a given project.
     */
    fun getActiveCasesByProject(projectId: UUID): List<Case>

    /**
     * Retrieve all active case instances.
     */
    fun getAllActiveCases(): List<Case>

    // ========================================
    // Execution Control
    // ========================================

    /**
     * Request a case to stop gracefully.
     * Preserves case state and allows clean completion of current operation.
     *
     * @param caseId The unique identifier of the case to stop
     * @throws ResourceNotFoundException if no active instance exists for [caseId]
     */
    fun stopCase(caseId: UUID)

    /**
     * Immediately terminate a case and cleanup resources.
     * Unlike stop(), this method does not preserve state.
     *
     * @param caseId The unique identifier of the case to kill
     * @throws ResourceNotFoundException if no active instance exists for [caseId]
     */
    fun killCase(caseId: UUID)
}
