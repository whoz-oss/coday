package io.whozoss.agentos.caseEvent

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import java.time.Instant
import java.util.UUID

class InMemoryCaseEventListTest :
    StringSpec({
        timeout = 5000

        val projectId = UUID.randomUUID()
        val caseId = UUID.randomUUID()

        fun createMessageEvent(timestamp: Instant): MessageEvent =
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

        "should maintain chronological order when adding events in order" {
            val eventList = InMemoryCaseEventList()

            val event1 = createMessageEvent(timestamp = Instant.ofEpochMilli(1000))
            val event2 = createMessageEvent(timestamp = Instant.ofEpochMilli(2000))
            val event3 = createMessageEvent(timestamp = Instant.ofEpochMilli(3000))

            eventList.add(event1)
            eventList.add(event2)
            eventList.add(event3)

            val events = eventList.getAll()
            events shouldHaveSize 3
            events[0] shouldBe event1
            events[1] shouldBe event2
            events[2] shouldBe event3
        }

        "should maintain chronological order when adding events out of order" {
            val eventList = InMemoryCaseEventList()

            val event1 = createMessageEvent(timestamp = Instant.ofEpochMilli(1000))
            val event2 = createMessageEvent(timestamp = Instant.ofEpochMilli(2000))
            val event3 = createMessageEvent(timestamp = Instant.ofEpochMilli(3000))

            // Add in reverse order
            eventList.add(event3)
            eventList.add(event1)
            eventList.add(event2)

            val events = eventList.getAll()
            events shouldHaveSize 3
            events[0] shouldBe event1
            events[1] shouldBe event2
            events[2] shouldBe event3
        }

        "should handle events with same timestamp" {
            val eventList = InMemoryCaseEventList()

            val timestamp = Instant.ofEpochMilli(1000)
            val event1 = createMessageEvent(timestamp = timestamp)
            val event2 = createMessageEvent(timestamp = timestamp)
            val event3 = createMessageEvent(timestamp = timestamp)

            eventList.add(event1)
            eventList.add(event2)
            eventList.add(event3)

            val events = eventList.getAll()
            events shouldHaveSize 3
            // All should be present, order between same timestamps is insertion order
            events[0] shouldBe event1
            events[1] shouldBe event2
            events[2] shouldBe event3
        }

        "should insert event in middle when timestamp is between existing events" {
            val eventList = InMemoryCaseEventList()

            val event1 = createMessageEvent(timestamp = Instant.ofEpochMilli(1000))
            val event3 = createMessageEvent(timestamp = Instant.ofEpochMilli(3000))
            val event2 = createMessageEvent(timestamp = Instant.ofEpochMilli(2000))

            eventList.add(event1)
            eventList.add(event3)
            eventList.add(event2) // Should be inserted between event1 and event3

            val events = eventList.getAll()
            events shouldHaveSize 3
            events[0] shouldBe event1
            events[1] shouldBe event2
            events[2] shouldBe event3
        }

        "should initialize with input events in chronological order" {
            val event1 = createMessageEvent(timestamp = Instant.ofEpochMilli(1000))
            val event2 = createMessageEvent(timestamp = Instant.ofEpochMilli(2000))
            val event3 = createMessageEvent(timestamp = Instant.ofEpochMilli(3000))

            // Initialize with events out of order
            val eventList = InMemoryCaseEventList(listOf(event3, event1, event2))

            val events = eventList.getAll()
            events shouldHaveSize 3
            events[0] shouldBe event1
            events[1] shouldBe event2
            events[2] shouldBe event3
        }

        "should handle empty list" {
            val eventList = InMemoryCaseEventList()

            val events = eventList.getAll()
            events shouldHaveSize 0
        }

        "should return read-only copy of events" {
            val eventList = InMemoryCaseEventList()
            val event = createMessageEvent(timestamp = Instant.ofEpochMilli(1000))

            eventList.add(event)
            val events1 = eventList.getAll()
            val events2 = eventList.getAll()

            // Should return different list instances (using reference equality)
            (events1 !== events2) shouldBe true
            // But with same content
            events1 shouldBe events2
        }

        "getById should return event when it exists" {
            val eventList = InMemoryCaseEventList()
            val event1 = createMessageEvent(timestamp = Instant.ofEpochMilli(1000))
            val event2 = createMessageEvent(timestamp = Instant.ofEpochMilli(2000))
            val event3 = createMessageEvent(timestamp = Instant.ofEpochMilli(3000))

            eventList.add(event1)
            eventList.add(event2)
            eventList.add(event3)

            // Should find each event by ID
            eventList.getById(event1.id) shouldBe event1
            eventList.getById(event2.id) shouldBe event2
            eventList.getById(event3.id) shouldBe event3
        }

        "getById should return null when event does not exist" {
            val eventList = InMemoryCaseEventList()
            val event = createMessageEvent(timestamp = Instant.ofEpochMilli(1000))

            eventList.add(event)

            // Should return null for non-existent ID
            val nonExistentId = UUID.randomUUID()
            eventList.getById(nonExistentId) shouldBe null
        }

        "getById should work with input events" {
            val event1 = createMessageEvent(timestamp = Instant.ofEpochMilli(1000))
            val event2 = createMessageEvent(timestamp = Instant.ofEpochMilli(2000))

            // Initialize with events
            val eventList = InMemoryCaseEventList(listOf(event1, event2))

            // Should find events that were passed in constructor
            eventList.getById(event1.id) shouldBe event1
            eventList.getById(event2.id) shouldBe event2
        }

        "getById should be O(1) efficient" {
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

            foundEvent shouldBe targetEvent
            // Should be very fast (less than 1ms even on slow machines)
            duration shouldBeLessThan 1_000_000L
        }
    })
