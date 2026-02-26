package io.whozoss.agentos.caseEvent

import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import mu.KLogging
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
    override fun create(entity: CaseEvent): CaseEvent = repository.save(entity)

    override fun update(entity: CaseEvent): CaseEvent = repository.save(entity)

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

    companion object : KLogging()
}
