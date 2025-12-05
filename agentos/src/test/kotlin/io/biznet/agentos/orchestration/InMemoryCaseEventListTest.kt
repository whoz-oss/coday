package io.biznet.agentos.orchestration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

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

    private fun createMessageEvent(timestamp: Instant): MessageEvent {
        return MessageEvent(
            projectId = projectId,
            caseId = caseId,
            timestamp = timestamp,
            actor = Actor(
                id = "test-user",
                displayName = "Test User",
                role = ActorRole.USER
            ),
            content = listOf(MessageContent.Text("test message")),
        )
    }
}
