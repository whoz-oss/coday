package io.whozoss.agentos.orchestration

import io.whozoss.agentos.sdk.caseEvent.CaseEvent
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

    /**
     * Remove the event [eventId] and every event after it (rewind).
     * @return The number of events removed, 0 (nothing changed) when the event is absent.
     */
    fun removeFrom(eventId: UUID): Int
}
