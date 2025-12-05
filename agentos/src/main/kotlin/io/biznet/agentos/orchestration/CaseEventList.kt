package io.biznet.agentos.orchestration

import org.slf4j.LoggerFactory
import java.util.UUID

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

/**
 * Default in-memory implementation of CaseEventList.
 * Maintains events sorted by timestamp for efficient chronological access.
 * Also maintains an index by ID for O(1) lookups.
 * This is runtime state only - persistence is handled by ICaseEventService.
 */
class InMemoryCaseEventList(
    inputEvents: List<CaseEvent> = emptyList(),
) : CaseEventList {
    private val logger = LoggerFactory.getLogger(InMemoryCaseEventList::class.java)

    /**
     * List sorted by timestamp of the events. Sort order must be maintained.
     */
    private val events = inputEvents.sortedBy { it.timestamp }.toMutableList()

    /**
     * Map of events by ID for O(1) lookups.
     */
    private val eventsById = inputEvents.associateBy { it.id }.toMutableMap()

    /**
     * Add an event to the list, maintaining chronological order.
     * Events are inserted in chronological order (sorted by timestamp).
     * Starts searching from the end for efficiency (most recent events are typically added).
     */
    override fun add(event: CaseEvent) {
        // Find the index at which to insert the event, starting by the end
        // Events are sorted by timestamp (ascending order)
        var insertIndex = events.size
        for (i in events.lastIndex downTo 0) {
            if (events[i].timestamp <= event.timestamp) {
                insertIndex = i + 1
                break
            }
            insertIndex = i
        }
        events.add(insertIndex, event)
        eventsById[event.id] = event
        logger.debug("[Case ${event.caseId}] Event added at index $insertIndex: ${event::class.simpleName}")
    }

    /**
     * Get all events in the list (read-only view).
     */
    override fun getAll(): List<CaseEvent> = events.toList()

    /**
     * Get an event by its ID.
     * @return The event if found, null otherwise.
     */
    override fun getById(id: UUID): CaseEvent? = eventsById[id]
}
