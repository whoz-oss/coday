package io.whozoss.agentos.caseFlow

import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.entity.EntityService
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
interface CaseService : EntityService<CaseModel, UUID> {
    // ========================================
    // Runtime Instance Management
    // ========================================

    /**
     * Save a CaseModel, creating both the persistent entity and the runtime Case instance.
     * Overrides EntityService::save to also initialize the runtime.
     *
     * @param entity The CaseModel to persist and start
     * @return The saved CaseModel
     */
    override fun save(entity: CaseModel): CaseModel

    /**
     * Retrieve an active case runtime instance by ID.
     *
     * @param caseId The unique identifier of the case
     * @return The Case instance, or null if not found or not active
     */
    fun getCaseInstance(caseId: UUID): Case?

    /**
     * Add a user message to a case, encapsulating actor creation and delegation to the runtime.
     *
     * @param caseId The case to add the message to
     * @param userId The user identifier
     * @param content The message text
     * @param answerToEventId Optional ID of the question event this answers
     */
    suspend fun addMessage(
        caseId: UUID,
        userId: String,
        content: String,
        answerToEventId: UUID? = null,
    )

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
