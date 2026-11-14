package io.whozoss.agentos.caseEvent

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.CaseConfigProperties
import io.whozoss.agentos.caseFlow.CaseRuntime
import io.whozoss.agentos.caseFlow.CaseService
import io.whozoss.agentos.namespace.NamespaceCaseListUpdateBroadcaster
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
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
import io.whozoss.agentos.security.declarative.AgentOsAuthentication
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
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

    /** These tests are about the case collectors; the case-list side has its own tests below. */
    fun permissionServiceGrantingNothing() =
        mockk<PermissionService> { every { hasPermission(any(), any(), any(), any()) } returns false }

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
        // Controller-level fan-in / reconciliation & SecurityContext propagation
        // -------------------------------------------------------------------------

        "two visible active cases: collectors subscribe to both live flows with SecurityContext populated" {
            val userId = UUID.randomUUID()
            val caseAId = UUID.randomUUID()
            val caseBId = UUID.randomUUID()
            val flowA = MutableSharedFlow<CaseEvent>(replay = 0)
            val flowB = MutableSharedFlow<CaseEvent>(replay = 0)
            val subA: StateFlow<Int> = flowA.subscriptionCount
            val subB: StateFlow<Int> = flowB.subscriptionCount
            val capturedAuth = AtomicReference<Authentication?>(null)

            val user = User(metadata = EntityMetadata(id = userId), externalId = "u1")
            val userService = mockk<UserService> { every { getCurrentUser() } returns user }
            val caseService =
                mockk<CaseService> {
                    every { findConcerningUser(userId) } answers {
                        capturedAuth.set(SecurityContextHolder.getContext().authentication)
                        listOf(caseWithId(caseAId), caseWithId(caseBId))
                    }
                    every { getAllActiveCases() } returns
                        listOf(runtimeWithId(caseAId, flowA), runtimeWithId(caseBId, flowB))
                }

            val controller =
                GlobalCaseEventSseController(
                    caseService = caseService,
                    userService = userService,
                    caseConfig = CaseConfigProperties(sseHeartbeatIntervalMs = Long.MAX_VALUE, globalEventsPollIntervalMs = 20L),
                    activeCompanionSessionRegistry = ActiveCompanionSessionRegistry(),
                    permissionService = permissionServiceGrantingNothing(),
                    caseListUpdateBroadcaster = NamespaceCaseListUpdateBroadcaster(),
                )
            val emitter = controller.streamMyEvents()

            subA.first { it >= 1 }
            subB.first { it >= 1 }

            val auth = capturedAuth.get()
            (auth is AgentOsAuthentication) shouldBe true
            auth?.name shouldBe userId.toString()

            // Without this, the reconciliation loop (delay=20ms) keeps calling these
            // mocks forever in the background, long after the test returns.
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
                    permissionService = permissionServiceGrantingNothing(),
                    caseListUpdateBroadcaster = NamespaceCaseListUpdateBroadcaster(),
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

        // -------------------------------------------------------------------------
        // Case-list updates — carried here instead of one stream per namespace (#1616)
        // -------------------------------------------------------------------------

        "the case-list subscription is taken before the handler returns, not on a later dispatch" {
            val userId = UUID.randomUUID()
            val user = User(metadata = EntityMetadata(id = userId), externalId = "u1")
            val userService = mockk<UserService> { every { getCurrentUser() } returns user }
            val caseService =
                mockk<CaseService> {
                    every { findConcerningUser(userId) } returns emptyList()
                    every { getAllActiveCases() } returns emptyList()
                }
            val subscribedOn = AtomicReference<Thread?>(null)
            val broadcaster =
                mockk<NamespaceCaseListUpdateBroadcaster> {
                    every { allEvents() } answers {
                        subscribedOn.set(Thread.currentThread())
                        MutableSharedFlow()
                    }
                }

            val controller =
                GlobalCaseEventSseController(
                    caseService = caseService,
                    userService = userService,
                    caseConfig = CaseConfigProperties(sseHeartbeatIntervalMs = Long.MAX_VALUE, globalEventsPollIntervalMs = 20L),
                    activeCompanionSessionRegistry = ActiveCompanionSessionRegistry(),
                    permissionService = permissionServiceGrantingNothing(),
                    caseListUpdateBroadcaster = broadcaster,
                )
            val emitter = controller.streamMyEvents()

            // Read at once, without waiting. The flow replays nothing and the client reloads its list as
            // soon as the stream opens: a subscription taken on a later dispatch, on another thread,
            // leaves a window for an update to fall into. A timing-based test cannot see that window,
            // the dispatch usually winning the race; the thread the subscription was taken on can.
            subscribedOn.get() shouldBe Thread.currentThread()

            emitter.complete()
        }

        "a case-list update of a namespace created after connecting is followed" {
            val userId = UUID.randomUUID()
            val user = User(metadata = EntityMetadata(id = userId), externalId = "u1")
            val userService = mockk<UserService> { every { getCurrentUser() } returns user }
            val caseService =
                mockk<CaseService> {
                    every { findConcerningUser(userId) } returns emptyList()
                    every { getAllActiveCases() } returns emptyList()
                }
            // Created after the stream opened: nothing about it could have been resolved at connect.
            val laterNamespaceId = UUID.randomUUID()
            val permissionService =
                mockk<PermissionService> {
                    every {
                        hasPermission(userId.toString(), EntityType.NAMESPACE, laterNamespaceId.toString(), Action.READ)
                    } returns true
                }
            val broadcaster = NamespaceCaseListUpdateBroadcaster()

            val controller =
                GlobalCaseEventSseController(
                    caseService = caseService,
                    userService = userService,
                    caseConfig = CaseConfigProperties(sseHeartbeatIntervalMs = Long.MAX_VALUE, globalEventsPollIntervalMs = 20L),
                    activeCompanionSessionRegistry = ActiveCompanionSessionRegistry(),
                    permissionService = permissionService,
                    caseListUpdateBroadcaster = broadcaster,
                )
            val emitter = controller.streamMyEvents()

            broadcaster.publishCaseCreated(laterNamespaceId)

            verify(timeout = 2000, exactly = 1) {
                permissionService.hasPermission(userId.toString(), EntityType.NAMESPACE, laterNamespaceId.toString(), Action.READ)
            }

            emitter.complete()
        }

        "a failing permission check skips that update and keeps the stream following the others" {
            val userId = UUID.randomUUID()
            val user = User(metadata = EntityMetadata(id = userId), externalId = "u1")
            val userService = mockk<UserService> { every { getCurrentUser() } returns user }
            val caseService =
                mockk<CaseService> {
                    every { findConcerningUser(userId) } returns emptyList()
                    every { getAllActiveCases() } returns emptyList()
                }
            val failingNamespaceId = UUID.randomUUID()
            val permissionService =
                mockk<PermissionService> {
                    every {
                        hasPermission(userId.toString(), EntityType.NAMESPACE, failingNamespaceId.toString(), Action.READ)
                    } throws IllegalStateException("store down")
                    every { hasPermission(userId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.READ) } returns true
                }
            val broadcaster = NamespaceCaseListUpdateBroadcaster()

            val controller =
                GlobalCaseEventSseController(
                    caseService = caseService,
                    userService = userService,
                    caseConfig = CaseConfigProperties(sseHeartbeatIntervalMs = Long.MAX_VALUE, globalEventsPollIntervalMs = 20L),
                    activeCompanionSessionRegistry = ActiveCompanionSessionRegistry(),
                    permissionService = permissionService,
                    caseListUpdateBroadcaster = broadcaster,
                )
            val emitter = controller.streamMyEvents()

            broadcaster.publishCaseCreated(failingNamespaceId)
            broadcaster.publishCaseCreated(namespaceId)

            verify(timeout = 2000, exactly = 1) {
                permissionService.hasPermission(userId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.READ)
            }

            emitter.complete()
        }
    }
}
