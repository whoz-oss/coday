package io.whozoss.agentos.caseEvent

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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.time.Instant
import java.util.*
import java.util.concurrent.TimeUnit

@Timeout(value = 5, unit = TimeUnit.SECONDS)
class DefaultCaseEventEmitterTest {
    private val projectId = UUID.randomUUID()
    private val caseId = UUID.randomUUID()

    @Test
    fun `should emit events to collectors`() =
        runBlocking {
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

            assertEquals(1, collectedEvents.size)
            assertEquals(event, collectedEvents[0])
        }

    @Test
    fun `should emit multiple events in order`() =
        runBlocking {
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

            assertEquals(3, collectedEvents.size)
            assertEquals(event1, collectedEvents[0])
            assertEquals(event2, collectedEvents[1])
            assertEquals(event3, collectedEvents[2])
        }

    @Test
    fun `should support multiple collectors`() =
        runBlocking {
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

            assertEquals(1, collector1Events.size)
            assertEquals(1, collector2Events.size)
            assertEquals(event, collector1Events[0])
            assertEquals(event, collector2Events[0])
        }

    @Test
    fun `should not replay events to new collectors`() =
        runBlocking {
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
            assertEquals(1, collectedEvents.size)
            assertEquals(event2, collectedEvents[0])
        }

    @Test
    fun `should emit events even without collectors (hot flow)`() =
        runBlocking {
            val emitter = DefaultCaseEventEmitter()
            val event = createMessageEvent()

            // Emit without any collector - should not block or throw
            emitter.emit(event)

            // Verify by starting a collector after emission
            val result =
                runCatching {
                    withTimeout(500) {
                        emitter.events.first()
                    }
                }

            // Should timeout because no new events are emitted
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is TimeoutCancellationException)
        }

    @Test
    fun `should handle rapid emissions`() =
        runBlocking {
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

            assertEquals(eventCount, collectedEvents.size)
        }

    @Test
    fun `should handle different event types`() =
        runBlocking {
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

            assertEquals(3, collectedEvents.size)
            assertTrue(collectedEvents[0] is MessageEvent)
            assertTrue(collectedEvents[1] is CaseStatusEvent)
            assertTrue(collectedEvents[2] is ThinkingEvent)
        }

    @Test
    fun `should buffer events for slow collectors`() =
        runBlocking {
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
            assertEquals(eventCount, collectedEvents.size)
        }

    private fun createMessageEvent(timestamp: Instant = Instant.now()): MessageEvent =
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
}
