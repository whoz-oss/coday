package io.whozoss.agentos.orchestration

import io.whozoss.agentos.sdk.model.CaseEvent
import java.util.*

/**
 * Interface for maintaining an ordered list of case events in memory.
 * This is NOT a persistence layer - it's a runtime data structure.
 * For persistence, use ICaseEventService.save().
 */
interface CaseEventList {
    /**
     * Add an event to the list, maintaining chronological order (sorted by timestamp).
     */
    fun add(event: CaseEvent)

    /**
     * Get all events in the list (read-only view).
     */
    fun getAll(): List<CaseEvent>

    /**
     * Get an event by its ID.
     * @return The event if found, null otherwise.
     */
    fun getById(id: UUID): CaseEvent?
}
