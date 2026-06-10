package io.whozoss.agentos.caseFlow

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.agent.AgentConfigProperties
import io.whozoss.agentos.agent.AgentService
import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.caseEvent.CaseEventServiceImpl
import io.whozoss.agentos.caseEvent.InMemoryCaseEventRepository
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.agent.Agent
import io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent
import io.whozoss.agentos.sdk.caseEvent.AgentRunningEvent
import io.whozoss.agentos.sdk.caseEvent.AgentSelectedEvent
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.CaseStatusEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.TextChunkEvent
import io.whozoss.agentos.sdk.caseEvent.ThinkingEvent
import io.whozoss.agentos.sdk.caseEvent.WarnEvent
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.UUID

/**
 * Suspends until [runtime]'s SSE flow has at least [count] active subscribers.
 *
 * [CaseRuntime] delegates [CaseEventEmitter] to [DefaultCaseEventEmitter], which
 * implements [CaseEventEmitter.subscriptionCount]. The delegation propagates the
 * property automatically, so [runtime.subscriptionCount] is safe to call here.
 * This is race-free: [subscriptionCount] is updated synchronously on each subscribe.
 */
private suspend fun awaitSubscribers(runtime: CaseRuntime, count: Int = 1) {
    runtime.subscriptionCount.first { it >= count }
}

/**
 * Integration tests for [CaseServiceImpl].
 *
 * These tests wire [CaseServiceImpl] with real in-memory repositories so that the
 * full execution path is exercised:
 *
 *   addMessage
 *     → CaseRuntime.addUserMessage  (stores MessageEvent + AgentSelectedEvent)
 *     → CaseRuntime.run             (loop starts)
 *       → processNextStep sees AgentSelectedEvent → stores AgentRunningEvent
 *       → processNextStep sees AgentRunningEvent  → calls runAgent callback
 *         → CaseServiceImpl.runAgent collects agent flow
 *           → pushes AgentFinishedEvent into the runtime's event list  ← the bug was here
 *       → processNextStep sees AgentFinishedEvent → sets stopRequested → loop exits
 *
 * The [CaseRuntimeSpec] unit tests exercise [CaseRuntime] in isolation with a mock
 * runAgent that calls pushEvents directly. These service-level tests catch regressions
 * in [CaseServiceImpl.runAgent] itself — specifically that it pushes agent-produced
 * events back into the runtime so the loop can terminate.
 */
