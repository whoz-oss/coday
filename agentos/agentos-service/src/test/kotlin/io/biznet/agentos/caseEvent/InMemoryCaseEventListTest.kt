package io.biznet.agentos.caseEvent

import io.biznet.agentos.sdk.model.Actor
import io.biznet.agentos.sdk.model.ActorRole
import io.biznet.agentos.sdk.model.MessageContent
import io.biznet.agentos.sdk.model.MessageEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.time.Instant
import java.util.*
import java.util.concurrent.TimeUnit

@Timeout(value = 5, unit = TimeUnit.SECONDS)
class InMemoryCaseEventListTest {
    private val projectId = UUID.randomUUID()
    private val caseId = UUID.randomUUID()

    @Test
    fun `should maintain chronological order when adding events in order`() {
        val eventList = InMemoryCaseEventList()

        val event1 = createMessageEvent(timestamp = Instant.ofEpochMilli(1000))
        val event2 = createMessageEvent(timestamp = Instant.ofEpochMilli(2000))
        val event3 = createMessageEvent(timestamp = Instant.ofEpochMilli(3000))

        eventList.add(event1)
        eventList.add(event2)
        eventList.add(event3)

        val events = eventList.getAll()
        assertEquals(3, events.size)
        assertEquals(event1, events[0])
        assertEquals(event2, events[1])
        assertEquals(event3, events[2])
    }

    @Test
    fun `should maintain chronological order when adding events out of order`() {
        val eventList = InMemoryCaseEventList()

        val event1 = createMessageEvent(timestamp = Instant.ofEpochMilli(1000))
        val event2 = createMessageEvent(timestamp = Instant.ofEpochMilli(2000))
        val event3 = createMessageEvent(timestamp = Instant.ofEpochMilli(3000))

        // Add in reverse order
        eventList.add(event3)
        eventList.add(event1)
        eventList.add(event2)

        val events = eventList.getAll()
        assertEquals(3, events.size)
        assertEquals(event1, events[0])
        assertEquals(event2, events[1])
        assertEquals(event3, events[2])
    }

    @Test
    fun `should handle events with same timestamp`() {
        val eventList = InMemoryCaseEventList()

        val timestamp = Instant.ofEpochMilli(1000)
        val event1 = createMessageEvent(timestamp = timestamp)
        val event2 = createMessageEvent(timestamp = timestamp)
        val event3 = createMessageEvent(timestamp = timestamp)

        eventList.add(event1)
        eventList.add(event2)
        eventList.add(event3)

        val events = eventList.getAll()
        assertEquals(3, events.size)
        // All should be present, order between same timestamps is insertion order
        assertEquals(event1, events[0])
        assertEquals(event2, events[1])
        assertEquals(event3, events[2])
    }

    @Test
    fun `should insert event in middle when timestamp is between existing events`() {
        val eventList = InMemoryCaseEventList()

        val event1 = createMessageEvent(timestamp = Instant.ofEpochMilli(1000))
        val event3 = createMessageEvent(timestamp = Instant.ofEpochMilli(3000))
        val event2 = createMessageEvent(timestamp = Instant.ofEpochMilli(2000))

        eventList.add(event1)
        eventList.add(event3)
        eventList.add(event2) // Should be inserted between event1 and event3

        val events = eventList.getAll()
        assertEquals(3, events.size)
        assertEquals(event1, events[0])
        assertEquals(event2, events[1])
        assertEquals(event3, events[2])
    }

    @Test
    fun `should initialize with input events in chronological order`() {
        val event1 = createMessageEvent(timestamp = Instant.ofEpochMilli(1000))
        val event2 = createMessageEvent(timestamp = Instant.ofEpochMilli(2000))
        val event3 = createMessageEvent(timestamp = Instant.ofEpochMilli(3000))

        // Initialize with events out of order
        val eventList = InMemoryCaseEventList(listOf(event3, event1, event2))

        val events = eventList.getAll()
        assertEquals(3, events.size)
        assertEquals(event1, events[0])
        assertEquals(event2, events[1])
        assertEquals(event3, events[2])
    }

    @Test
    fun `should handle empty list`() {
        val eventList = InMemoryCaseEventList()

        val events = eventList.getAll()
        assertEquals(0, events.size)
    }

    @Test
    fun `should return read-only copy of events`() {
        val eventList = InMemoryCaseEventList()
        val event = createMessageEvent(timestamp = Instant.ofEpochMilli(1000))

        eventList.add(event)
        val events1 = eventList.getAll()
        val events2 = eventList.getAll()

        // Should return different list instances
        assert(events1 !== events2)
        // But with same content
        assertEquals(events1, events2)
    }

    @Test
    fun `getById should return event when it exists`() {
        val eventList = InMemoryCaseEventList()
        val event1 = createMessageEvent(timestamp = Instant.ofEpochMilli(1000))
        val event2 = createMessageEvent(timestamp = Instant.ofEpochMilli(2000))
        val event3 = createMessageEvent(timestamp = Instant.ofEpochMilli(3000))

        eventList.add(event1)
        eventList.add(event2)
        eventList.add(event3)

        // Should find each event by ID
        assertEquals(event1, eventList.getById(event1.id))
        assertEquals(event2, eventList.getById(event2.id))
        assertEquals(event3, eventList.getById(event3.id))
    }

    @Test
    fun `getById should return null when event does not exist`() {
        val eventList = InMemoryCaseEventList()
        val event = createMessageEvent(timestamp = Instant.ofEpochMilli(1000))

        eventList.add(event)

        // Should return null for non-existent ID
        val nonExistentId = UUID.randomUUID()
        assertEquals(null, eventList.getById(nonExistentId))
    }

    @Test
    fun `getById should work with input events`() {
        val event1 = createMessageEvent(timestamp = Instant.ofEpochMilli(1000))
        val event2 = createMessageEvent(timestamp = Instant.ofEpochMilli(2000))

        // Initialize with events
        val eventList = InMemoryCaseEventList(listOf(event1, event2))

        // Should find events that were passed in constructor
        assertEquals(event1, eventList.getById(event1.id))
        assertEquals(event2, eventList.getById(event2.id))
    }

    @Test
    fun `getById should be O(1) efficient`() {
        val eventList = InMemoryCaseEventList()

        // Add many events
        val events =
            (1..1000).map { i ->
                createMessageEvent(timestamp = Instant.ofEpochMilli(i.toLong()))
            }
        events.forEach { eventList.add(it) }

        // Lookup should be fast even with many events
        val targetEvent = events[500]
        val startTime = System.nanoTime()
        val foundEvent = eventList.getById(targetEvent.id)
        val duration = System.nanoTime() - startTime

        assertEquals(targetEvent, foundEvent)
        // Should be very fast (less than 1ms even on slow machines)
        assert(duration < 1_000_000) { "Lookup took ${duration}ns, expected < 1ms" }
    }

    private fun createMessageEvent(timestamp: Instant): MessageEvent =
        MessageEvent(
            projectId = projectId,
            caseId = caseId,
            timestamp = timestamp,
            actor =
                Actor(
                    id = "test-user",
                    displayName = "Test User",
                    role = ActorRole.USER,
                ),
            content = listOf(MessageContent.Text("test message")),
        )
}
