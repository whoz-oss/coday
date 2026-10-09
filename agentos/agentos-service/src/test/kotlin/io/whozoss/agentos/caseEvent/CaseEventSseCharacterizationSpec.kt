package io.whozoss.agentos.caseEvent

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.caseFlow.CaseConfigProperties
import io.whozoss.agentos.caseFlow.CaseRuntime
import io.whozoss.agentos.caseFlow.CaseService
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.WarnEvent
import io.whozoss.agentos.sdk.entity.EntityMetadata
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.lang.reflect.Field
import java.time.Instant
import java.util.UUID

/**
 * Characterization tests for the AgentOS SSE contract exposed by
 * [CaseEventSseController] (`GET /api/cases/{caseId}/events`).
 *
 * These tests freeze the *observable* protocol that the Factory -> AgentOS bridge must rely on:
 * - the stable `event: case-event` channel and the SSE `id` = `CaseEvent.id`;
 * - connection before execution (live only), mid-run (history then live), and after
 *   termination (history then stream completion);
 * - full replay on reconnection (AgentOS ignores `Last-Event-ID`, there is no cursor);
 * - in-connection deduplication keyed by `CaseEvent.id`;
 * - saturation closing the stream with an error so the client reconnects and replays;
 * - periodic `keep-alive` comment frames.
 *
 * They do NOT exercise `includePreviousEvents=false`, serialization, auth, or the servlet
 * container: those are documented in `app_docs/agentos-sse-contract.md`.
 *
 * ### Why reflection on [SseEmitter]
 * [CaseEventSseController] constructs `SseEmitter(0L)` itself, so there is no seam to inject a
 * test double. Before a servlet container calls `initialize(Handler)`, every `send(...)` is
 * queued in the package-private `earlySendAttempts` field of [ResponseBodyEmitter] (a
 * `LinkedHashSet`, insertion-ordered). Reading that field lets us freeze the exact frames
 * without booting Tomcat. `complete` and `failure` are read the same way. This is deliberately
 * coupled to the current Spring internals — the goal is to *characterize* the current contract,
 * so a Spring upgrade that changes these fields must be noticed here.
 */
