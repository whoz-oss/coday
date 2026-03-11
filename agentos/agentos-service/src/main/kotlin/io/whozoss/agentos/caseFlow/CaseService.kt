package io.whozoss.agentos.caseFlow

import io.whozoss.agentos.entity.EntityService
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.sdk.actor.Actor
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
     * Use this only when you intend to interact with the case (send a message, stop it).
     *
     * @param caseId The unique identifier of the case
     * @return The active [CaseRuntime] instance
     * @throws ResourceNotFoundException if no persisted [Case] exists for [caseId]
     */
    fun getCaseRuntime(caseId: UUID): CaseRuntime

    /**
     * Return the live [CaseRuntime] for [caseId] if one exists in memory, or null.
     * Never rehydrates from persistence — safe to call for observation purposes
     * (e.g. SSE streaming) without creating unintended side effects.
     */
    fun findActiveRuntime(caseId: UUID): CaseRuntime?

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
        actor: Actor,
        content: List<io.whozoss.agentos.sdk.caseEvent.MessageContent>,
        answerToEventId: UUID? = null,
    )

    // ========================================
    // Execution Control
    // ========================================

    /**
     * Interrupt the current agent turn and return the case to
     * [io.whozoss.agentos.sdk.caseFlow.CaseStatus.IDLE].
     *
     * The runtime stays alive and the SSE connection stays open, so the user can
     * immediately send a corrective message. Use this when the agent is going in the
     * wrong direction and you want to redirect it without ending the conversation.
     *
     * Note: if the agent is mid-LLM-stream the interrupt takes effect after the
     * current stream completes. The next iteration of the run loop is what is
     * prevented.
     *
     * @param caseId The unique identifier of the case to interrupt
     * @throws ResourceNotFoundException if no active runtime exists for [caseId]
     */
    fun interruptCase(caseId: UUID)

    /**
     * Permanently terminate a case and clean up its runtime.
     * Sets the case status to [io.whozoss.agentos.sdk.caseFlow.CaseStatus.KILLED],
     * evicts the runtime from memory, and allows the SSE connection to close.
     *
     * Note: between agent turns the case is in [io.whozoss.agentos.sdk.caseFlow.CaseStatus.IDLE]
     * with its runtime still alive — that is the normal resting state, not a stopped one.
     * Only call this when the conversation should be permanently ended.
     *
     * @param caseId The unique identifier of the case to kill
     */
    fun killCase(caseId: UUID)
}
