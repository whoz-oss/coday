package io.whozoss.agentos.caseEvent

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.CaseConfigProperties
import io.whozoss.agentos.caseFlow.CaseRuntime
import io.whozoss.agentos.caseFlow.CaseService
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent
import io.whozoss.agentos.sdk.caseEvent.AnswerEvent
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.ConfirmationResolvedEvent
import io.whozoss.agentos.sdk.caseEvent.ErrorEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.PendingConfirmationEvent
import io.whozoss.agentos.sdk.caseEvent.QuestionEvent
import io.whozoss.agentos.sdk.caseEvent.WarnEvent
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Unit tests for [GlobalCaseEventSseController], the pure functions it's built on
 * ([resolveVisibleActiveRuntimes], [isNotifiable]), and its fan-in/reconciliation
 * behavior.
 *
 * Mirrors [CaseEventSseControllerUnitSpec]'s conventions: controllers built with
 * sseHeartbeatIntervalMs = Long.MAX_VALUE so the heartbeat never interferes, and
 * subscriptionCount on the mocked flows used as the async assertion signal (the full
 * write/disconnect path needs a live Servlet container and isn't unit-testable here).
 */
class GlobalCaseEventSseControllerUnitSpec : StringSpec() {
    val namespaceId: UUID = UUID.randomUUID()
    val userActor = Actor(id = "u1", displayName = "User", role = ActorRole.USER)

    fun caseWithId(id: UUID) = Case(metadata = EntityMetadata(id = id), namespaceId = namespaceId)

    fun runtimeWithId(
        id: UUID,
        flow: MutableSharedFlow<CaseEvent>,
    ) = mockk<CaseRuntime> {
        every { this@mockk.id } returns id
        every { events } returns flow
    }

    init {
        // -------------------------------------------------------------------------
        // resolveVisibleActiveRuntimes — pure function, no mocks
        // -------------------------------------------------------------------------

        "resolveVisibleActiveRuntimes: only runtimes both concerning the user and active are returned" {
            val onlyConcerning = UUID.randomUUID() // in findConcerningUser, not active
            val onlyActive = UUID.randomUUID() // active, but not concerning the user
            val both = UUID.randomUUID() // in both lists

            val concerning = listOf(caseWithId(onlyConcerning), caseWithId(both))
            val active =
                listOf(
                    runtimeWithId(onlyActive, MutableSharedFlow()),
                    runtimeWithId(both, MutableSharedFlow()),
                )

            val result = resolveVisibleActiveRuntimes(concerning, active)

            result.map { it.id } shouldBe listOf(both)
        }

        "resolveVisibleActiveRuntimes: empty when no overlap" {
            val concerning = listOf(caseWithId(UUID.randomUUID()))
            val active = listOf(runtimeWithId(UUID.randomUUID(), MutableSharedFlow()))

            resolveVisibleActiveRuntimes(concerning, active) shouldBe emptyList()
        }

        // -------------------------------------------------------------------------
        // isNotifiable — pure extension, no mocks
        // -------------------------------------------------------------------------

        "isNotifiable: PendingConfirmationEvent, ConfirmationResolvedEvent, QuestionEvent, AnswerEvent, " +
            "AgentFinishedEvent and ErrorEvent are notifiable" {
                val caseId = UUID.randomUUID()
                val agentId = UUID.randomUUID()
                val notifiable: List<CaseEvent> =
                    listOf(
                        PendingConfirmationEvent(
                            namespaceId = namespaceId,
                            caseId = caseId,
                            toolRequestId = "t1",
                            toolName = "run",
                            inputJson = "{}",
                        ),
                        ConfirmationResolvedEvent(
                            namespaceId = namespaceId,
                            caseId = caseId,
                            pendingEventId = UUID.randomUUID(),
                            confirmed = true,
                        ),
                        QuestionEvent(
                            namespaceId = namespaceId,
                            caseId = caseId,
                            agentId = agentId,
                            agentName = "Agent",
                            question = "Continue?",
                        ),
                        AnswerEvent(
                            namespaceId = namespaceId,
                            caseId = caseId,
                            questionId = UUID.randomUUID(),
                            actor = userActor,
                            answer = "yes",
                        ),
                        AgentFinishedEvent(
                            namespaceId = namespaceId,
                            caseId = caseId,
                            agentId = agentId,
                            agentName = "Agent",
                        ),
                        ErrorEvent(namespaceId = namespaceId, caseId = caseId, message = "boom"),
                    )

                notifiable.forEach { it.isNotifiable() shouldBe true }
            }

        "isNotifiable: MessageEvent and WarnEvent are not notifiable" {
            val caseId = UUID.randomUUID()
            val notNotifiable: List<CaseEvent> =
                listOf(
                    MessageEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        actor = userActor,
                        content = listOf(MessageContent.Text("hello")),
                    ),
                    WarnEvent(namespaceId = namespaceId, caseId = caseId, message = "something happened"),
                )

            notNotifiable.forEach { it.isNotifiable() shouldBe false }
        }

        // -------------------------------------------------------------------------
        // Controller-level fan-in / reconciliation
        // -------------------------------------------------------------------------

        "two visible active cases: collectors subscribe to both live flows" {
            val userId = UUID.randomUUID()
            val caseAId = UUID.randomUUID()
            val caseBId = UUID.randomUUID()
            val flowA = MutableSharedFlow<CaseEvent>(replay = 0)
            val flowB = MutableSharedFlow<CaseEvent>(replay = 0)
            val subA: StateFlow<Int> = flowA.subscriptionCount
            val subB: StateFlow<Int> = flowB.subscriptionCount

            val user = User(metadata = EntityMetadata(id = userId), externalId = "u1")
            val userService = mockk<UserService> { every { getCurrentUser() } returns user }
            val caseService =
                mockk<CaseService> {
                    every { findConcerningUser(userId) } returns listOf(caseWithId(caseAId), caseWithId(caseBId))
                    every { getAllActiveCases() } returns
                        listOf(runtimeWithId(caseAId, flowA), runtimeWithId(caseBId, flowB))
                }

            val controller =
                GlobalCaseEventSseController(
                    caseService = caseService,
                    userService = userService,
                    caseConfig = CaseConfigProperties(sseHeartbeatIntervalMs = Long.MAX_VALUE, globalEventsPollIntervalMs = 20L),
                    activeCompanionSessionRegistry = ActiveCompanionSessionRegistry(),
                )
            val emitter = controller.streamMyEvents()

            subA.first { it >= 1 }
            subB.first { it >= 1 }

            // Without this, the reconciliation loop (delay=20ms) keeps calling these
            // mocks forever in the background, long after the test returns — surfaces
            // as "no answer found" MockKExceptions once later tests tear down state.
            emitter.complete()
        }

        "a case dropping out of getAllActiveCases has its collector cancelled without affecting the other" {
            val userId = UUID.randomUUID()
            val caseAId = UUID.randomUUID()
            val caseBId = UUID.randomUUID()
            val flowA = MutableSharedFlow<CaseEvent>(replay = 0)
            val flowB = MutableSharedFlow<CaseEvent>(replay = 0)
            val subA: StateFlow<Int> = flowA.subscriptionCount
            val subB: StateFlow<Int> = flowB.subscriptionCount

            val user = User(metadata = EntityMetadata(id = userId), externalId = "u1")
            val userService = mockk<UserService> { every { getCurrentUser() } returns user }

            val activeRuntimes =
                AtomicReference(listOf(runtimeWithId(caseAId, flowA), runtimeWithId(caseBId, flowB)))

            val caseService =
                mockk<CaseService> {
                    every { findConcerningUser(userId) } returns listOf(caseWithId(caseAId), caseWithId(caseBId))
                    every { getAllActiveCases() } answers { activeRuntimes.get() }
                }

            val controller =
                GlobalCaseEventSseController(
                    caseService = caseService,
                    userService = userService,
                    caseConfig = CaseConfigProperties(sseHeartbeatIntervalMs = Long.MAX_VALUE, globalEventsPollIntervalMs = 20L),
                    activeCompanionSessionRegistry = ActiveCompanionSessionRegistry(),
                )
            val emitter = controller.streamMyEvents()

            // Both subscribed initially.
            subA.first { it >= 1 }
            subB.first { it >= 1 }

            // Case A ends / is evicted: drop it from the active set.
            activeRuntimes.set(listOf(runtimeWithId(caseBId, flowB)))

            // Next reconciliation tick cancels A's collector; B is untouched.
            subA.first { it == 0 }
            subB.value shouldBe 1

            emitter.complete()
        }
    }
}
