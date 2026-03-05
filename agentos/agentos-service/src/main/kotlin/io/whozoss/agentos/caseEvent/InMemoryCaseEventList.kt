package io.whozoss.agentos.caseEvent

import io.whozoss.agentos.orchestration.CaseEventList
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import mu.KLogging
import java.util.*
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Thread-safe in-memory implementation of [CaseEventList].
 *
 * Maintains events sorted by timestamp for efficient chronological access
 * and an ID index for O(1) lookups.
 *
 * Thread safety: a [ReentrantReadWriteLock] allows concurrent reads while
 * serializing writes. This is important because [add] may be called from
 * the HTTP thread (via [io.whozoss.agentos.caseFlow.CaseRuntime.addUserMessage])
 * concurrently with [getAll] being called from the coroutine running
 * [io.whozoss.agentos.caseFlow.CaseRuntime.run].
 */
class InMemoryCaseEventList(
    inputEvents: List<CaseEvent> = emptyList(),
) : CaseEventList {
    private val lock = ReentrantReadWriteLock()
    private val events = inputEvents.sortedBy { it.timestamp }.toMutableList()
    private val eventsById = inputEvents.associateBy { it.id }.toMutableMap()

    /**
     * Add an event, maintaining chronological (timestamp-ascending) order.
     * Inserts from the end for efficiency since new events are typically the most recent.
     */
    override fun add(event: CaseEvent): Unit =
        lock.write {
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
            logger.debug { "[Case ${event.caseId}] Event added at index $insertIndex: ${event::class.simpleName}" }
        }

    override fun getAll(): List<CaseEvent> = lock.read { events.toList() }

    override fun getById(id: UUID): CaseEvent? = lock.read { eventsById[id] }

    companion object : KLogging()
}
