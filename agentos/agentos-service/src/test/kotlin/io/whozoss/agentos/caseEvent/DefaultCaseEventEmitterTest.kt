package io.whozoss.agentos.caseEvent

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.whozoss.agentos.sdk.actor.*
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.CaseStatusEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.ThinkingEvent
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.entity.EntityMetadata
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import java.time.Instant
import java.util.*

class DefaultCaseEventEmitterTest : StringSpec({
    timeout = 5000

    val projectId = UUID.randomUUID()
    val caseId = UUID.randomUUID()

    fun createMessageEvent(timestamp: Instant = Instant.now()): MessageEvent =
        MessageEvent(
            metadata = EntityMetadata(),
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

    "should emit events to collectors" {
        val emitter = DefaultCaseEventEmitter()
        val event = createMessageEvent()

        // Start collecting in a coroutine
        val collectedEvents = mutableListOf<CaseEvent>()
        val job =
            launch {
                emitter.events.take(1).toList(collectedEvents)
            }

        // Give collector time to start
        delay(100)

        // Emit event
        emitter.emit(event)

        // Wait for collection to complete
        job.join()

        collectedEvents shouldHaveSize 1
        collectedEvents[0] shouldBe event
    }

    "should emit multiple events in order" {
        val emitter = DefaultCaseEventEmitter()
        val event1 = createMessageEvent(timestamp = Instant.ofEpochMilli(1000))
        val event2 = createMessageEvent(timestamp = Instant.ofEpochMilli(2000))
        val event3 = createMessageEvent(timestamp = Instant.ofEpochMilli(3000))

        val collectedEvents = mutableListOf<CaseEvent>()
        val job =
            launch {
                emitter.events.take(3).toList(collectedEvents)
            }

        delay(100)

        emitter.emit(event1)
        emitter.emit(event2)
        emitter.emit(event3)

        job.join()

        collectedEvents shouldHaveSize 3
        collectedEvents[0] shouldBe event1
        collectedEvents[1] shouldBe event2
        collectedEvents[2] shouldBe event3
    }

    "should support multiple collectors" {
        val emitter = DefaultCaseEventEmitter()
        val event = createMessageEvent()

        val collector1Events = mutableListOf<CaseEvent>()
        val collector2Events = mutableListOf<CaseEvent>()

        val job1 =
            launch {
                emitter.events.take(1).toList(collector1Events)
            }

        val job2 =
            launch {
                emitter.events.take(1).toList(collector2Events)
            }

        delay(100)

        emitter.emit(event)

        job1.join()
        job2.join()

        collector1Events shouldHaveSize 1
        collector2Events shouldHaveSize 1
        collector1Events[0] shouldBe event
        collector2Events[0] shouldBe event
    }

    "should not replay events to new collectors" {
        val emitter = DefaultCaseEventEmitter()
        val event1 = createMessageEvent(timestamp = Instant.ofEpochMilli(1000))
        val event2 = createMessageEvent(timestamp = Instant.ofEpochMilli(2000))

        // Emit first event without collector
        emitter.emit(event1)

        delay(100)

        // Start collecting after first event
        val collectedEvents = mutableListOf<CaseEvent>()
        val job =
            launch {
                emitter.events.take(1).toList(collectedEvents)
            }

        delay(100)

        // Emit second event
        emitter.emit(event2)

        job.join()

        // Should only receive event2, not event1 (no replay)
        collectedEvents shouldHaveSize 1
        collectedEvents[0] shouldBe event2
    }

    "should emit events even without collectors (hot flow)" {
        val emitter = DefaultCaseEventEmitter()
        val event = createMessageEvent()

        // Emit without any collector - should not block or throw
        emitter.emit(event)

        // Verify by starting a collector after emission
        shouldThrow<TimeoutCancellationException> {
            withTimeout(500) {
                emitter.events.first()
            }
        }
    }

    "should handle rapid emissions" {
        val emitter = DefaultCaseEventEmitter()
        val eventCount = 50

        val collectedEvents = mutableListOf<CaseEvent>()
        val job =
            launch {
                emitter.events.take(eventCount).toList(collectedEvents)
            }

        delay(100)

        // Emit many events rapidly
        repeat(eventCount) { i ->
            val event = createMessageEvent(timestamp = Instant.ofEpochMilli(i.toLong()))
            emitter.emit(event)
        }

        job.join()

        collectedEvents shouldHaveSize eventCount
    }

    "should handle different event types" {
        val emitter = DefaultCaseEventEmitter()

        val messageEvent = createMessageEvent()
        val statusEvent =
            CaseStatusEvent(
                metadata = EntityMetadata(),
                projectId = projectId,
                caseId = caseId,
                status = CaseStatus.RUNNING,
            )
        val thinkingEvent =
            ThinkingEvent(
                metadata = EntityMetadata(),
                projectId = projectId,
                caseId = caseId,
            )

        val collectedEvents = mutableListOf<CaseEvent>()
        val job =
            launch {
                emitter.events.take(3).toList(collectedEvents)
            }

        delay(100)

        emitter.emit(messageEvent)
        emitter.emit(statusEvent)
        emitter.emit(thinkingEvent)

        job.join()

        collectedEvents shouldHaveSize 3
        collectedEvents[0].shouldBeInstanceOf<MessageEvent>()
        collectedEvents[1].shouldBeInstanceOf<CaseStatusEvent>()
        collectedEvents[2].shouldBeInstanceOf<ThinkingEvent>()
    }

    "should buffer events for slow collectors" {
        val emitter = DefaultCaseEventEmitter()
        val eventCount = 10

        val collectedEvents = mutableListOf<CaseEvent>()
        val job =
            launch {
                emitter.events.take(eventCount).collect { event ->
                    collectedEvents.add(event)
                    // Simulate slow collector
                    delay(50)
                }
            }

        delay(100)

        // Emit events rapidly
        repeat(eventCount) { i ->
            emitter.emit(createMessageEvent(timestamp = Instant.ofEpochMilli(i.toLong())))
        }

        job.join()

        // All events should be received despite slow collection
        collectedEvents shouldHaveSize eventCount
    }
})