class CaseEventSseCharacterizationSpec :
    StringSpec({
        timeout = 15_000

        val namespaceId: UUID = UUID.randomUUID()
        val userActor = Actor(id = "u1", displayName = "User", role = ActorRole.USER)

        fun messageEvent(
            caseId: UUID,
            timestamp: Instant,
        ) = MessageEvent(
            metadata = EntityMetadata(),
            namespaceId = namespaceId,
            caseId = caseId,
            timestamp = timestamp,
            actor = userActor,
            content = listOf(MessageContent.Text("hello")),
        )

        fun warnEvent(
            caseId: UUID,
            timestamp: Instant,
        ) = WarnEvent(
            metadata = EntityMetadata(),
            namespaceId = namespaceId,
            caseId = caseId,
            timestamp = timestamp,
            message = "something happened",
        )

        fun activeRuntime(
            live: SharedFlow<CaseEvent>,
            deliveryFailures: MutableStateFlow<Long> = MutableStateFlow(0L),
        ): CaseRuntime =
            mockk {
                every { events } returns live
                every { deliveryFailureCount } returns deliveryFailures
            }

        fun controller(
            caseService: CaseService,
            caseEventService: CaseEventService,
            heartbeatMs: Long = Long.MAX_VALUE,
        ) = CaseEventSseController(
            caseService = caseService,
            caseEventService = caseEventService,
            caseConfig = CaseConfigProperties(sseHeartbeatIntervalMs = heartbeatMs),
        )

        // -------------------------------------------------------------------------
        // 1. Connection before execution — live stream only
        // -------------------------------------------------------------------------

        "connection before execution: live events are streamed on the stable case-event channel with their eventId" {
            val caseId = UUID.randomUUID()
            val source = DefaultCaseEventEmitter()
            val runtime = activeRuntime(source.events)
            val caseService = mockk<CaseService> { every { findActiveRuntime(caseId) } returns runtime }
            val caseEventService = mockk<CaseEventService> { every { findByParent(caseId) } returns emptyList() }

            val emitter = controller(caseService, caseEventService).streamEvents(caseId)
            source.subscriptionCount.first { it >= 1 }

            val e1 = messageEvent(caseId, Instant.ofEpochMilli(1_000))
            source.emit(e1)

            awaitSseEvents(emitter, 1).map { it.id } shouldBe listOf(e1.id)
            // The frame carries both the SSE id (the CaseEvent UUID) and the stable channel name.
            val frame = sseStrings(emitter).first { it.contains("event:case-event") }
            frame shouldContain "id:${e1.id}"
            frame shouldContain "event:case-event"
        }

        // -------------------------------------------------------------------------
        // 2. Connection after execution started — durable replay then live
        // -------------------------------------------------------------------------

        "connection after execution start: durable history is replayed first, then live events, in order and without duplication" {
            val caseId = UUID.randomUUID()
            val source = DefaultCaseEventEmitter()
            val runtime = activeRuntime(source.events)
            val e1 = messageEvent(caseId, Instant.ofEpochMilli(1_000))
            val e2 = warnEvent(caseId, Instant.ofEpochMilli(2_000))
            val caseService = mockk<CaseService> { every { findActiveRuntime(caseId) } returns runtime }
            val caseEventService = mockk<CaseEventService> { every { findByParent(caseId) } returns listOf(e1, e2) }

            val emitter = controller(caseService, caseEventService).streamEvents(caseId)
            awaitSseEvents(emitter, 2)
            source.subscriptionCount.first { it >= 1 }

            val e3 = messageEvent(caseId, Instant.ofEpochMilli(3_000))
            source.emit(e3)

            awaitSseEvents(emitter, 3).map { it.id } shouldBe listOf(e1.id, e2.id, e3.id)
            verify(exactly = 1) { caseEventService.findByParent(caseId) }
        }

        "includePreviousEvents=false skips durable replay and streams only live events" {
            val caseId = UUID.randomUUID()
            val source = DefaultCaseEventEmitter()
            val runtime = activeRuntime(source.events)
            val durable = messageEvent(caseId, Instant.ofEpochMilli(1_000))
            val caseService = mockk<CaseService> { every { findActiveRuntime(caseId) } returns runtime }
            val caseEventService = mockk<CaseEventService> { every { findByParent(caseId) } returns listOf(durable) }

            val emitter =
                controller(caseService, caseEventService)
                    .streamEvents(caseId, includePreviousEvents = false)
            source.subscriptionCount.first { it >= 1 }

            val live = warnEvent(caseId, Instant.ofEpochMilli(2_000))
            source.emit(live)

            awaitSseEvents(emitter, 1).map { it.id } shouldBe listOf(live.id)
            verify(exactly = 0) { caseEventService.findByParent(caseId) }
        }

        // -------------------------------------------------------------------------
        // 3. Connection after termination — snapshot replay then auto-complete
        // -------------------------------------------------------------------------

        "connection after termination: durable history is replayed, then the stream completes" {
            val caseId = UUID.randomUUID()
            val e1 = messageEvent(caseId, Instant.ofEpochMilli(1_000))
            val e2 = warnEvent(caseId, Instant.ofEpochMilli(2_000))
            val caseService = mockk<CaseService> { every { findActiveRuntime(caseId) } returns null }
            val caseEventService = mockk<CaseEventService> { every { findByParent(caseId) } returns listOf(e1, e2) }

            val emitter = controller(caseService, caseEventService).streamEvents(caseId)

            awaitSseEvents(emitter, 2).map { it.id } shouldBe listOf(e1.id, e2.id)
            awaitSseComplete(emitter)
            sseComplete(emitter) shouldBe true
            sseFailure(emitter) shouldBe null
        }

        // -------------------------------------------------------------------------
        // 4. Disconnection / reconnection and eventId deduplication
        // -------------------------------------------------------------------------

        "reconnection without a cursor replays the full durable history from the first event" {
            val caseId = UUID.randomUUID()
            val source = DefaultCaseEventEmitter()
            val runtime = activeRuntime(source.events)
            val e1 = messageEvent(caseId, Instant.ofEpochMilli(1_000))
            val e2 = warnEvent(caseId, Instant.ofEpochMilli(2_000))
            val caseService = mockk<CaseService> { every { findActiveRuntime(caseId) } returns runtime }
            val caseEventService =
                mockk<CaseEventService> {
                    // First connection: nothing durable yet. Reconnect: e1 and e2 are durable.
                    every { findByParent(caseId) } returnsMany listOf(emptyList(), listOf(e1, e2))
                }
            val ctrl = controller(caseService, caseEventService)

            val first = ctrl.streamEvents(caseId)
            source.subscriptionCount.first { it >= 1 }
            source.emit(e1)
            awaitSseEvents(first, 1).map { it.id } shouldBe listOf(e1.id)

            // A fresh SSE request — AgentOS does not honor Last-Event-ID, so it replays from #1.
            val second = ctrl.streamEvents(caseId)
            awaitSseEvents(second, 2).map { it.id } shouldBe listOf(e1.id, e2.id)
            verify(exactly = 2) { caseEventService.findByParent(caseId) }
        }

        "a durable event replayed from history is not emitted twice when it also arrives on the live flow" {
            val caseId = UUID.randomUUID()
            val live = MutableSharedFlow<CaseEvent>()
            val runtime = activeRuntime(live)
            val e1 = messageEvent(caseId, Instant.ofEpochMilli(1_000))
            val caseService = mockk<CaseService> { every { findActiveRuntime(caseId) } returns runtime }
            val caseEventService = mockk<CaseEventService> { every { findByParent(caseId) } returns listOf(e1) }

            val emitter = controller(caseService, caseEventService).streamEvents(caseId)
            awaitSseEvents(emitter, 1).map { it.id } shouldBe listOf(e1.id)

            live.subscriptionCount.first { it >= 1 }
            live.emit(e1) // Same event id, already emitted by the replay.
            delay(200)

            sseEvents(emitter).map { it.id } shouldBe listOf(e1.id)
        }

        // -------------------------------------------------------------------------
        // 5. Saturation / backpressure signal
        // -------------------------------------------------------------------------

        "a rejected runtime emission closes the stream with an error so the client reconnects and replays" {
            val caseId = UUID.randomUUID()
            val live = MutableSharedFlow<CaseEvent>()
            val deliveryFailures = MutableStateFlow(0L)
            val runtime = activeRuntime(live, deliveryFailures)
            val caseService = mockk<CaseService> { every { findActiveRuntime(caseId) } returns runtime }
            val caseEventService = mockk<CaseEventService> { every { findByParent(caseId) } returns emptyList() }

            val emitter = controller(caseService, caseEventService).streamEvents(caseId)
            // Wait for the controller's delivery-failure watcher to subscribe before signalling.
            deliveryFailures.subscriptionCount.first { it >= 1 }
            deliveryFailures.value = 1L

            awaitSseComplete(emitter)
            sseComplete(emitter) shouldBe true
            val failure = sseFailure(emitter)
            failure.shouldBeInstanceOf<IllegalStateException>()
            failure.message shouldContain "saturated"
        }

        // -------------------------------------------------------------------------
        // 6. Heartbeat
        // -------------------------------------------------------------------------

        "heartbeat keep-alive comment frames are written while the stream stays open" {
            val caseId = UUID.randomUUID()
            val live = MutableSharedFlow<CaseEvent>()
            val runtime = activeRuntime(live)
            val caseService = mockk<CaseService> { every { findActiveRuntime(caseId) } returns runtime }
            val caseEventService = mockk<CaseEventService> { every { findByParent(caseId) } returns emptyList() }

            val emitter = controller(caseService, caseEventService, heartbeatMs = 20L).streamEvents(caseId)

            withTimeout(5_000) {
                while (sseStrings(emitter).none { it.contains(":keep-alive") }) delay(5)
            }
            sseStrings(emitter).any { it.contains(":keep-alive") } shouldBe true
        }
    })