class CaseServiceImplSpec :
    StringSpec({
        timeout = 10_000

        val namespaceId: UUID = UUID.randomUUID()
        val userId: UUID = UUID.randomUUID()
        val userActor = Actor(id = userId.toString(), displayName = "Test User", role = ActorRole.USER)
        val activeUser =
            User(
                metadata = EntityMetadata(id = userId),
                externalId = "ext-1",
                email = "test@example.com",
            )
        val agentName = "test-agent"
        val agentId: UUID = UUID.nameUUIDFromBytes(agentName.toByteArray())

        /**
         * Launches a coroutine that subscribes to the runtime's SSE flow and resolves
         * once a [CaseStatusEvent] with one of [targetStatuses] is observed. Returns the
         * [Job] so callers can `join()` after triggering the action.
         *
         * The subscription is established *before* the caller triggers any action, which
         * avoids the race inherent to a hot [SharedFlow] with `replay = 0`: events emitted
         * before the subscriber registers would otherwise be missed.
         *
         * Usage:
         * ```kotlin
         * val awaiter = scope.expectCaseStatus(runtime, CaseStatus.IDLE)
         * service.addMessage(...)   // trigger the action
         * awaiter.join()            // wait for the expected status
         * ```
         */
        fun CoroutineScope.expectCaseStatus(
            runtime: CaseRuntime,
            vararg targetStatuses: CaseStatus,
        ): Job =
            launch {
                withTimeout(8_000) {
                    runtime.events
                        .filterIsInstance<CaseStatusEvent>()
                        .first { it.status in targetStatuses }
                }
            }

        /**
         * Suspends until [CaseRuntime.isRunning] returns false, yielding the coroutine
         * between each check so the background run() coroutine can make progress.
         */
        suspend fun awaitNotRunning(runtime: CaseRuntime) {
            while (runtime.isRunning()) delay(10)
        }

        /** Build a mock Agent that immediately emits AgentFinishedEvent. */
        fun finishingAgent(): Agent =
            mockk<Agent> {
                every { metadata } returns EntityMetadata(id = agentId)
                every { name } returns agentName
                every { run(any<List<CaseEvent>>(), any()) } answers {
                    val caseId = firstArg<List<CaseEvent>>().first().caseId
                    flow {
                        emit(
                            AgentFinishedEvent(
                                namespaceId = namespaceId,
                                caseId = caseId,
                                agentId = agentId,
                                agentName = agentName,
                            ),
                        )
                    }
                }
            }

        /**
         * [AgentConfigService] mock that authorizes any agent name — Neo4j not available in unit tests.
         * Returns a list containing every agent name used across this spec so that
         * [isAgentAuthorized] always passes regardless of which agent is targeted.
         */
        val allowAllAgentConfigService: AgentConfigService = mockk {
            every { findAvailableByNamespaceIdAndUserId(any(), any(), any()) } answers {
                val ns = firstArg<UUID>()
                val name = thirdArg<String?>()
                if (name != null) listOf(AgentConfig(namespaceId = ns, name = name)) else emptyList()
            }
        }

        beforeTest {
            clearMocks(allowAllAgentConfigService, answers = false)
        }

        /** Build a fully-wired [CaseServiceImpl] backed by in-memory repositories. */
        fun buildService(
            agent: Agent = finishingAgent(),
            userService: UserService = mockk { every { findById(userId) } returns activeUser },
            defaultAgentName: String? = agentName,
            environmentAgentName: String? = null,
            agentConfigService: AgentConfigService = allowAllAgentConfigService,
        ): CaseServiceImpl {
            val namespace = Namespace(
                metadata = EntityMetadata(id = namespaceId),
                name = "test-namespace",
                defaultAgentName = defaultAgentName,
            )
            val namespaceService = mockk<NamespaceService> { every { findById(namespaceId) } returns namespace }
            val agentService =
                mockk<AgentService> {
                    every { resolveAgentName(any(), any(), any()) } returns agentName
                    every { findAgentByName(agentName, any()) } returns agent
                }
            val caseRepository = InMemoryCaseRepository()
            val caseEventService = CaseEventServiceImpl(InMemoryCaseEventRepository())
            return CaseServiceImpl(
                agentService,
                agentConfigService,
                AgentConfigProperties(agentName = environmentAgentName),
                caseRepository,
                caseEventService,
                userService,
                namespaceService,
            )
        }

        // -------------------------------------------------------------------------
        // Regression: AgentFinishedEvent must be pushed into the runtime event list
        // -------------------------------------------------------------------------

        "agent runs exactly once and case reaches IDLE after a single message" {
            // This is the direct regression test for the infinite-loop bug.
            //
            // Before the fix, CaseServiceImpl.runAgent collected agent events and persisted
            // them but never called runtime.pushEvents(). processNextStep therefore never
            // saw AgentFinishedEvent and kept re-running the agent indefinitely.
            //
            // After the fix, agent events whose caseId matches the current case are pushed
            // into the runtime's event list, allowing processNextStep to detect
            // AgentFinishedEvent and set stopRequested = true.

            var runCallCount = 0
            val countingAgent =
                mockk<Agent> {
                    every { metadata } returns EntityMetadata(id = agentId)
                    every { name } returns agentName
                    every { run(any<List<CaseEvent>>(), any()) } answers {
                        runCallCount++
                        val caseId = firstArg<List<CaseEvent>>().first().caseId
                        flow {
                            emit(
                                AgentFinishedEvent(
                                    namespaceId = namespaceId,
                                    caseId = caseId,
                                    agentId = agentId,
                                    agentName = agentName,
                                ),
                            )
                        }
                    }
                }

            val service = buildService(countingAgent)
            val case = service.create(Case(namespaceId = namespaceId))
            val runtime = service.getCaseRuntime(case.id)
            val scope = CoroutineScope(Dispatchers.IO)

            val awaiter = scope.expectCaseStatus(runtime, CaseStatus.IDLE, CaseStatus.ERROR)
            awaitSubscribers(runtime)
            service.addMessage(
                caseId = case.id,
                actor = userActor,
                content = listOf(MessageContent.Text("hello")),
            )
            awaiter.join()

            runCallCount shouldBe 1
            service.getById(case.id).status shouldBe CaseStatus.IDLE
            // isAgentAuthorized must have been called once with the agent name to authorize the redirect
            verify(exactly = 1) { allowAllAgentConfigService.findAvailableByNamespaceIdAndUserId(namespaceId, userId, agentName) }
        }

        // -------------------------------------------------------------------------
        // User validation: case must not run without a valid active user
        // -------------------------------------------------------------------------

        "case transitions to ERROR when userId is null (actor id is not a valid UUID)" {
            val actorWithNonUuidId = Actor(id = "not-a-uuid", displayName = "Unknown", role = ActorRole.USER)
            val service = buildService()
            val case = service.create(Case(namespaceId = namespaceId))
            val runtime = service.getCaseRuntime(case.id)
            val scope = CoroutineScope(Dispatchers.IO)

            val awaiter = scope.expectCaseStatus(runtime, CaseStatus.ERROR, CaseStatus.IDLE)
            awaitSubscribers(runtime)
            service.addMessage(
                caseId = case.id,
                actor = actorWithNonUuidId,
                content = listOf(MessageContent.Text("hello")),
            )
            awaiter.join()

            service.getById(case.id).status shouldBe CaseStatus.ERROR
            // userId is not a valid UUID so isAgentAuthorized is never reached (userId is null)
            verify(exactly = 0) { allowAllAgentConfigService.findAvailableByNamespaceIdAndUserId(any(), any(), any()) }
        }

        "case transitions to ERROR when userId does not resolve to a known user" {
            val unknownUserId = UUID.randomUUID()
            val actorWithUnknownUser = Actor(id = unknownUserId.toString(), displayName = "Ghost", role = ActorRole.USER)
            val userService = mockk<UserService> { every { findById(any()) } returns null }
            val service = buildService(userService = userService)
            val case = service.create(Case(namespaceId = namespaceId))
            val runtime = service.getCaseRuntime(case.id)
            val scope = CoroutineScope(Dispatchers.IO)

            val awaiter = scope.expectCaseStatus(runtime, CaseStatus.ERROR, CaseStatus.IDLE)
            awaitSubscribers(runtime)
            service.addMessage(
                caseId = case.id,
                actor = actorWithUnknownUser,
                content = listOf(MessageContent.Text("hello")),
            )
            awaiter.join()

            service.getById(case.id).status shouldBe CaseStatus.ERROR
            // isAgentAuthorized is called before runAgent fails on user lookup
            verify(exactly = 1) { allowAllAgentConfigService.findAvailableByNamespaceIdAndUserId(any(), any(), agentName) }
        }

        // -------------------------------------------------------------------------
        // Event sequence persisted to the event store
        // -------------------------------------------------------------------------

        "persisted events contain the full agent lifecycle sequence" {
            val caseEventService = CaseEventServiceImpl(InMemoryCaseEventRepository())
            val namespace = Namespace(
                metadata = EntityMetadata(id = namespaceId),
                name = "test-namespace",
                defaultAgentName = agentName,
            )
            val namespaceService = mockk<NamespaceService> { every { findById(namespaceId) } returns namespace }
            val agentService =
                mockk<AgentService> {
                    every { resolveAgentName(any(), any(), any()) } returns agentName
                    every { findAgentByName(agentName, any()) } returns finishingAgent()
                }
            val userService = mockk<UserService> { every { findById(userId) } returns activeUser }
            val service = CaseServiceImpl(
                agentService,
                allowAllAgentConfigService,
                AgentConfigProperties(),
                InMemoryCaseRepository(),
                caseEventService,
                userService,
                namespaceService,
            )
            val case = service.create(Case(namespaceId = namespaceId))
            val runtime = service.getCaseRuntime(case.id)
            val scope = CoroutineScope(Dispatchers.IO)

            val awaiter = scope.expectCaseStatus(runtime, CaseStatus.IDLE)
            awaitSubscribers(runtime)
            service.addMessage(
                caseId = case.id,
                actor = userActor,
                content = listOf(MessageContent.Text("hi")),
            )
            awaiter.join()

            val events = caseEventService.findByParent(case.id)
            events shouldHaveAtLeastSize 4

            val agentEvents =
                events.filter {
                    it is MessageEvent ||
                        it is AgentSelectedEvent ||
                        it is AgentRunningEvent ||
                        it is AgentFinishedEvent
                }

            agentEvents shouldHaveAtLeastSize 4
            agentEvents[0].shouldBeInstanceOf<MessageEvent>()
            agentEvents[1].shouldBeInstanceOf<AgentSelectedEvent>()
            agentEvents[2].shouldBeInstanceOf<AgentRunningEvent>()
            agentEvents[3].shouldBeInstanceOf<AgentFinishedEvent>()
            // isAgentAuthorized called once for the AgentSelectedEvent -> AgentRunningEvent transition
            verify(exactly = 1) { allowAllAgentConfigService.findAvailableByNamespaceIdAndUserId(namespaceId, userId, agentName) }
        }

        // -------------------------------------------------------------------------
        // handleStatusChange emits CaseStatusEvent on the runtime's SSE Flow
        // -------------------------------------------------------------------------
        //
        // These tests are the direct guard for the emit in handleStatusChange:
        //
        //   activeRuntimes[caseId]?.let {
        //       it.emitEvent(savedStatusEvent)   ← this line must exist
        //       ...
        //   }
        //
        // Removing that call leaves the persistence tests (status == IDLE) green but
        // breaks these Flow-subscription tests, because no CaseStatusEvent ever
        // appears in runtime.events.

        "handleStatusChange emits RUNNING then IDLE CaseStatusEvents on the runtime Flow" {
            // Subscribe to the runtime's events Flow BEFORE triggering any status change.
            // We collect CaseStatusEvents until we have seen IDLE, then stop.
            //
            // This test fails if the emitEvent(savedStatusEvent) call is removed from
            // handleStatusChange, because no CaseStatusEvent would ever arrive on the Flow
            // (the persistence-based assertion `status == IDLE` would still pass).

            val service = buildService()
            val case = service.create(Case(namespaceId = namespaceId))
            val runtime = service.getCaseRuntime(case.id)

            val collectedStatuses = mutableListOf<CaseStatus>()
            val collectorScope = CoroutineScope(Dispatchers.IO)
            val collectJob: Job =
                collectorScope.launch {
                    withTimeout(8_000) {
                        runtime.events
                            .filterIsInstance<CaseStatusEvent>()
                            // takeWhile completes the flow cleanly once IDLE is seen.
                            .takeWhile { event ->
                                collectedStatuses.add(event.status)
                                event.status != CaseStatus.IDLE
                            }.toList()
                        // Add IDLE itself: takeWhile consumed it without adding.
                        collectedStatuses.add(CaseStatus.IDLE)
                    }
                }

            // Wait until the collector coroutine is actually subscribed to the SharedFlow
            // before sending the message. subscriptionCount is updated synchronously on
            // subscribe, so this is race-free unlike an arbitrary delay.
            awaitSubscribers(runtime)

            service.addMessage(
                caseId = case.id,
                actor = userActor,
                content = listOf(MessageContent.Text("hello")),
            )

            collectJob.join()

            collectedStatuses.contains(CaseStatus.RUNNING) shouldBe true
            collectedStatuses.contains(CaseStatus.IDLE) shouldBe true
            // RUNNING must precede IDLE
            collectedStatuses.indexOf(CaseStatus.RUNNING) shouldBe 0
        }

        "handleStatusChange emits KILLED CaseStatusEvent on the runtime Flow before eviction" {
            // killCase() calls handleStatusChange(KILLED).
            // The implementation must emit the status event BEFORE removing the runtime
            // from activeRuntimes, so that SSE clients receive the final status.
            //
            // This test fails if emitEvent(savedStatusEvent) is removed from
            // handleStatusChange, because the KILLED event would never arrive on the Flow.

            val service = buildService()
            val case = service.create(Case(namespaceId = namespaceId))
            val runtime = service.getCaseRuntime(case.id)

            val killedEventReceived =
                java.util.concurrent.atomic
                    .AtomicBoolean(false)
            val collectorScope = CoroutineScope(Dispatchers.IO)
            val collectJob: Job =
                collectorScope.launch {
                    withTimeout(5_000) {
                        runtime.events
                            .filterIsInstance<CaseStatusEvent>()
                            .takeWhile { event -> event.status != CaseStatus.KILLED }
                            .toList()
                        // takeWhile completed — the KILLED event was seen.
                        killedEventReceived.set(true)
                    }
                }

            awaitSubscribers(runtime)

            service.killCase(case.id)

            collectJob.join()

            killedEventReceived.get() shouldBe true
        }

        "handleStatusChange emits ERROR CaseStatusEvent on the runtime Flow" {
            // Force the case into ERROR status via update() — this routes through
            // handleStatusChange, which must call emitEvent(savedStatusEvent).
            //
            // This test fails if emitEvent(savedStatusEvent) is removed from
            // handleStatusChange, because the ERROR event would never arrive on the Flow.

            val service = buildService()
            val case = service.create(Case(namespaceId = namespaceId))
            val runtime = service.getCaseRuntime(case.id)

            val errorEventReceived =
                java.util.concurrent.atomic
                    .AtomicBoolean(false)
            val collectorScope = CoroutineScope(Dispatchers.IO)
            val collectJob: Job =
                collectorScope.launch {
                    withTimeout(5_000) {
                        runtime.events
                            .filterIsInstance<CaseStatusEvent>()
                            .takeWhile { event -> event.status != CaseStatus.ERROR }
                            .toList()
                        // takeWhile completed — the ERROR event was seen.
                        errorEventReceived.set(true)
                    }
                }

            awaitSubscribers(runtime)

            // Route the ERROR status change through handleStatusChange.
            service.update(case.copy(status = CaseStatus.ERROR))

            collectJob.join()

            errorEventReceived.get() shouldBe true
        }

        // -------------------------------------------------------------------------
        // TextChunkEvent must not be persisted
        // -------------------------------------------------------------------------

        "TransientCaseEvents are not persisted but do appear on the SSE flow" {
            // TransientCaseEvents (TextChunkEvent, ThinkingEvent, ...) must reach the
            // SSE flow for real-time display but must NOT be written to the event store
            // and must NOT be pushed into the runtime's in-memory event list.

            val caseEventService = CaseEventServiceImpl(InMemoryCaseEventRepository())
            val chunkingAgent =
                mockk<Agent> {
                    every { metadata } returns EntityMetadata(id = agentId)
                    every { name } returns agentName
                    every { run(any<List<CaseEvent>>(), any()) } answers {
                        val caseId = firstArg<List<CaseEvent>>().first().caseId
                        flow {
                            emit(ThinkingEvent(namespaceId = namespaceId, caseId = caseId))
                            emit(TextChunkEvent(namespaceId = namespaceId, caseId = caseId, chunk = "Hello"))
                            emit(TextChunkEvent(namespaceId = namespaceId, caseId = caseId, chunk = " world"))
                            emit(AgentFinishedEvent(namespaceId = namespaceId, caseId = caseId, agentId = agentId, agentName = agentName))
                        }
                    }
                }

            val namespace = Namespace(
                metadata = EntityMetadata(id = namespaceId),
                name = "test-namespace",
                defaultAgentName = agentName,
            )
            val namespaceService = mockk<NamespaceService> { every { findById(namespaceId) } returns namespace }
            val agentService =
                mockk<AgentService> {
                    every { resolveAgentName(agentName, namespaceId, any()) } returns agentName
                    every { findAgentByName(agentName, any()) } returns chunkingAgent
                }
            val userService = mockk<UserService> { every { findById(userId) } returns activeUser }
            val service = CaseServiceImpl(agentService, allowAllAgentConfigService, AgentConfigProperties(), InMemoryCaseRepository(), caseEventService, userService, namespaceService)
            val case = service.create(Case(namespaceId = namespaceId))
            val runtime = service.getCaseRuntime(case.id)

            // Subscribe to both the IDLE gate and text chunks BEFORE sending the message
            // so no events are missed on the hot SharedFlow (replay = 0).
            //
            // Both collectors run until the case reaches IDLE: the chunk collector stops
            // on the AgentFinishedEvent (which immediately precedes IDLE), the idle
            // collector stops on the CaseStatusEvent(IDLE). This avoids the race where
            // chunkCollectJob.cancel() fires before the dispatcher has delivered all
            // TextChunkEvents to the collector coroutine.
            val collectedChunks = mutableListOf<TextChunkEvent>()
            val collectorScope = CoroutineScope(Dispatchers.IO)
            val idleJob: Job =
                collectorScope.launch {
                    withTimeout(8_000) {
                        runtime.events
                            .filterIsInstance<CaseStatusEvent>()
                            .takeWhile { it.status != CaseStatus.IDLE }
                            .toList()
                    }
                }
            // Collect TextChunkEvents until AgentFinishedEvent signals the end of the
            // agent turn. This bounds the collector without relying on external cancel().
            val chunkCollectJob: Job =
                collectorScope.launch {
                    withTimeout(8_000) {
                        runtime.events
                            .takeWhile { it !is AgentFinishedEvent }
                            .filterIsInstance<TextChunkEvent>()
                            .toList()
                            .also { collectedChunks.addAll(it) }
                    }
                }

            // Wait until both collectors (idleJob + chunkCollectJob) are subscribed.
            awaitSubscribers(runtime, count = 2)

            service.addMessage(
                caseId = case.id,
                actor = userActor,
                content = listOf(MessageContent.Text("hi")),
            )

            idleJob.join()
            chunkCollectJob.join() // both collectors are self-terminating — no cancel() needed

            service.getById(case.id).status shouldBe CaseStatus.IDLE

            val persisted = caseEventService.findByParent(case.id)
            // Orchestration events must still be persisted
            persisted.filterIsInstance<AgentFinishedEvent>() shouldHaveAtLeastSize 1

            // TransientCaseEvents must NOT be in the persistent store
            persisted.filterIsInstance<ThinkingEvent>() shouldBe emptyList()
            persisted.filterIsInstance<TextChunkEvent>() shouldBe emptyList()

            // TextChunkEvents MUST have arrived on the SSE flow
            collectedChunks.size shouldBe 2
            collectedChunks[0].chunk shouldBe "Hello"
            collectedChunks[1].chunk shouldBe " world"
        }

        // -------------------------------------------------------------------------
        // Sticky-agent behaviour: second message without @mention reuses last agent
        // -------------------------------------------------------------------------

        // -------------------------------------------------------------------------
        // Default agent routing — environment-level fallback
        // -------------------------------------------------------------------------

        "first message without @mention routes to environment default agent when namespace has none" {
            val caseEventService = CaseEventServiceImpl(InMemoryCaseEventRepository())
            val namespace = Namespace(
                metadata = EntityMetadata(id = namespaceId),
                name = "test-namespace",
                defaultAgentName = null,  // no namespace-level default
            )
            val namespaceService = mockk<NamespaceService> { every { findById(namespaceId) } returns namespace }
            val agentService = mockk<AgentService> {
                every { resolveAgentName(agentName, namespaceId, any()) } returns agentName
                every { findAgentByName(agentName, any()) } returns finishingAgent()
            }
            val userService = mockk<UserService> { every { findById(userId) } returns activeUser }
            val service = CaseServiceImpl(
                agentService,
                allowAllAgentConfigService,
                AgentConfigProperties(agentName = agentName),  // environment-level default
                InMemoryCaseRepository(),
                caseEventService,
                userService,
                namespaceService,
            )
            val case = service.create(Case(namespaceId = namespaceId))
            val runtime = service.getCaseRuntime(case.id)
            val scope = CoroutineScope(Dispatchers.IO)

            val awaiter = scope.expectCaseStatus(runtime, CaseStatus.IDLE, CaseStatus.ERROR)
            awaitSubscribers(runtime)
            service.addMessage(
                caseId = case.id,
                actor = userActor,
                content = listOf(MessageContent.Text("hello")),
            )
            awaiter.join()

            service.getById(case.id).status shouldBe CaseStatus.IDLE
            val persistedEvents = caseEventService.findByParent(case.id)
            persistedEvents.filterIsInstance<AgentSelectedEvent>().last().agentName shouldBe agentName
            persistedEvents.filterIsInstance<WarnEvent>() shouldBe emptyList()
            verify(exactly = 1) { allowAllAgentConfigService.findAvailableByNamespaceIdAndUserId(namespaceId, userId, agentName) }
        }

        "namespace default agent takes precedence over environment default agent" {
            val namespaceDefaultName = "namespace-agent"
            val environmentDefaultName = "env-agent"
            val namespaceAgentId = UUID.nameUUIDFromBytes(namespaceDefaultName.toByteArray())
            val caseEventService = CaseEventServiceImpl(InMemoryCaseEventRepository())
            val namespace = Namespace(
                metadata = EntityMetadata(id = namespaceId),
                name = "test-namespace",
                defaultAgentName = namespaceDefaultName,
            )
            val namespaceService = mockk<NamespaceService> { every { findById(namespaceId) } returns namespace }
            val namespaceAgent = mockk<Agent> {
                every { metadata } returns EntityMetadata(id = namespaceAgentId)
                every { name } returns namespaceDefaultName
                every { run(any<List<CaseEvent>>(), any()) } answers {
                    val caseId = firstArg<List<CaseEvent>>().first().caseId
                    flow {
                        emit(AgentFinishedEvent(namespaceId = namespaceId, caseId = caseId, agentId = namespaceAgentId, agentName = namespaceDefaultName))
                    }
                }
            }
            val agentService = mockk<AgentService> {
                every { resolveAgentName(namespaceDefaultName, namespaceId, any()) } returns namespaceDefaultName
                every { findAgentByName(namespaceDefaultName, any()) } returns namespaceAgent
            }
            val userService = mockk<UserService> { every { findById(userId) } returns activeUser }
            val service = CaseServiceImpl(
                agentService,
                allowAllAgentConfigService,
                AgentConfigProperties(agentName = environmentDefaultName),
                InMemoryCaseRepository(),
                caseEventService,
                userService,
                namespaceService,
            )
            val case = service.create(Case(namespaceId = namespaceId))
            val runtime = service.getCaseRuntime(case.id)
            val scope = CoroutineScope(Dispatchers.IO)

            val awaiter = scope.expectCaseStatus(runtime, CaseStatus.IDLE, CaseStatus.ERROR)
            awaitSubscribers(runtime)
            service.addMessage(
                caseId = case.id,
                actor = userActor,
                content = listOf(MessageContent.Text("hello")),
            )
            awaiter.join()

            service.getById(case.id).status shouldBe CaseStatus.IDLE
            val persistedEvents = caseEventService.findByParent(case.id)
            // namespace agent was selected, not the environment default
            persistedEvents.filterIsInstance<AgentSelectedEvent>().last().agentName shouldBe namespaceDefaultName
            verify(exactly = 1) { allowAllAgentConfigService.findAvailableByNamespaceIdAndUserId(namespaceId, userId, namespaceDefaultName) }
        }

        "no default agent at any level produces WarnEvent and stops" {
            val caseEventService = CaseEventServiceImpl(InMemoryCaseEventRepository())
            val namespace = Namespace(
                metadata = EntityMetadata(id = namespaceId),
                name = "test-namespace",
                defaultAgentName = null,
            )
            val namespaceService = mockk<NamespaceService> { every { findById(namespaceId) } returns namespace }
            val agentService = mockk<AgentService>(relaxed = true)
            val userService = mockk<UserService> { every { findById(userId) } returns activeUser }
            val service = CaseServiceImpl(
                agentService,
                allowAllAgentConfigService,
                AgentConfigProperties(agentName = null),  // no environment default either
                InMemoryCaseRepository(),
                caseEventService,
                userService,
                namespaceService,
            )
            val case = service.create(Case(namespaceId = namespaceId))
            val runtime = service.getCaseRuntime(case.id)
            val scope = CoroutineScope(Dispatchers.IO)

            val awaiter = scope.expectCaseStatus(runtime, CaseStatus.IDLE, CaseStatus.ERROR)
            awaitSubscribers(runtime)
            service.addMessage(
                caseId = case.id,
                actor = userActor,
                content = listOf(MessageContent.Text("hello")),
            )
            awaiter.join()

            service.getById(case.id).status shouldBe CaseStatus.IDLE
            val persistedEvents = caseEventService.findByParent(case.id)
            persistedEvents.filterIsInstance<WarnEvent>() shouldHaveAtLeastSize 1
            persistedEvents.filterIsInstance<AgentSelectedEvent>() shouldBe emptyList()
            // no AgentSelectedEvent means isAgentAuthorized is never reached
            verify(exactly = 0) { allowAllAgentConfigService.findAvailableByNamespaceIdAndUserId(any(), any(), any()) }
        }

        // -------------------------------------------------------------------------
        // Default agent routing — basic
        // -------------------------------------------------------------------------

        "first message without @mention routes to namespace default agent" {
            val service = buildService()
            val case = service.create(Case(namespaceId = namespaceId))
            val runtime = service.getCaseRuntime(case.id)
            val scope = CoroutineScope(Dispatchers.IO)

            val awaiter = scope.expectCaseStatus(runtime, CaseStatus.IDLE, CaseStatus.ERROR)
            awaitSubscribers(runtime)
            service.addMessage(
                caseId = case.id,
                actor = userActor,
                content = listOf(MessageContent.Text("hello")),
            )
            awaiter.join()

            service.getById(case.id).status shouldBe CaseStatus.IDLE
            verify(exactly = 1) { allowAllAgentConfigService.findAvailableByNamespaceIdAndUserId(namespaceId, userId, agentName) }
        }

        "first message without @mention produces WarnEvent and stops when namespace has no default agent" {
            // Wire the service manually to keep a reference to the event store.
            val caseEventService = CaseEventServiceImpl(InMemoryCaseEventRepository())
            val namespace = Namespace(
                metadata = EntityMetadata(id = namespaceId),
                name = "test-namespace",
                defaultAgentName = null,
            )
            val namespaceService = mockk<NamespaceService> { every { findById(namespaceId) } returns namespace }
            // resolveAgentName is never reached because selectDefaultAgent short-circuits on null
            val agentService = mockk<AgentService>(relaxed = true)
            val userService = mockk<UserService> { every { findById(userId) } returns activeUser }
            val service = CaseServiceImpl(
                agentService,
                allowAllAgentConfigService,
                AgentConfigProperties(),
                InMemoryCaseRepository(),
                caseEventService,
                userService,
                namespaceService,
            )
            val case = service.create(Case(namespaceId = namespaceId))
            val runtime = service.getCaseRuntime(case.id)
            val scope = CoroutineScope(Dispatchers.IO)

            val awaiter = scope.expectCaseStatus(runtime, CaseStatus.IDLE, CaseStatus.ERROR)
            awaitSubscribers(runtime)
            service.addMessage(
                caseId = case.id,
                actor = userActor,
                content = listOf(MessageContent.Text("hello")),
            )
            awaiter.join()

            // Case must be IDLE (not ERROR): no default is a configuration issue, not a crash
            service.getById(case.id).status shouldBe CaseStatus.IDLE
            // A WarnEvent must have been persisted — it is the only event besides the MessageEvent
            val persistedEvents = caseEventService.findByParent(case.id)
            persistedEvents.filterIsInstance<WarnEvent>() shouldHaveAtLeastSize 1
            // No AgentSelectedEvent: routing stopped at the WarnEvent
            persistedEvents.filterIsInstance<AgentSelectedEvent>() shouldBe emptyList()
            // no AgentSelectedEvent means isAgentAuthorized is never reached
            verify(exactly = 0) { allowAllAgentConfigService.findAvailableByNamespaceIdAndUserId(any(), any(), any()) }
        }

        "last active agent unavailable falls back to namespace default" {
            val unavailableAgentName = "old-agent"
            val caseEventService = CaseEventServiceImpl(InMemoryCaseEventRepository())
            val namespace = Namespace(
                metadata = EntityMetadata(id = namespaceId),
                name = "test-namespace",
                defaultAgentName = agentName,
            )
            val namespaceService = mockk<NamespaceService> { every { findById(namespaceId) } returns namespace }
            // resolveAgentName call sequence:
            //   turn 1, @mention path: resolveAgentName(unavailableAgentName) -> unavailableAgentName (found)
            //   turn 2, sticky-agent availability check: resolveAgentName(unavailableAgentName) -> null (gone)
            //   turn 2, default resolution: resolveAgentName(agentName) -> agentName (found)
            val resolveCallCount = java.util.concurrent.atomic.AtomicInteger(0)
            val agentService = mockk<AgentService> {
                every { resolveAgentName(unavailableAgentName, namespaceId, any()) } answers {
                    when (resolveCallCount.incrementAndGet()) {
                        1 -> unavailableAgentName  // turn 1: @mention resolves
                        else -> null              // turn 2: sticky-agent check fails
                    }
                }
                every { resolveAgentName(agentName, namespaceId, any()) } returns agentName
                every { findAgentByName(any(), any()) } returns finishingAgent()
            }
            val userServiceMock = mockk<UserService> { every { findById(userId) } returns activeUser }
            val service = CaseServiceImpl(
                agentService,
                allowAllAgentConfigService,
                AgentConfigProperties(),
                InMemoryCaseRepository(),
                caseEventService,
                userServiceMock,
                namespaceService,
            )
            val case = service.create(Case(namespaceId = namespaceId))
            val runtime = service.getCaseRuntime(case.id)
            val scope = CoroutineScope(Dispatchers.IO)

            // First turn: explicit @mention of the old agent — resolves and runs normally
            val firstIdle = scope.expectCaseStatus(runtime, CaseStatus.IDLE)
            awaitSubscribers(runtime)
            service.addMessage(
                caseId = case.id,
                actor = userActor,
                content = listOf(MessageContent.Text("@$unavailableAgentName hello")),
            )
            firstIdle.join()
            awaitNotRunning(runtime)

            // Second turn: no @mention, old agent is gone -> WarnEvent + fallback to default
            val secondIdle = scope.expectCaseStatus(runtime, CaseStatus.IDLE)
            awaitSubscribers(runtime)
            service.addMessage(
                caseId = case.id,
                actor = userActor,
                content = listOf(MessageContent.Text("follow-up")),
            )
            secondIdle.join()

            service.getById(case.id).status shouldBe CaseStatus.IDLE
            // WarnEvent must have been persisted during the second turn
            val persistedEvents = caseEventService.findByParent(case.id)
            persistedEvents.filterIsInstance<WarnEvent>() shouldHaveAtLeastSize 1
            // Default agent was ultimately selected after the warn
            persistedEvents.filterIsInstance<AgentSelectedEvent>().last().agentName shouldBe agentName
            // turn 1: authorized for unavailableAgentName, turn 2: authorized for agentName (fallback)
            verify(exactly = 1) { allowAllAgentConfigService.findAvailableByNamespaceIdAndUserId(namespaceId, userId, unavailableAgentName) }
            verify(exactly = 1) { allowAllAgentConfigService.findAvailableByNamespaceIdAndUserId(namespaceId, userId, agentName) }
        }

        "second message without @mention uses the same agent as the first" {
            // Regression test for the sticky-agent feature.
            //
            // When a user sends `@some-agent hello` and then `follow-up question`,
            // the second message must be handled by `some-agent`, not the default agent.
            //
            // Before the fix, selectAgent() ignored the event history and always fell
            // back to getDefaultAgentName(), so the second message was always routed
            // to the default agent regardless of any prior @mention.

            val defaultAgentName = "default-agent"
            val selectedAgentName = "selected-agent"
            val selectedAgentId = UUID.nameUUIDFromBytes(selectedAgentName.toByteArray())
            val agentCallNames = mutableListOf<String>()

            val selectedAgent =
                mockk<Agent> {
                    every { metadata } returns EntityMetadata(id = selectedAgentId)
                    every { name } returns selectedAgentName
                    every { run(any<List<CaseEvent>>(), any()) } answers {
                        agentCallNames.add(selectedAgentName)
                        val caseId = firstArg<List<CaseEvent>>().first().caseId
                        flow {
                            emit(
                                AgentFinishedEvent(
                                    namespaceId = namespaceId,
                                    caseId = caseId,
                                    agentId = selectedAgentId,
                                    agentName = selectedAgentName,
                                ),
                            )
                        }
                    }
                }

            val namespace = Namespace(
                metadata = EntityMetadata(id = namespaceId),
                name = "test-namespace",
                defaultAgentName = defaultAgentName,
            )
            val namespaceService = mockk<NamespaceService> { every { findById(namespaceId) } returns namespace }
            val agentService =
                mockk<AgentService> {
                    // @selected-agent resolves to selectedAgentName
                    every { resolveAgentName(selectedAgentName, any(), any()) } returns selectedAgentName
                    // no other mention resolution needed
                    every { findAgentByName(selectedAgentName, any()) } returns selectedAgent
                }
            val caseRepository = InMemoryCaseRepository()
            val caseEventService = CaseEventServiceImpl(InMemoryCaseEventRepository())
            val userService = mockk<UserService> { every { findById(userId) } returns activeUser }
            val service = CaseServiceImpl(agentService, allowAllAgentConfigService, AgentConfigProperties(), caseRepository, caseEventService, userService, namespaceService)
            val case = service.create(Case(namespaceId = namespaceId))
            val runtime = service.getCaseRuntime(case.id)
            val scope = CoroutineScope(Dispatchers.IO)

            // First message: explicit @mention
            val firstIdle = scope.expectCaseStatus(runtime, CaseStatus.IDLE)
            service.addMessage(
                caseId = case.id,
                actor = userActor,
                content = listOf(MessageContent.Text("@$selectedAgentName hello")),
            )
            firstIdle.join()
            service.getById(case.id).status shouldBe CaseStatus.IDLE

            // Wait for run() to fully exit before sending the second message.
            awaitNotRunning(runtime)

            // Second message: no @mention — must stick with selectedAgent.
            // Subscribe before sending so the second IDLE is not missed.
            val secondIdle = scope.expectCaseStatus(runtime, CaseStatus.IDLE)
            service.addMessage(
                caseId = case.id,
                actor = userActor,
                content = listOf(MessageContent.Text("follow-up question")),
            )
            secondIdle.join()

            agentCallNames shouldBe listOf(selectedAgentName, selectedAgentName)
            // called once per message for the same agent
            verify(exactly = 2) { allowAllAgentConfigService.findAvailableByNamespaceIdAndUserId(namespaceId, userId, selectedAgentName) }
        }

        // -------------------------------------------------------------------------
        // @mention parsing: agent name must not include URL or non-ASCII whitespace
        // -------------------------------------------------------------------------

        "@mention followed by a URL selects the agent and ignores the URL" {
            // Regression: MENTION_REGEX used \S+ which captures everything up to the first
            // ASCII whitespace. A non-breaking space (U+00A0) or similar Unicode whitespace
            // between the agent name and the URL would cause the entire string
            // `inspector https://...` to be captured as the agent name.
            //
            // The fix uses [\w-]+ which stops at the first non-word, non-hyphen character,
            // so `@inspector https://example.com` correctly extracts `inspector` only.

            val inspectorName = "inspector"
            val inspectorId = UUID.nameUUIDFromBytes(inspectorName.toByteArray())
            val caseEventService = CaseEventServiceImpl(InMemoryCaseEventRepository())
            val namespace = Namespace(
                metadata = EntityMetadata(id = namespaceId),
                name = "test-namespace",
                defaultAgentName = agentName,
            )
            val namespaceService = mockk<NamespaceService> { every { findById(namespaceId) } returns namespace }
            val inspectorAgent = mockk<Agent> {
                every { metadata } returns EntityMetadata(id = inspectorId)
                every { name } returns inspectorName
                every { run(any<List<CaseEvent>>(), any()) } answers {
                    val caseId = firstArg<List<CaseEvent>>().first().caseId
                    flow {
                        emit(AgentFinishedEvent(namespaceId = namespaceId, caseId = caseId, agentId = inspectorId, agentName = inspectorName))
                    }
                }
            }
            val agentService = mockk<AgentService> {
                // Only `inspector` resolves — the full string with URL must NOT be passed here
                every { resolveAgentName(inspectorName, namespaceId, any()) } returns inspectorName
                every { findAgentByName(inspectorName, any()) } returns inspectorAgent
            }
            val userService = mockk<UserService> { every { findById(userId) } returns activeUser }
            val service = CaseServiceImpl(
                agentService,
                allowAllAgentConfigService,
                AgentConfigProperties(),
                InMemoryCaseRepository(),
                caseEventService,
                userService,
                namespaceService,
            )
            val case = service.create(Case(namespaceId = namespaceId))
            val runtime = service.getCaseRuntime(case.id)
            val scope = CoroutineScope(Dispatchers.IO)

            val awaiter = scope.expectCaseStatus(runtime, CaseStatus.IDLE, CaseStatus.ERROR)
            awaitSubscribers(runtime)

            // Regular ASCII space between name and URL — the common case
            service.addMessage(
                caseId = case.id,
                actor = userActor,
                content = listOf(MessageContent.Text("@$inspectorName https://example.com/some/path")),
            )
            awaiter.join()

            service.getById(case.id).status shouldBe CaseStatus.IDLE
            val persistedEvents = caseEventService.findByParent(case.id)
            // The selected agent must be `inspector`, not `inspector https://...`
            persistedEvents.filterIsInstance<AgentSelectedEvent>().last().agentName shouldBe inspectorName
            verify(exactly = 1) { allowAllAgentConfigService.findAvailableByNamespaceIdAndUserId(namespaceId, userId, inspectorName) }
        }

        "@mention followed by a URL with non-breaking space selects the agent and ignores the URL" {
            // Non-breaking space (U+00A0) is not matched by \s in Java/Kotlin regex,
            // so \S+ would consume the entire `inspector\u00A0https://...` string.
            // The fix [\w-]+ stops at the non-breaking space (which is not \w or -).

            val inspectorName = "inspector"
            val inspectorId = UUID.nameUUIDFromBytes(inspectorName.toByteArray())
            val caseEventService = CaseEventServiceImpl(InMemoryCaseEventRepository())
            val namespace = Namespace(
                metadata = EntityMetadata(id = namespaceId),
                name = "test-namespace",
                defaultAgentName = agentName,
            )
            val namespaceService = mockk<NamespaceService> { every { findById(namespaceId) } returns namespace }
            val inspectorAgent = mockk<Agent> {
                every { metadata } returns EntityMetadata(id = inspectorId)
                every { name } returns inspectorName
                every { run(any<List<CaseEvent>>(), any()) } answers {
                    val caseId = firstArg<List<CaseEvent>>().first().caseId
                    flow {
                        emit(AgentFinishedEvent(namespaceId = namespaceId, caseId = caseId, agentId = inspectorId, agentName = inspectorName))
                    }
                }
            }
            val agentService = mockk<AgentService> {
                every { resolveAgentName(inspectorName, namespaceId, any()) } returns inspectorName
                every { findAgentByName(inspectorName, any()) } returns inspectorAgent
            }
            val userService = mockk<UserService> { every { findById(userId) } returns activeUser }
            val service = CaseServiceImpl(
                agentService,
                allowAllAgentConfigService,
                AgentConfigProperties(),
                InMemoryCaseRepository(),
                caseEventService,
                userService,
                namespaceService,
            )
            val case = service.create(Case(namespaceId = namespaceId))
            val runtime = service.getCaseRuntime(case.id)
            val scope = CoroutineScope(Dispatchers.IO)

            val awaiter = scope.expectCaseStatus(runtime, CaseStatus.IDLE, CaseStatus.ERROR)
            awaitSubscribers(runtime)

            // Non-breaking space (U+00A0) — the pathological case that triggered the bug
            val messageWithNbsp = "@$inspectorName\u00A0https://example.com/some/path"
            service.addMessage(
                caseId = case.id,
                actor = userActor,
                content = listOf(MessageContent.Text(messageWithNbsp)),
            )
            awaiter.join()

            service.getById(case.id).status shouldBe CaseStatus.IDLE
            val persistedEvents = caseEventService.findByParent(case.id)
            persistedEvents.filterIsInstance<AgentSelectedEvent>().last().agentName shouldBe inspectorName
            verify(exactly = 1) { allowAllAgentConfigService.findAvailableByNamespaceIdAndUserId(namespaceId, userId, inspectorName) }
        }

        "agent runs once per message when two messages are sent sequentially" {
            var runCallCount = 0
            val countingAgent =
                mockk<Agent> {
                    every { metadata } returns EntityMetadata(id = agentId)
                    every { name } returns agentName
                    every { run(any<List<CaseEvent>>(), any()) } answers {
                        runCallCount++
                        val caseId = firstArg<List<CaseEvent>>().first().caseId
                        flow {
                            emit(
                                AgentFinishedEvent(
                                    namespaceId = namespaceId,
                                    caseId = caseId,
                                    agentId = agentId,
                                    agentName = agentName,
                                ),
                            )
                        }
                    }
                }

            val service = buildService(countingAgent)
            val case = service.create(Case(namespaceId = namespaceId))
            val runtime = service.getCaseRuntime(case.id)
            val scope = CoroutineScope(Dispatchers.IO)

            // First message
            val firstIdle = scope.expectCaseStatus(runtime, CaseStatus.IDLE)
            awaitSubscribers(runtime)
            service.addMessage(
                caseId = case.id,
                actor = userActor,
                content = listOf(MessageContent.Text("first")),
            )
            firstIdle.join()
            service.getById(case.id).status shouldBe CaseStatus.IDLE
            runCallCount shouldBe 1

            // Wait until run() has fully exited (runInFlight cleared) before sending
            // the second message. The runtime stays alive (IDLE is non-terminal), but
            // run() must have exited so the AtomicBoolean guard allows re-entry.
            awaitNotRunning(runtime)

            // Second message — subscribe before sending so the second IDLE is not missed.
            val secondIdle = scope.expectCaseStatus(runtime, CaseStatus.IDLE)
            awaitSubscribers(runtime)
            service.addMessage(
                caseId = case.id,
                actor = userActor,
                content = listOf(MessageContent.Text("second")),
            )
            secondIdle.join()

            runCallCount shouldBe 2
            service.getById(case.id).status shouldBe CaseStatus.IDLE
            verify(exactly = 2) { allowAllAgentConfigService.findAvailableByNamespaceIdAndUserId(namespaceId, userId, agentName) }
        }
    })
