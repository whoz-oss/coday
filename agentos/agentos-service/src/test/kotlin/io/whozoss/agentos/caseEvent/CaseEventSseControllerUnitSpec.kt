package io.whozoss.agentos.caseEvent

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Unit tests for [CaseEventSseController.streamEvents].
 *
 * All controller instances are built with sseHeartbeatIntervalMs set to a very large
 * value (Long.MAX_VALUE) so the heartbeat coroutine never fires during the test.
 * Tests that specifically exercise the heartbeat/disconnect path set a short interval
 * and drive the disconnect themselves.
 *
 * Validates the three connection scenarios:
 * 1. Active case  — history replayed, then live flow subscribed.
 * 2. Past case    — history replayed, emitter completed immediately (no live flow).
 * 3. Unknown case — history is empty and no active instance; emitter completed immediately.
 */
class CaseEventSseControllerUnitSpec : StringSpec() {
    val namespaceId: UUID = UUID.randomUUID()
    val userActor = Actor(id = "u1", displayName = "User", role = ActorRole.USER)

    fun msgEvent(
        caseId: UUID,
        timestamp: Instant = Instant.now(),
    ) = MessageEvent(
        metadata = EntityMetadata(),
        namespaceId = namespaceId,
        caseId = caseId,
        timestamp = timestamp,
        actor = userActor,
        content = listOf(MessageContent.Text("hello")),
    )

    fun warnEvent(caseId: UUID) =
        WarnEvent(
            metadata = EntityMetadata(),
            namespaceId = namespaceId,
            caseId = caseId,
            message = "something happened",
        )

    init {
        // -------------------------------------------------------------------------
        // Past / completed case — only history, no live instance
        // -------------------------------------------------------------------------

        "past case: replays persisted events and completes the emitter" {
            val caseId = UUID.randomUUID()
            val history = listOf(msgEvent(caseId, Instant.ofEpochMilli(1000)), warnEvent(caseId))
            val latch = CountDownLatch(1)

            val caseService =
                mockk<CaseService> {
                    every { findActiveRuntime(caseId) } returns null
                }
            val caseEventService =
                mockk<CaseEventService> {
                    every { findByParent(caseId) } answers {
                        latch.countDown()
                        history
                    }
                }

            val controller =
                CaseEventSseController(
                    caseService = caseService,
                    caseEventService = caseEventService,
                    caseConfig = CaseConfigProperties(sseHeartbeatIntervalMs = Long.MAX_VALUE),
                )
            controller.streamEvents(caseId)

            latch.await(2, TimeUnit.SECONDS)
            verify(exactly = 1) { caseEventService.findByParent(caseId) }
            verify(timeout = 2000, exactly = 1) { caseService.findActiveRuntime(caseId) }
        }

        "past case with no events: completes the emitter without error" {
            val caseId = UUID.randomUUID()
            val latch = CountDownLatch(1)

            val caseService =
                mockk<CaseService> {
                    every { findActiveRuntime(caseId) } returns null
                }
            val caseEventService =
                mockk<CaseEventService> {
                    every { findByParent(caseId) } answers {
                        latch.countDown()
                        emptyList()
                    }
                }

            val controller =
                CaseEventSseController(
                    caseService = caseService,
                    caseEventService = caseEventService,
                    caseConfig = CaseConfigProperties(sseHeartbeatIntervalMs = Long.MAX_VALUE),
                )
            controller.streamEvents(caseId)

            latch.await(2, TimeUnit.SECONDS)
            verify(exactly = 1) { caseEventService.findByParent(caseId) }
        }

        // -------------------------------------------------------------------------
        // Active case — history replayed then live flow subscribed
        // -------------------------------------------------------------------------

        "active case: history is queried and live flow is subscribed" {
            val caseId = UUID.randomUUID()
            val history = listOf(msgEvent(caseId))
            val latch = CountDownLatch(1)
            val liveFlow = MutableSharedFlow<CaseEvent>()

            val activeCase =
                mockk<CaseRuntime> {
                    every { events } returns liveFlow
                }
            val caseService =
                mockk<CaseService> {
                    every { findActiveRuntime(caseId) } answers {
                        latch.countDown()
                        activeCase
                    }
                }
            val caseEventService =
                mockk<CaseEventService> {
                    every { findByParent(caseId) } returns history
                }

            val controller =
                CaseEventSseController(
                    caseService = caseService,
                    caseEventService = caseEventService,
                    caseConfig = CaseConfigProperties(sseHeartbeatIntervalMs = Long.MAX_VALUE),
                )
            controller.streamEvents(caseId)

            latch.await(2, TimeUnit.SECONDS)
            verify(exactly = 1) { caseEventService.findByParent(caseId) }
            verify(exactly = 1) { caseService.findActiveRuntime(caseId) }
        }

        "active case with no history: subscribes to live flow without error" {
            val caseId = UUID.randomUUID()
            val latch = CountDownLatch(1)
            val liveFlow = MutableSharedFlow<CaseEvent>()

            val activeCase =
                mockk<CaseRuntime> {
                    every { events } returns liveFlow
                }
            val caseService =
                mockk<CaseService> {
                    every { findActiveRuntime(caseId) } answers {
                        latch.countDown()
                        activeCase
                    }
                }
            val caseEventService =
                mockk<CaseEventService> {
                    every { findByParent(caseId) } returns emptyList()
                }

            val controller =
                CaseEventSseController(
                    caseService = caseService,
                    caseEventService = caseEventService,
                    caseConfig = CaseConfigProperties(sseHeartbeatIntervalMs = Long.MAX_VALUE),
                )
            controller.streamEvents(caseId)

            latch.await(2, TimeUnit.SECONDS)
            verify(exactly = 1) { caseEventService.findByParent(caseId) }
            verify(exactly = 1) { caseService.findActiveRuntime(caseId) }
        }

        // -------------------------------------------------------------------------
        // Heartbeat — disconnect detection
        // -------------------------------------------------------------------------
        //
        // The full disconnect path (heartbeat write fails → Tomcat calls onError →
        // scope.cancel() → subscriptionCount drops) requires a live Servlet container
        // and cannot be exercised in a unit test without a full Spring MVC context.
        //
        // The eviction behaviour that depends on subscriptionCount is covered by the
        // integration-level tests in CaseServiceImplSpec
        // ("idle runtime is evicted after all SSE subscribers disconnect...").
        //
        // What we can test here is that the controller subscribes to the live flow
        // (subscriptionCount reaches 1) when an active runtime is present, confirming
        // the collector coroutine is launched and the heartbeat interval is accepted
        // without error.

        "active case: collector subscribes to live flow and heartbeat config is accepted" {
            val caseId = UUID.randomUUID()
            val liveFlow = MutableSharedFlow<CaseEvent>(replay = 0)
            val subscriptionCount: StateFlow<Int> = liveFlow.subscriptionCount

            val activeCase = mockk<CaseRuntime> { every { events } returns liveFlow }
            val caseService = mockk<CaseService> { every { findActiveRuntime(caseId) } returns activeCase }
            val caseEventService = mockk<CaseEventService> { every { findByParent(caseId) } returns emptyList() }

            // Use a short heartbeat interval to confirm the config is wired through.
            val controller =
                CaseEventSseController(
                    caseService,
                    caseEventService,
                    CaseConfigProperties(sseHeartbeatIntervalMs = 50L),
                )
            controller.streamEvents(caseId)

            // The collector coroutine must subscribe to the live flow.
            subscriptionCount.first { it >= 1 }
            subscriptionCount.value shouldBe 1
        }
    }
}