// ---------------------------------------------------------------------------
// Reflection helpers over Spring's ResponseBodyEmitter/SseEmitter internals
// ---------------------------------------------------------------------------

private fun declaredField(
    type: Class<*>,
    name: String,
): Field? {
    var current: Class<*>? = type
    while (current != null) {
        runCatching { return current.getDeclaredField(name) }
        current = current.superclass
    }
    return null
}

private fun readEmitterField(
    emitter: SseEmitter,
    name: String,
): Any? {
    val field = declaredField(emitter.javaClass, name) ?: error("No field '$name' on ${emitter.javaClass}")
    field.isAccessible = true
    return field.get(emitter)
}

/**
 * Extract the SSE frames buffered before the emitter is initialized by a servlet container.
 * Each entry of `earlySendAttempts` is a `DataWithMediaType`; `getData()` returns either the
 * raw appended text frame (a `String`, e.g. `"id:...\nevent:case-event\n"` or `":keep-alive\n"`)
 * or the `CaseEvent` object passed to `.data(event)`.
 */
private fun sseFrames(emitter: SseEmitter): List<Any?> {
    val attempts = readEmitterField(emitter, "earlySendAttempts") as Set<*>
    return attempts.map { entry -> entry!!.javaClass.getMethod("getData").invoke(entry) }
}

private fun sseEvents(emitter: SseEmitter): List<CaseEvent> = sseFrames(emitter).filterIsInstance<CaseEvent>()

private fun sseStrings(emitter: SseEmitter): List<String> = sseFrames(emitter).filterIsInstance<String>()

private fun sseComplete(emitter: SseEmitter): Boolean = readEmitterField(emitter, "complete") == true

private fun sseFailure(emitter: SseEmitter): Throwable? = readEmitterField(emitter, "failure") as Throwable?

private suspend fun awaitSseEvents(
    emitter: SseEmitter,
    count: Int,
): List<CaseEvent> =
    withTimeout(5_000) {
        while (true) {
            val events = sseEvents(emitter)
            if (events.size >= count) return@withTimeout events
            delay(5)
        }
        @Suppress("UNREACHABLE_CODE")
        emptyList()
    }

private suspend fun awaitSseComplete(emitter: SseEmitter) {
    withTimeout(5_000) {
        while (!sseComplete(emitter)) delay(5)
    }
}
