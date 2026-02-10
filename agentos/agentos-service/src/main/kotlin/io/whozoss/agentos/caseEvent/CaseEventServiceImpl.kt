package io.whozoss.agentos.caseEvent

import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Service for managing CaseEvent entities with business logic.
 *
 * Responsibilities:
 * - Validation of events before persistence
 * - Business rules enforcement (e.g., event ordering, consistency checks)
 * - Coordination with other services if needed
 * - Delegation to repository for persistence
 *
 * This layer separates business logic from pure persistence concerns.
 */
@Service
class CaseEventServiceImpl(
    private val repository: CaseEventRepository,
) : CaseEventService {
    private val logger = LoggerFactory.getLogger(CaseEventServiceImpl::class.java)

    /**
     * Save an event with validation.
     * Ensures events are valid before persisting.
     */
    override fun save(entity: CaseEvent): CaseEvent {
        // Future: Add validation logic here
        // - Check event integrity
        // - Validate relationships (case exists, etc.)
        // - Enforce business rules

        return repository.save(entity)
    }

    /**
     * Find multiple events by their IDs.
     */
    override fun findByIds(ids: Collection<UUID>): List<CaseEvent> = repository.findByIds(ids)

    /**
     * Find all events belonging to a case, ordered by timestamp.
     */
    override fun findByParent(parentId: UUID): List<CaseEvent> = repository.findByParent(parentId)

    /**
     * Delete a single event.
     * Future: May need to validate deletion rules (e.g., prevent deletion of certain event types).
     */
    override fun delete(id: UUID): Boolean {
        // Future: Add business logic here
        // - Check if event can be deleted
        // - Emit domain events

        return repository.delete(id)
    }

    /**
     * Delete all events belonging to a parent (case).
     * Useful for cascade deletion when a case is removed.
     */
    override fun deleteByParent(parentId: UUID): Int = repository.deleteByParent(parentId)
}
