package io.whozoss.agentos.caseEvent

import io.kotest.core.spec.style.StringSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.CaseService
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.WarnEvent
import io.whozoss.agentos.sdk.entity.EntityMetadata
import kotlinx.coroutines.flow.MutableSharedFlow
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Unit tests for [CaseEventSseController.streamEvents].
 *
 * Validates the three connection scenarios:
 * 1. Active case  — history replayed, then live flow subscribed.
 * 2. Past case    — history replayed, emitter completed immediately (no live flow).
 * 3. Unknown case — history is empty and no active instance; emitter completed immediately.
 */
class CaseEventSseControllerTest : StringSpec() {
    val projectId: UUID = UUID.randomUUID()
    val userActor = Actor(id = "u1", displayName = "User", role = ActorRole.USER)

    fun msgEvent(
        caseId: UUID,
        timestamp: Instant = Instant.now(),
    ) = MessageEvent(
        metadata = EntityMetadata(),
        projectId = projectId,
        caseId = caseId,
        timestamp = timestamp,
        actor = userActor,
        content = listOf(MessageContent.Text("hello")),
    )

    fun warnEvent(caseId: UUID) =
        WarnEvent(
            metadata = EntityMetadata(),
            projectId = projectId,
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

            val caseService = mockk<CaseService> {
                every { getCaseInstance(caseId) } throws ResourceNotFoundException("not active")
            }
            val caseEventService = mockk<CaseEventService> {
                every { findByParent(caseId) } answers {
                    latch.countDown()
                    history
                }
            }

            val controller = CaseEventSseController(caseService, caseEventService)
            controller.streamEvents(caseId)

            latch.await(2, TimeUnit.SECONDS)
            verify(exactly = 1) { caseEventService.findByParent(caseId) }
            verify(exactly = 1) { caseService.getCaseInstance(caseId) }
        }

        "past case with no events: completes the emitter without error" {
            val caseId = UUID.randomUUID()
            val latch = CountDownLatch(1)

            val caseService = mockk<CaseService> {
                every { getCaseInstance(caseId) } throws ResourceNotFoundException("not active")
            }
            val caseEventService = mockk<CaseEventService> {
                every { findByParent(caseId) } answers {
                    latch.countDown()
                    emptyList()
                }
            }

            val controller = CaseEventSseController(caseService, caseEventService)
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

            val activeCase = mockk<Case> {
                every { events } returns liveFlow
            }
            val caseService = mockk<CaseService> {
                every { getCaseInstance(caseId) } answers {
                    latch.countDown()
                    activeCase
                }
            }
            val caseEventService = mockk<CaseEventService> {
                every { findByParent(caseId) } returns history
            }

            val controller = CaseEventSseController(caseService, caseEventService)
            controller.streamEvents(caseId)

            latch.await(2, TimeUnit.SECONDS)
            verify(exactly = 1) { caseEventService.findByParent(caseId) }
            verify(exactly = 1) { caseService.getCaseInstance(caseId) }
        }

        "active case with no history: subscribes to live flow without error" {
            val caseId = UUID.randomUUID()
            val latch = CountDownLatch(1)
            val liveFlow = MutableSharedFlow<CaseEvent>()

            val activeCase = mockk<Case> {
                every { events } returns liveFlow
            }
            val caseService = mockk<CaseService> {
                every { getCaseInstance(caseId) } answers {
                    latch.countDown()
                    activeCase
                }
            }
            val caseEventService = mockk<CaseEventService> {
                every { findByParent(caseId) } returns emptyList()
            }

            val controller = CaseEventSseController(caseService, caseEventService)
            controller.streamEvents(caseId)

            latch.await(2, TimeUnit.SECONDS)
            verify(exactly = 1) { caseEventService.findByParent(caseId) }
            verify(exactly = 1) { caseService.getCaseInstance(caseId) }
        }
    }
}
