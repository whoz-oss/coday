package io.biznet.agentos.orchestration

import io.biznet.agentos.common.EntityService
import kotlinx.coroutines.flow.SharedFlow
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
interface ICaseService : EntityService<CaseModel, UUID> {
    // ========================================
    // Runtime Instance Management
    // ========================================

    /**
     * Create a new case instance for the given project.
     * This creates both the persistent CaseModel and the runtime Case instance.
     *
     * @param projectId The project this case belongs to
     * @param initialEvents Optional list of events to initialize the case with (for resumption)
     * @return The created Case instance (runtime)
     */
    fun createCaseInstance(
        projectId: UUID,
        initialEvents: List<CaseEvent> = emptyList(),
    ): Case

    /**
     * Retrieve an active case runtime instance by ID.
     *
     * @param caseId The unique identifier of the case
     * @return The Case instance, or null if not found or not active
     */
    fun getCaseInstance(caseId: UUID): Case?

    /**
     * Retrieve all active case instances for a given project.
     *
     * @param projectId The project ID to filter by
     * @return List of active Case instances belonging to the project
     */
    fun getActiveCasesByProject(projectId: UUID): List<Case>

    /**
     * Retrieve all active case instances.
     *
     * @return List of all active Case instances currently managed by the service
     */
    fun getAllActiveCases(): List<Case>

    // ========================================
    // Event Stream Access
    // ========================================

    /**
     * Get the event stream for a specific case.
     * This allows controllers to subscribe to case events and stream them to clients.
     *
     * @param caseId The unique identifier of the case
     * @return SharedFlow of CaseEvents, or null if case not found or not active
     */
    fun getCaseEventStream(caseId: UUID): SharedFlow<CaseEvent>?

    // ========================================
    // Execution Control
    // ========================================

    /**
     * Request a case to stop gracefully.
     * Preserves case state and allows clean completion of current operation.
     *
     * @param caseId The unique identifier of the case to stop
     * @return true if the case was found and stop was requested, false otherwise
     */
    fun stopCase(caseId: UUID): Boolean

    /**
     * Immediately terminate a case and cleanup resources.
     * Unlike stop(), this method does not preserve state.
     *
     * @param caseId The unique identifier of the case to kill
     * @return true if the case was found and killed, false otherwise
     */
    fun killCase(caseId: UUID): Boolean
}
