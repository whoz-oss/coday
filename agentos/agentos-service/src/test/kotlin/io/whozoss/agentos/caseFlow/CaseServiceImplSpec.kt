package io.whozoss.agentos.caseFlow

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.agent.AgentConfigProperties
import io.whozoss.agentos.agent.AgentService
import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.caseEvent.CaseEventServiceImpl
import io.whozoss.agentos.caseEvent.InMemoryCaseEventRepository
import io.whozoss.agentos.caseFlow.CaseConfigProperties
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.permissions.PermissionService
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
private suspend fun awaitSubscribers(
    runtime: CaseRuntime,
    count: Int = 1,
) {
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
                every { id } returns agentId
                every { llmProvider } returns "test-provider"
                every { llmModel } returns "test-model"
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
        val allowAllAgentConfigService: AgentConfigService =
            mockk {
                every { findDeployedByNamespaceIdAndUserIdAndName(any(), any(), any()) } answers {
                    val ns = firstArg<UUID>()
                    val name = thirdArg<String?>()
                    if (name != null) listOf(AgentConfig(namespaceId = ns, name = name)) else emptyList()
                }
            }

        beforeTest {
            clearMocks(allowAllAgentConfigService, answers = false)
        }

        /** No-op naming service — tests do not exercise automatic case naming. */
        val noOpCaseNamingService: CaseNamingService = mockk(relaxed = true)

        val permissionService: PermissionService = mockk(relaxed = true)

        /** Build a fully-wired [CaseServiceImpl] backed by in-memory repositories. */
        fun buildService(
            agent: Agent = finishingAgent(),
            userService: UserService =
                mockk {
                    every { findById(userId) } returns activeUser
                    every { getById(userId) } returns activeUser
                },
            defaultAgentName: String? = agentName,
            environmentAgentName: String? = null,
            agentConfigService: AgentConfigService = allowAllAgentConfigService,
            idleEvictionGraceMs: Long = 5_000L,
        ): CaseServiceImpl {
            val namespace =
                Namespace(
                    metadata = EntityMetadata(id = namespaceId),
                    name = "test-namespace",
                    defaultAgentName = defaultAgentName,
                )
            val namespaceService = mockk<NamespaceService> { every { findById(namespaceId) } returns namespace }
            val agentService =
                mockk<AgentService> {
                    every { resolveAgentName(any(), any(), any()) } returns agentName
                    coEvery { findAgentByName(agentName, any(), any()) } returns agent
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
                caseConfig = CaseConfigProperties(idleEvictionGraceMs = idleEvictionGraceMs),
                permissionService = permissionService,
                caseNamingService = noOpCaseNamingService,
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
                    every { id } returns agentId
                    every { llmProvider } returns "test-provider"
                    every { llmModel } returns "test-model"
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
            verify(exactly = 1) { allowAllAgentConfigService.findDeployedByNamespaceIdAndUserIdAndName(namespaceId, userId, agentName) }
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
            verify(exactly = 0) { allowAllAgentConfigService.findDeployedByNamespaceIdAndUserIdAndName(any(), any(), any()) }
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
            verify(exactly = 1) { allowAllAgentConfigService.findDeployedByNamespaceIdAndUserIdAndName(any(), any(), agentName) }
        }

        // -------------------------------------------------------------------------
        // Event sequence persisted to the event store
        // -------------------------------------------------------------------------

        "persisted events contain the full agent lifecycle sequence" {
            val caseEventService = CaseEventServiceImpl(InMemoryCaseEventRepository())
            val namespace =
                Namespace(
                    metadata = EntityMetadata(id = namespaceId),
                    name = "test-namespace",
                    defaultAgentName = agentName,
                )
            val namespaceService = mockk<NamespaceService> { every { findById(namespaceId) } returns namespace }
            val agentService =
                mockk<AgentService> {
                    every { resolveAgentName(any(), any(), any()) } returns agentName
                    coEvery { findAgentByName(agentName, any(), any()) } returns finishingAgent()
                }
            val userService = mockk<UserService> { every { findById(userId) } returns activeUser }
            val service =
                CaseServiceImpl(
                    agentService,
                    allowAllAgentConfigService,
                    AgentConfigProperties(),
                    InMemoryCaseRepository(),
                    caseEventService,
                    userService,
                    namespaceService,
                    caseConfig = CaseConfigProperties(),
                    permissionService = permissionService,
                    caseNamingService = noOpCaseNamingService,
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
            verify(exactly = 1) { allowAllAgentConfigService.findDeployedByNamespaceIdAndUserIdAndName(namespaceId, userId, agentName) }
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
                    every { id } returns agentId
                    every { llmProvider } returns "test-provider"
                    every { llmModel } returns "test-model"
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

            val namespace =
                Namespace(
                    metadata = EntityMetadata(id = namespaceId),
                    name = "test-namespace",
                    defaultAgentName = agentName,
                )
            val namespaceService = mockk<NamespaceService> { every { findById(namespaceId) } returns namespace }
            val agentService =
                mockk<AgentService> {
                    every { resolveAgentName(agentName, namespaceId, any()) } returns agentName
                    coEvery { findAgentByName(agentName, any(), any()) } returns chunkingAgent
                }
            val userService = mockk<UserService> { every { findById(userId) } returns activeUser }
            val service =
                CaseServiceImpl(
                    agentService,
                    allowAllAgentConfigService,
                    AgentConfigProperties(),
                    InMemoryCaseRepository(),
                    caseEventService,
                    userService,
                    namespaceService,
                    caseConfig = CaseConfigProperties(),
                    permissionService = permissionService,
                    caseNamingService = noOpCaseNamingService,
                )
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
            val namespace =
                Namespace(
                    metadata = EntityMetadata(id = namespaceId),
                    name = "test-namespace",
                    defaultAgentName = null, // no namespace-level default
                )
            val namespaceService = mockk<NamespaceService> { every { findById(namespaceId) } returns namespace }
            val agentService =
                mockk<AgentService> {
                    every { resolveAgentName(agentName, namespaceId, any()) } returns agentName
                    coEvery { findAgentByName(agentName, any(), any()) } returns finishingAgent()
                }
            val userService = mockk<UserService> { every { findById(userId) } returns activeUser }
            val service =
                CaseServiceImpl(
                    agentService,
                    allowAllAgentConfigService,
                    AgentConfigProperties(agentName = agentName), // environment-level default
                    InMemoryCaseRepository(),
                    caseEventService,
                    userService,
                    namespaceService,
                    caseConfig = CaseConfigProperties(),
                    permissionService = permissionService,
                    caseNamingService = noOpCaseNamingService,
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
            verify(exactly = 1) { allowAllAgentConfigService.findDeployedByNamespaceIdAndUserIdAndName(namespaceId, userId, agentName) }
        }

        "namespace default agent takes precedence over environment default agent" {
            val namespaceDefaultName = "namespace-agent"
            val environmentDefaultName = "env-agent"
            val namespaceAgentId = UUID.nameUUIDFromBytes(namespaceDefaultName.toByteArray())
            val caseEventService = CaseEventServiceImpl(InMemoryCaseEventRepository())
            val namespace =
                Namespace(
                    metadata = EntityMetadata(id = namespaceId),
                    name = "test-namespace",
                    defaultAgentName = namespaceDefaultName,
                )
            val namespaceService = mockk<NamespaceService> { every { findById(namespaceId) } returns namespace }
            val namespaceAgent =
                mockk<Agent> {
                    every { metadata } returns EntityMetadata(id = namespaceAgentId)
                    every { name } returns namespaceDefaultName
                    every { id } returns namespaceAgentId
                    every { llmProvider } returns "test-provider"
                    every { llmModel } returns "test-model"
                    every { run(any<List<CaseEvent>>(), any()) } answers {
                        val caseId = firstArg<List<CaseEvent>>().first().caseId
                        flow {
                            emit(
                                AgentFinishedEvent(
                                    namespaceId = namespaceId,
                                    caseId = caseId,
                                    agentId = namespaceAgentId,
                                    agentName = namespaceDefaultName,
                                ),
                            )
                        }
                    }
                }
            val agentService =
                mockk<AgentService> {
                    every { resolveAgentName(namespaceDefaultName, namespaceId, any()) } returns namespaceDefaultName
                    coEvery { findAgentByName(namespaceDefaultName, any(), any()) } returns namespaceAgent
                }
            val userService = mockk<UserService> { every { findById(userId) } returns activeUser }
            val service =
                CaseServiceImpl(
                    agentService,
                    allowAllAgentConfigService,
                    AgentConfigProperties(agentName = environmentDefaultName),
                    InMemoryCaseRepository(),
                    caseEventService,
                    userService,
                    namespaceService,
                    caseConfig = CaseConfigProperties(),
                    permissionService = permissionService,
                    caseNamingService = noOpCaseNamingService,
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
            verify(
                exactly = 1,
            ) { allowAllAgentConfigService.findDeployedByNamespaceIdAndUserIdAndName(namespaceId, userId, namespaceDefaultName) }
        }

        "no default agent at any level produces WarnEvent and stops" {
            val caseEventService = CaseEventServiceImpl(InMemoryCaseEventRepository())
            val namespace =
                Namespace(
                    metadata = EntityMetadata(id = namespaceId),
                    name = "test-namespace",
                    defaultAgentName = null,
                )
            val namespaceService = mockk<NamespaceService> { every { findById(namespaceId) } returns namespace }
            val agentService = mockk<AgentService>(relaxed = true)
            val userService = mockk<UserService> { every { findById(userId) } returns activeUser }
            val service =
                CaseServiceImpl(
                    agentService,
                    allowAllAgentConfigService,
                    AgentConfigProperties(agentName = null), // no environment default either
                    InMemoryCaseRepository(),
                    caseEventService,
                    userService,
                    namespaceService,
                    caseConfig = CaseConfigProperties(),
                    permissionService = permissionService,
                    caseNamingService = noOpCaseNamingService,
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
            verify(exactly = 0) { allowAllAgentConfigService.findDeployedByNamespaceIdAndUserIdAndName(any(), any(), any()) }
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
            verify(exactly = 1) { allowAllAgentConfigService.findDeployedByNamespaceIdAndUserIdAndName(namespaceId, userId, agentName) }
        }

        "first message without @mention produces WarnEvent and stops when namespace has no default agent" {
            // Wire the service manually to keep a reference to the event store.
            val caseEventService = CaseEventServiceImpl(InMemoryCaseEventRepository())
            val namespace =
                Namespace(
                    metadata = EntityMetadata(id = namespaceId),
                    name = "test-namespace",
                    defaultAgentName = null,
                )
            val namespaceService = mockk<NamespaceService> { every { findById(namespaceId) } returns namespace }
            // resolveAgentName is never reached because selectDefaultAgent short-circuits on null
            val agentService = mockk<AgentService>(relaxed = true)
            val userService = mockk<UserService> { every { findById(userId) } returns activeUser }
            val service =
                CaseServiceImpl(
                    agentService,
                    allowAllAgentConfigService,
                    AgentConfigProperties(),
                    InMemoryCaseRepository(),
                    caseEventService,
                    userService,
                    namespaceService,
                    caseConfig = CaseConfigProperties(),
                    permissionService = permissionService,
                    caseNamingService = noOpCaseNamingService,
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
            verify(exactly = 0) { allowAllAgentConfigService.findDeployedByNamespaceIdAndUserIdAndName(any(), any(), any()) }
        }

        "last active agent unavailable falls back to namespace default" {
            val unavailableAgentName = "old-agent"
            val caseEventService = CaseEventServiceImpl(InMemoryCaseEventRepository())
            val namespace =
                Namespace(
                    metadata = EntityMetadata(id = namespaceId),
                    name = "test-namespace",
                    defaultAgentName = agentName,
                )
            val namespaceService = mockk<NamespaceService> { every { findById(namespaceId) } returns namespace }
            // resolveAgentName call sequence:
            //   turn 1, @mention path: resolveAgentName(unavailableAgentName) -> unavailableAgentName (found)
            //   turn 2, sticky-agent availability check: resolveAgentName(unavailableAgentName) -> null (gone)
            //   turn 2, default resolution: resolveAgentName(agentName) -> agentName (found)
            val resolveCallCount =
                java.util.concurrent.atomic
                    .AtomicInteger(0)
            val agentService =
                mockk<AgentService> {
                    every { resolveAgentName(unavailableAgentName, namespaceId, any()) } answers {
                        when (resolveCallCount.incrementAndGet()) {
                            1 -> unavailableAgentName

                            // turn 1: @mention resolves
                            else -> null // turn 2: sticky-agent check fails
                        }
                    }
                    every { resolveAgentName(agentName, namespaceId, any()) } returns agentName
                    coEvery { findAgentByName(any(), any(), any()) } returns finishingAgent()
                }
            val userServiceMock = mockk<UserService> { every { findById(userId) } returns activeUser }
            val service =
                CaseServiceImpl(
                    agentService,
                    allowAllAgentConfigService,
                    AgentConfigProperties(),
                    InMemoryCaseRepository(),
                    caseEventService,
                    userServiceMock,
                    namespaceService,
                    caseConfig = CaseConfigProperties(),
                    permissionService = permissionService,
                    caseNamingService = noOpCaseNamingService,
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
            verify(
                exactly = 1,
            ) { allowAllAgentConfigService.findDeployedByNamespaceIdAndUserIdAndName(namespaceId, userId, unavailableAgentName) }
            verify(exactly = 1) { allowAllAgentConfigService.findDeployedByNamespaceIdAndUserIdAndName(namespaceId, userId, agentName) }
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
                    every { id } returns selectedAgentId
                    every { llmProvider } returns "test-provider"
                    every { llmModel } returns "test-model"
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

            val namespace =
                Namespace(
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
                    coEvery { findAgentByName(selectedAgentName, any(), any()) } returns selectedAgent
                }
            val caseRepository = InMemoryCaseRepository()
            val caseEventService = CaseEventServiceImpl(InMemoryCaseEventRepository())
            val userService = mockk<UserService> { every { findById(userId) } returns activeUser }
            val service =
                CaseServiceImpl(
                    agentService,
                    allowAllAgentConfigService,
                    AgentConfigProperties(),
                    caseRepository,
                    caseEventService,
                    userService,
                    namespaceService,
                    caseConfig = CaseConfigProperties(),
                    permissionService = permissionService,
                    caseNamingService = noOpCaseNamingService,
                )
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
            verify(
                exactly = 2,
            ) { allowAllAgentConfigService.findDeployedByNamespaceIdAndUserIdAndName(namespaceId, userId, selectedAgentName) }
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
            val namespace =
                Namespace(
                    metadata = EntityMetadata(id = namespaceId),
                    name = "test-namespace",
                    defaultAgentName = agentName,
                )
            val namespaceService = mockk<NamespaceService> { every { findById(namespaceId) } returns namespace }
            val inspectorAgent =
                mockk<Agent> {
                    every { metadata } returns EntityMetadata(id = inspectorId)
                    every { name } returns inspectorName
                    every { id } returns inspectorId
                    every { llmProvider } returns "test-provider"
                    every { llmModel } returns "test-model"
                    every { run(any<List<CaseEvent>>(), any()) } answers {
                        val caseId = firstArg<List<CaseEvent>>().first().caseId
                        flow {
                            emit(
                                AgentFinishedEvent(
                                    namespaceId = namespaceId,
                                    caseId = caseId,
                                    agentId = inspectorId,
                                    agentName = inspectorName,
                                ),
                            )
                        }
                    }
                }
            val agentService =
                mockk<AgentService> {
                    // Only `inspector` resolves — the full string with URL must NOT be passed here
                    coEvery { resolveAgentName(inspectorName, namespaceId, any()) } returns inspectorName
                    coEvery { findAgentByName(inspectorName, any(), any()) } returns inspectorAgent
                }
            val userService = mockk<UserService> { every { findById(userId) } returns activeUser }
            val service =
                CaseServiceImpl(
                    agentService,
                    allowAllAgentConfigService,
                    AgentConfigProperties(),
                    InMemoryCaseRepository(),
                    caseEventService,
                    userService,
                    namespaceService,
                    caseConfig = CaseConfigProperties(),
                    permissionService = permissionService,
                    caseNamingService = noOpCaseNamingService,
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
            verify(exactly = 1) { allowAllAgentConfigService.findDeployedByNamespaceIdAndUserIdAndName(namespaceId, userId, inspectorName) }
        }

        // -------------------------------------------------------------------------
        // Idle runtime eviction
        // -------------------------------------------------------------------------

        "idle runtime is NOT evicted when client disconnects while agent is still running" {
            // The eviction watcher combines subscriptionCount and statusFlow.
            // If subscriptionCount drops to 0 while status is RUNNING, combine emits false
            // and the grace period never starts — the runtime must survive until the run completes.
            //
            // This test uses a slow agent (200ms delay) so subscriptionCount == 0 and
            // status == RUNNING overlap. The assertion is made immediately after the
            // subscriber disconnects — no timing margin needed.

            val slowAgent =
                mockk<Agent> {
                    every { metadata } returns EntityMetadata(id = agentId)
                    every { name } returns agentName
                    every { id } returns agentId
                    every { llmProvider } returns "test-provider"
                    every { llmModel } returns "test-model"
                    every { run(any<List<CaseEvent>>(), any()) } answers {
                        val caseId = firstArg<List<CaseEvent>>().first().caseId
                        flow {
                            delay(200) // simulate a slow agent run
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

            val service = buildService(agent = slowAgent, idleEvictionGraceMs = 50L)
            val case = service.create(Case(namespaceId = namespaceId))
            val runtime = service.getCaseRuntime(case.id)
            val scope = CoroutineScope(Dispatchers.IO)

            // Subscribe just long enough to observe RUNNING, then unsubscribe.
            // This creates the window: subscriptionCount == 0 while status == RUNNING.
            val shortLivedJob =
                scope.launch {
                    withTimeout(8_000) {
                        runtime.events
                            .filterIsInstance<CaseStatusEvent>()
                            .first { it.status == CaseStatus.RUNNING }
                    }
                }
            awaitSubscribers(runtime)
            service.addMessage(
                caseId = case.id,
                actor = userActor,
                content = listOf(MessageContent.Text("hello")),
            )
            shortLivedJob.join() // unsubscribes when RUNNING is seen
            // subscriptionCount is now 0, status is RUNNING — eviction must NOT fire.
            // Assert immediately: statusFlow is RUNNING by construction at this point
            // (shortLivedJob only completed after seeing the RUNNING CaseStatusEvent,
            // and _statusFlow is updated before emitEvent so it is guaranteed RUNNING here).
            service.findActiveRuntime(case.id) shouldBe runtime
            runtime.statusFlow.value shouldBe CaseStatus.RUNNING
        }

        "idle runtime is evicted after all SSE subscribers disconnect and grace period elapses" {
            val service = buildService(idleEvictionGraceMs = 50L)
            val case = service.create(Case(namespaceId = namespaceId))
            val runtime = service.getCaseRuntime(case.id)
            val scope = CoroutineScope(Dispatchers.IO)

            // Subscribe, let the case reach IDLE, then unsubscribe.
            val awaiter = scope.expectCaseStatus(runtime, CaseStatus.IDLE)
            awaitSubscribers(runtime)
            service.addMessage(
                caseId = case.id,
                actor = userActor,
                content = listOf(MessageContent.Text("hello")),
            )
            awaiter.join()
            // awaiter job ends, which cancels its coroutine -> subscriptionCount drops to 0.

            // Wait for the grace period + a small margin to let the eviction coroutine run.
            delay(200)

            // The runtime must have been evicted: findActiveRuntime returns null.
            service.findActiveRuntime(case.id) shouldBe null
            // The case itself is still persisted and accessible.
            service.getById(case.id).status shouldBe CaseStatus.IDLE
        }

        "idle runtime is NOT evicted when a new message arrives before grace period elapses" {
            // idleEvictionGraceMs=500 gives us a window to send a second message.
            // The eviction watcher fires when subscriptionCount hits 0 after the first IDLE.
            // A second message arrives within the grace period, making the status RUNNING again.
            // The guard (currentStatus == IDLE) prevents eviction, and we verify the runtime
            // is still alive before the second grace period elapses.
            val service = buildService(idleEvictionGraceMs = 500L)
            val case = service.create(Case(namespaceId = namespaceId))
            val runtime = service.getCaseRuntime(case.id)
            val scope = CoroutineScope(Dispatchers.IO)

            // First message -> IDLE
            val firstIdle = scope.expectCaseStatus(runtime, CaseStatus.IDLE)
            awaitSubscribers(runtime)
            service.addMessage(
                caseId = case.id,
                actor = userActor,
                content = listOf(MessageContent.Text("first")),
            )
            firstIdle.join()
            awaitNotRunning(runtime)

            // Send a second message immediately — the runtime transitions IDLE -> RUNNING
            // before the first grace period elapses, which cancels the first eviction.
            val secondIdle = scope.expectCaseStatus(runtime, CaseStatus.IDLE)
            awaitSubscribers(runtime)
            service.addMessage(
                caseId = case.id,
                actor = userActor,
                content = listOf(MessageContent.Text("second")),
            )
            secondIdle.join()
            // awaitNotRunning ensures run() has fully exited and the runtime is in a
            // stable IDLE state before we assert. No arbitrary delay needed.
            awaitNotRunning(runtime)

            // Check BEFORE the second grace period elapses.
            // The runtime must still be alive: the first eviction was cancelled by the
            // second message, and the second eviction hasn't fired yet.
            service.findActiveRuntime(case.id) shouldBe runtime
            service.getById(case.id).status shouldBe CaseStatus.IDLE
        }

        "eviction watcher coroutine is terminated after idle eviction" {
            // Regression test for the coroutine-leak fix.
            //
            // Before the fix, the idle eviction path called watcherJobs.remove(caseId)
            // without ?.cancel(). The comment claimed collect{} on the infinite
            // combine(StateFlow, StateFlow) "ends naturally" — it does not. The remove
            // cleared the map entry but left the coroutine suspended in collect forever,
            // retaining the CaseRuntime in its closure — a memory leak.
            //
            // After the fix, watcherJobs.remove(caseId)?.cancel() is used, which is
            // consistent with the terminal-status path in handleStatusChange and
            // correctly terminates the coroutine.
            //
            // This test verifies that after eviction the service scope has no orphan
            // coroutines left over from the watcher.

            val service = buildService(idleEvictionGraceMs = 50L)
            val case = service.create(Case(namespaceId = namespaceId))
            val runtime = service.getCaseRuntime(case.id)
            val scope = CoroutineScope(Dispatchers.IO)

            // Subscribe, let the case reach IDLE, then unsubscribe.
            val awaiter = scope.expectCaseStatus(runtime, CaseStatus.IDLE)
            awaitSubscribers(runtime)
            service.addMessage(
                caseId = case.id,
                actor = userActor,
                content = listOf(MessageContent.Text("hello")),
            )
            awaiter.join()
            // awaiter job ends -> subscriptionCount drops to 0.
            // Also wait for run() to fully exit so its coroutine is not counted.
            awaitNotRunning(runtime)

            // Wait for grace period + margin for the watcher to fire and cancel itself.
            delay(200)

            // Runtime was evicted.
            service.findActiveRuntime(case.id) shouldBe null
            // The watcher coroutine must have been cancelled — no orphan coroutines
            // should remain in the service scope for this case.
            service.activeCoroutineCount shouldBe 0
        }

        // -------------------------------------------------------------------------
        // Kill propagation to sub-cases
        // -------------------------------------------------------------------------

        // -------------------------------------------------------------------------
        // startSubCase: delegation depth and linkParentToChild atomicity
        // -------------------------------------------------------------------------

        "startSubCase creates a sub-case and links it to the parent" {
            val service = buildService()
            val parentCase = service.create(Case(namespaceId = namespaceId))

            val runtime =
                service.startSubCase(
                    parentCaseId = parentCase.id,
                    namespaceId = namespaceId,
                    agentName = agentName,
                    task = "do something",
                    userId = userId,
                )

            // A runtime was returned — the sub-case exists and is active
            val subCaseId = runtime.id
            val subCase = service.getById(subCaseId)
            subCase.namespaceId shouldBe namespaceId
            subCase.parentCaseId shouldBe parentCase.id
        }

        "startSubCase propagates exception when linkParentToChild fails" {
            // Uses a mockk CaseRepository that delegates all operations to InMemoryCaseRepository
            // but throws on linkParentToChild.
            // Before the refacto, this exception was swallowed by runCatching — the sub-case
            // would be created and the error silently logged.
            // After the refacto, the exception propagates to the caller.
            val delegate = InMemoryCaseRepository()
            val throwingRepo =
                mockk<CaseRepository> {
                    every { save(any()) } answers { delegate.save(firstArg()) }
                    every { findByIds(any(), any()) } answers { delegate.findByIds(firstArg(), secondArg()) }
                    every { findByParent(any()) } answers { delegate.findByParent(firstArg()) }
                    every { delete(any()) } answers { delegate.delete(firstArg()) }
                    every { deleteByParent(any()) } answers { delegate.deleteByParent(firstArg()) }
                    every { findAccessibleByUserInNamespace(any(), any()) } answers {
                        delegate.findAccessibleByUserInNamespace(firstArg(), secondArg())
                    }
                    every { findConcerningUser(any()) } answers { delegate.findConcerningUser(firstArg()) }
                    every { findConcerningUserInNamespace(any(), any()) } answers {
                        delegate.findConcerningUserInNamespace(firstArg(), secondArg())
                    }
                    every { findActiveByParentCaseId(any()) } answers { delegate.findActiveByParentCaseId(firstArg()) }
                    every { findActiveDescendants(any()) } answers { delegate.findActiveDescendants(firstArg()) }
                    every { countAncestorDepth(any()) } answers { delegate.countAncestorDepth(firstArg()) }
                    every { linkParentToChild(any(), any()) } throws RuntimeException("simulated Neo4j link failure")
                }
            val namespace =
                Namespace(
                    metadata = EntityMetadata(id = namespaceId),
                    name = "test-namespace",
                    defaultAgentName = agentName,
                )
            val namespaceService =
                mockk<NamespaceService> {
                    every { findById(namespaceId) } returns namespace
                }
            val agentService =
                mockk<AgentService> {
                    every { resolveAgentName(any(), any(), any()) } returns agentName
                    coEvery { findAgentByName(agentName, any(), any()) } returns finishingAgent()
                }
            val userService =
                mockk<UserService> {
                    every { findById(userId) } returns activeUser
                    every { getById(userId) } returns activeUser
                }
            val service =
                CaseServiceImpl(
                    agentService,
                    allowAllAgentConfigService,
                    AgentConfigProperties(),
                    throwingRepo,
                    CaseEventServiceImpl(InMemoryCaseEventRepository()),
                    userService,
                    namespaceService,
                    caseConfig = CaseConfigProperties(),
                    permissionService = permissionService,
                    caseNamingService = noOpCaseNamingService,
                )
            val parentCase = service.create(Case(namespaceId = namespaceId))

            shouldThrow<RuntimeException> {
                service.startSubCase(
                    parentCaseId = parentCase.id,
                    namespaceId = namespaceId,
                    agentName = agentName,
                    task = "do something",
                    userId = userId,
                )
            }
        }

        "killing a parent case also kills its active sub-cases" {
            // Verifies that killCase propagates depth-first to sub-cases created by
            // delegation. The parent is killed; both sub-cases must reach KILLED status
            // even though only the parent was explicitly killed.

            val service = buildService()

            val parentCase = service.create(Case(namespaceId = namespaceId))
            // Create two sub-cases linked to the parent via parentCaseId
            val subCase1 = service.create(Case(namespaceId = namespaceId, parentCaseId = parentCase.id))
            val subCase2 = service.create(Case(namespaceId = namespaceId, parentCaseId = parentCase.id))

            service.killCase(parentCase.id)

            service.getById(parentCase.id).status shouldBe CaseStatus.KILLED
            service.getById(subCase1.id).status shouldBe CaseStatus.KILLED
            service.getById(subCase2.id).status shouldBe CaseStatus.KILLED
        }

        "killing a parent case kills nested sub-sub-cases recursively" {
            val service = buildService()

            val parentCase = service.create(Case(namespaceId = namespaceId))
            val subCase = service.create(Case(namespaceId = namespaceId, parentCaseId = parentCase.id))
            val subSubCase = service.create(Case(namespaceId = namespaceId, parentCaseId = subCase.id))

            service.killCase(parentCase.id)

            service.getById(parentCase.id).status shouldBe CaseStatus.KILLED
            service.getById(subCase.id).status shouldBe CaseStatus.KILLED
            service.getById(subSubCase.id).status shouldBe CaseStatus.KILLED
        }

        "idle runtime is NOT evicted while SSE subscribers remain connected" {
            // The eviction watcher only fires when subscriptionCount == 0.
            // We keep a subscriber alive so subscriptionCount never reaches 0.
            val service = buildService(idleEvictionGraceMs = 50L)
            val case = service.create(Case(namespaceId = namespaceId))
            val runtime = service.getCaseRuntime(case.id)
            val scope = CoroutineScope(Dispatchers.IO)

            val awaiter = scope.expectCaseStatus(runtime, CaseStatus.IDLE)
            awaitSubscribers(runtime)
            service.addMessage(
                caseId = case.id,
                actor = userActor,
                content = listOf(MessageContent.Text("hello")),
            )
            awaiter.join()

            // Keep a long-lived subscriber open so subscriptionCount stays > 0.
            val longLivedJob =
                scope.launch {
                    withTimeout(5_000) {
                        runtime.events.collect { /* keep alive */ }
                    }
                }

            // Wait well past idleEvictionTimeoutMs to confirm no eviction happened.
            delay(300)

            service.findActiveRuntime(case.id) shouldBe runtime

            longLivedJob.cancel()
        }

        "@mention followed by a URL with non-breaking space selects the agent and ignores the URL" {
            // Non-breaking space (U+00A0) is not matched by \s in Java/Kotlin regex,
            // so \S+ would consume the entire `inspector\u00A0https://...` string.
            // The fix [\w-]+ stops at the non-breaking space (which is not \w or -).

            val inspectorName = "inspector"
            val inspectorId = UUID.nameUUIDFromBytes(inspectorName.toByteArray())
            val caseEventService = CaseEventServiceImpl(InMemoryCaseEventRepository())
            val namespace =
                Namespace(
                    metadata = EntityMetadata(id = namespaceId),
                    name = "test-namespace",
                    defaultAgentName = agentName,
                )
            val namespaceService = mockk<NamespaceService> { every { findById(namespaceId) } returns namespace }
            val inspectorAgent =
                mockk<Agent> {
                    every { metadata } returns EntityMetadata(id = inspectorId)
                    every { name } returns inspectorName
                    every { id } returns inspectorId
                    every { llmProvider } returns "test-provider"
                    every { llmModel } returns "test-model"
                    every { run(any<List<CaseEvent>>(), any()) } answers {
                        val caseId = firstArg<List<CaseEvent>>().first().caseId
                        flow {
                            emit(
                                AgentFinishedEvent(
                                    namespaceId = namespaceId,
                                    caseId = caseId,
                                    agentId = inspectorId,
                                    agentName = inspectorName,
                                ),
                            )
                        }
                    }
                }
            val agentService =
                mockk<AgentService> {
                    coEvery { resolveAgentName(inspectorName, namespaceId, any()) } returns inspectorName
                    coEvery { findAgentByName(inspectorName, any(), any()) } returns inspectorAgent
                }
            val userService = mockk<UserService> { every { findById(userId) } returns activeUser }
            val service =
                CaseServiceImpl(
                    agentService,
                    allowAllAgentConfigService,
                    AgentConfigProperties(),
                    InMemoryCaseRepository(),
                    caseEventService,
                    userService,
                    namespaceService,
                    caseConfig = CaseConfigProperties(),
                    permissionService = permissionService,
                    caseNamingService = noOpCaseNamingService,
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
            verify(exactly = 1) { allowAllAgentConfigService.findDeployedByNamespaceIdAndUserIdAndName(namespaceId, userId, inspectorName) }
        }

        "agent runs once per message when two messages are sent sequentially" {
            var runCallCount = 0
            val countingAgent =
                mockk<Agent> {
                    every { metadata } returns EntityMetadata(id = agentId)
                    every { name } returns agentName
                    every { id } returns agentId
                    every { llmProvider } returns "test-provider"
                    every { llmModel } returns "test-model"
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
            verify(exactly = 2) { allowAllAgentConfigService.findDeployedByNamespaceIdAndUserIdAndName(namespaceId, userId, agentName) }
        }

        // -------------------------------------------------------------------------
        // Rehydration: crash recovery from persisted AgentRunningEvent
        // -------------------------------------------------------------------------

        "rehydrated case with AgentRunningEvent as last event runs agent exactly once and reaches IDLE" {
            // Regression: when a case is rehydrated from persistence after a crash,
            // the last persisted event may be an AgentRunningEvent (emitted by runAgent
            // before agent.run()). processNextStep finds it and calls runAgent, which
            // now emits ANOTHER AgentRunningEvent. After the agent finishes, the second
            // AgentRunningEvent could be found by the next processNextStep iteration
            // (it's newer than AgentFinishedEvent), causing an infinite loop.
            //
            // Expected: the agent runs exactly once, no infinite loop, case reaches IDLE.

            var runCallCount = 0
            val countingAgent =
                mockk<Agent> {
                    every { id } returns agentId
                    every { metadata } returns EntityMetadata(id = agentId)
                    every { name } returns agentName
                    every { llmProvider } returns "test-provider"
                    every { llmModel } returns "test-model"
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

            // Build the service with a pre-existing case that has events simulating a crash
            // after AgentRunningEvent was emitted but before AgentFinishedEvent.
            val caseEventRepo = InMemoryCaseEventRepository()
            val caseEventService = CaseEventServiceImpl(caseEventRepo)
            val caseRepository = InMemoryCaseRepository()
            val namespace =
                Namespace(
                    metadata = EntityMetadata(id = namespaceId),
                    name = "test-namespace",
                    defaultAgentName = agentName,
                )
            val namespaceService = mockk<NamespaceService> { every { findById(namespaceId) } returns namespace }
            val agentService =
                mockk<AgentService> {
                    every { resolveAgentName(any(), any(), any()) } returns agentName
                    coEvery { findAgentByName(agentName, any(), any()) } returns countingAgent
                }
            val userService = mockk<UserService> { every { findById(userId) } returns activeUser }
            val service =
                CaseServiceImpl(
                    agentService,
                    allowAllAgentConfigService,
                    AgentConfigProperties(),
                    caseRepository,
                    caseEventService,
                    userService,
                    namespaceService,
                    caseConfig = CaseConfigProperties(),
                    permissionService = permissionService,
                    caseNamingService = noOpCaseNamingService,
                )

            // Insert the case directly into the repository so no runtime is created in
            // activeRuntimes. The subsequent getCaseRuntime() call will then trigger
            // rehydrate(), which loads the pre-populated events from the event store
            // and passes them as inputEvents to buildRuntime().
            val case = Case(namespaceId = namespaceId, status = CaseStatus.RUNNING)
            caseRepository.save(case)

            // Pre-populate events as if the case crashed after AgentRunningEvent
            val existingMessage =
                MessageEvent(
                    namespaceId = namespaceId,
                    caseId = case.id,
                    actor = userActor,
                    content = listOf(MessageContent.Text("hello")),
                )
            val existingSelected =
                AgentSelectedEvent(
                    namespaceId = namespaceId,
                    caseId = case.id,
                    agentId = agentId,
                    agentName = agentName,
                )
            val existingRunning =
                AgentRunningEvent(
                    namespaceId = namespaceId,
                    caseId = case.id,
                    agentId = agentId,
                    agentName = agentName,
                    llmProvider = "test-provider",
                    llmModel = "test-model",
                )
            caseEventService.create(existingMessage)
            caseEventService.create(existingSelected)
            caseEventService.create(existingRunning)

            // Rehydrate: getCaseRuntime loads past events from the event store
            val runtime = service.getCaseRuntime(case.id)
            val scope = CoroutineScope(Dispatchers.IO)

            val awaiter = scope.expectCaseStatus(runtime, CaseStatus.IDLE, CaseStatus.ERROR)
            awaitSubscribers(runtime)

            // Trigger the run loop — no new message, just resume from persisted state
            runtime.run()

            awaiter.join()

            runCallCount shouldBe 1
            service.getById(case.id).status shouldBe CaseStatus.IDLE
        }
    })
