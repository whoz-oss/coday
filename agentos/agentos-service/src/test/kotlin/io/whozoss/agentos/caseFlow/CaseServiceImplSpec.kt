package io.whozoss.agentos.caseFlow

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.agent.AgentConfigProperties
import io.whozoss.agentos.agent.AgentService
import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.caseEvent.CaseEventService
import io.whozoss.agentos.caseEvent.CaseEventServiceImpl
import io.whozoss.agentos.caseEvent.InMemoryCaseEventRepository
import io.whozoss.agentos.git.CaseResourceBinding
import io.whozoss.agentos.git.CaseResourceStatus
import io.whozoss.agentos.git.GitCaseLaunchGate
import io.whozoss.agentos.git.GitExchangeRoot
import io.whozoss.agentos.git.GitExchangeRootResolver
import io.whozoss.agentos.git.InMemoryCaseResourceBindingService
import io.whozoss.agentos.exchange.ExchangeStorageConfigProperties
import io.whozoss.agentos.exchange.ExchangeStorageService
import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.git.WorkspaceLifecycleLocks
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.prompt.Prompt
import io.whozoss.agentos.prompt.PromptService
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
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
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
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
        val promptService: PromptService = mockk(relaxed = true)

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
            caseRepository: CaseRepository = InMemoryCaseRepository(),
            caseLaunchGate: CaseLaunchGate = CaseLaunchGate.ALWAYS,
            caseEventService: CaseEventService = CaseEventServiceImpl(InMemoryCaseEventRepository()),
            admissionRetryDelaysMs: List<Long> = listOf(10L, 10L),
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
            return CaseServiceImpl(
                agentService = agentService,
                agentConfigService = agentConfigService,
                agentConfigProperties = AgentConfigProperties(agentName = environmentAgentName),
                caseRepository = caseRepository,
                caseEventService = caseEventService,
                userService = userService,
                namespaceService = namespaceService,
                caseConfig =
                    CaseConfigProperties(
                        idleEvictionGraceMs = idleEvictionGraceMs,
                        admissionRetryDelaysMs = admissionRetryDelaysMs,
                    ),
                permissionService = permissionService,
                promptService = promptService,
                caseNamingService = noOpCaseNamingService,
                caseLaunchGate = caseLaunchGate,
            )
        }

        fun gitGate(repository: CaseRepository, bindings: InMemoryCaseResourceBindingService): GitCaseLaunchGate =
            GitCaseLaunchGate(
                GitExchangeRootResolver(repository, bindings,
                    ExchangeStorageService(ExchangeStorageConfigProperties(mountRoot = "/tmp/runtime-exchange-tests")),
                    com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()),
                repository,
            )

        fun equip(bindings: InMemoryCaseResourceBindingService, caseId: UUID, status: CaseResourceStatus = CaseResourceStatus.READY) =
            bindings.create(CaseResourceBinding(rootCaseId = caseId, namespaceId = namespaceId,
                integrationConfigId = UUID.randomUUID(), status = status))

        /** A gate that holds runs back until [open] is set, as a workspace being prepared does. */
        class TestLaunchGate : CaseLaunchGate {
            var open: Boolean = false

            override fun canLaunch(caseId: UUID): Boolean = open
        }

        listOf(CaseStatus.KILLED, CaseStatus.ERROR).forEach { status ->
            listOf(false, true).forEach { withWorkspace ->
                "fresh messages to $status cases preserve the optional workspace policy (equipped=$withWorkspace)" {
                    val repository = InMemoryCaseRepository()
                    val case = repository.save(Case(namespaceId = namespaceId, status = status))
                    val events = CaseEventServiceImpl(InMemoryCaseEventRepository())
                    val roots = mockk<GitExchangeRootResolver>()
                    val binding = if (withWorkspace) CaseResourceBinding(
                        rootCaseId = case.id,
                        namespaceId = namespaceId,
                        integrationConfigId = UUID.randomUUID(),
                        status = CaseResourceStatus.READY,
                    ) else null
                    every { roots.resolveGit(case.id) } returns GitExchangeRoot(Path.of("/tmp/case"), binding, case.id)
                    val agent = finishingAgent()
                    val service = buildService(
                        agent = agent,
                        caseRepository = repository,
                        caseEventService = events,
                        caseLaunchGate = GitCaseLaunchGate(roots, repository),
                    )
                    try {
                        if (withWorkspace) {
                            shouldThrow<io.whozoss.agentos.exception.ConflictException> {
                                service.addMessage(case.id, userActor, listOf(MessageContent.Text("Do more work")))
                            }
                            events.findByParent(case.id) shouldBe emptyList()
                            service.findActiveRuntime(case.id) shouldBe null
                            service.activeCoroutineCount shouldBe 0
                            repository.findByIds(listOf(case.id)).single().status shouldBe status
                        } else {
                            service.addMessage(case.id, userActor, listOf(MessageContent.Text("Do more work")))
                            withTimeout(3_000) { while (service.getById(case.id).status != CaseStatus.IDLE) delay(10) }
                            verify(exactly = 1) { agent.run(any<List<CaseEvent>>(), any()) }
                            events.findByParent(case.id).filterIsInstance<MessageEvent>()
                                .single { it.actor.role == ActorRole.USER }.content shouldBe listOf(MessageContent.Text("Do more work"))
                        }
                    } finally { service.shutdown() }
                }
            }
        }

        "a fresh non-Git message after Kill is not lost while the previous agent is still stopping" {
            val firstEntered = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val secondEntered = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            val agent = finishingAgent()
            every { agent.run(any<List<CaseEvent>>(), any()) } answers {
                val caseId = firstArg<List<CaseEvent>>().first().caseId
                val call = calls.incrementAndGet()
                flow {
                    if (call == 1) {
                        firstEntered.complete(Unit)
                        releaseFirst.await()
                    } else secondEntered.complete(Unit)
                    emit(AgentFinishedEvent(namespaceId = namespaceId, caseId = caseId,
                        agentId = agentId, agentName = agentName))
                }
            }
            val repository = InMemoryCaseRepository()
            val events = CaseEventServiceImpl(InMemoryCaseEventRepository())
            val roots = mockk<GitExchangeRootResolver> {
                every { resolveGit(any<UUID>()) } answers {
                    GitExchangeRoot(Path.of("/tmp/case"), null, firstArg())
                }
            }
            val service = buildService(agent = agent, caseRepository = repository, caseEventService = events,
                caseLaunchGate = GitCaseLaunchGate(roots, repository))
            try {
                val case = service.create(Case(namespaceId = namespaceId))
                service.addMessage(case.id, userActor, listOf(MessageContent.Text("First instruction")))
                withTimeout(2_000) { firstEntered.await() }
                service.killCase(case.id)
                service.getById(case.id).status shouldBe CaseStatus.KILLED
                service.hasRunningExecutions(listOf(case.id)) shouldBe true

                service.addMessage(case.id, userActor, listOf(MessageContent.Text("Fresh instruction")))
                service.getById(case.id).status shouldBe CaseStatus.PENDING
                calls.get() shouldBe 1
                events.findByParent(case.id).filterIsInstance<MessageEvent>()
                    .last { it.actor.role == ActorRole.USER }.content shouldBe listOf(MessageContent.Text("Fresh instruction"))
                releaseFirst.complete(Unit)

                withTimeout(2_000) { secondEntered.await() }
                withTimeout(2_000) { while (service.getById(case.id).status != CaseStatus.IDLE) delay(10) }
                calls.get() shouldBe 2
                verify(exactly = 1) {
                    agent.run(match<List<CaseEvent>> { history ->
                        history.filterIsInstance<MessageEvent>().last { it.actor.role == ActorRole.USER }.content ==
                            listOf(MessageContent.Text("Fresh instruction"))
                    }, any())
                }
            } finally {
                releaseFirst.complete(Unit)
                service.shutdown()
            }
        }

        "workspace input is emitted before preparation and survives cancellation in conversation history" {
            val gate = TestLaunchGate()
            val events = CaseEventServiceImpl(InMemoryCaseEventRepository())
            val service = buildService(caseLaunchGate = gate, caseEventService = events)
            try {
                val case = service.create(Case(namespaceId = namespaceId))
                val runtime = service.getCaseRuntime(case.id)
                val receipt = async { runtime.events.filterIsInstance<MessageEvent>().first() }
                awaitSubscribers(runtime)
                service.addMessage(case.id, userActor, listOf(MessageContent.Text("Do the work")))
                val displayed = withTimeout(2_000) { receipt.await() }
                displayed.content shouldBe listOf(MessageContent.Text("Do the work"))
                events.findByParent(case.id).filterIsInstance<MessageEvent>() shouldBe listOf(displayed)
                service.interruptCase(case.id)
                events.findByParent(case.id).filterIsInstance<MessageEvent>() shouldBe listOf(displayed)
                runtime.isRunning() shouldBe false
            } finally { service.shutdown() }
        }

        "a new instruction after Stop while preparing executes when the workspace becomes ready" {
            val agent = finishingAgent()
            val gate = TestLaunchGate()
            val service = buildService(agent = agent, caseLaunchGate = gate, idleEvictionGraceMs = 25L)
            try {
                val case = service.create(Case(namespaceId = namespaceId))
                service.addMessage(case.id, userActor, listOf(MessageContent.Text("Cancelled instruction")))
                service.interruptCase(case.id)
                service.getById(case.id).status shouldBe CaseStatus.IDLE
                service.addMessage(case.id, userActor, listOf(MessageContent.Text("New instruction")))
                service.getById(case.id).status shouldBe CaseStatus.PENDING
                delay(100)
                service.findActiveRuntime(case.id)!!.statusFlow.value shouldBe CaseStatus.PENDING
                gate.open = true
                service.resumeIfPending(case.id)
                withTimeout(3_000) { while (service.getById(case.id).status != CaseStatus.IDLE) delay(10) }
                verify(exactly = 1) { agent.run(any<List<CaseEvent>>(), any()) }
            } finally { service.shutdown() }
        }

        listOf(false, true).forEach { sendFreshMessage ->
            "Stop revokes an admitted launch before execution and preserves a later message (fresh=$sendFreshMessage)" {
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                val checks = AtomicInteger()
                val gate = object : CaseLaunchGate {
                    override fun canLaunch(caseId: UUID): Boolean {
                        // The first check admits the job; the second is the job's own check.
                        if (checks.incrementAndGet() == 2) {
                            entered.countDown()
                            check(release.await(5, TimeUnit.SECONDS))
                        }
                        return true
                    }
                }
                val agent = finishingAgent()
                val service = buildService(agent = agent, caseLaunchGate = gate)
                try {
                    val case = service.create(Case(namespaceId = namespaceId))
                    service.addMessage(case.id, userActor, listOf(MessageContent.Text("Cancelled instruction")))
                    entered.await(5, TimeUnit.SECONDS) shouldBe true
                    service.findActiveRuntime(case.id)!!.isRunning() shouldBe false
                    service.hasRunningExecutions(listOf(case.id)) shouldBe true

                    service.interruptCase(case.id)
                    service.getById(case.id).status shouldBe CaseStatus.IDLE
                    if (sendFreshMessage) {
                        service.addMessage(case.id, userActor, listOf(MessageContent.Text("Fresh instruction")))
                        service.getById(case.id).status shouldBe CaseStatus.PENDING
                    }
                    release.countDown()
                    withTimeout(3_000) {
                        while (service.hasRunningExecutions(listOf(case.id)) ||
                            service.getById(case.id).status != CaseStatus.IDLE) delay(10)
                    }
                    verify(exactly = if (sendFreshMessage) 1 else 0) { agent.run(any<List<CaseEvent>>(), any()) }
                    if (sendFreshMessage) verify {
                        agent.run(match<List<CaseEvent>> { events ->
                            events.filterIsInstance<MessageEvent>().last { it.actor.role == ActorRole.USER }.content ==
                                listOf(MessageContent.Text("Fresh instruction"))
                        }, any())
                    }
                } finally {
                    release.countDown()
                    service.shutdown()
                }
            }
        }

        "Stop keeps an already running agent cooperative instead of cancelling its coroutine" {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val completed = CompletableDeferred<Unit>()
            val agent = finishingAgent()
            every { agent.run(any<List<CaseEvent>>(), any()) } answers {
                val caseId = firstArg<List<CaseEvent>>().first().caseId
                flow {
                    entered.complete(Unit)
                    release.await()
                    emit(AgentFinishedEvent(namespaceId = namespaceId, caseId = caseId,
                        agentId = agentId, agentName = agentName))
                    completed.complete(Unit)
                }
            }
            val service = buildService(agent = agent)
            try {
                val case = service.create(Case(namespaceId = namespaceId))
                service.addMessage(case.id, userActor, listOf(MessageContent.Text("Start agent")))
                withTimeout(3_000) { entered.await() }
                service.interruptCase(case.id)
                service.findActiveRuntime(case.id)!!.isRunning() shouldBe true
                release.complete(Unit)
                withTimeout(3_000) { completed.await() }
                withTimeout(3_000) { while (service.hasRunningExecutions(listOf(case.id))) delay(10) }
                service.getById(case.id).status shouldBe CaseStatus.IDLE
            } finally {
                release.complete(Unit)
                service.shutdown()
            }
        }

        listOf(CaseStatus.KILLED, CaseStatus.ERROR).forEach { terminal ->
            listOf(false, true).forEach { withGit ->
                "interrupt preserves $terminal without rehydrating a runtime (Git=$withGit)" {
                    val repository = InMemoryCaseRepository()
                    val case = repository.save(Case(namespaceId = namespaceId, status = terminal))
                    val gate = if (withGit) object : CaseLaunchGate {
                        override fun canLaunch(caseId: UUID) = false
                    } else CaseLaunchGate.ALWAYS
                    val service = buildService(caseRepository = repository, caseLaunchGate = gate)
                    try {
                        service.interruptCase(case.id)
                        service.getById(case.id).status shouldBe terminal
                        service.findActiveRuntime(case.id) shouldBe null
                        service.activeCoroutineCount shouldBe 0
                    } finally { service.shutdown() }
                }
            }
        }

        "message admission does not wait on preparation and resumes after a short lock owner too" {
            val rootId = UUID.randomUUID()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val holder = Thread {
                WorkspaceLifecycleLocks.withRoot(rootId) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
            }
            val repository = InMemoryCaseRepository()
            val roots = mockk<GitExchangeRootResolver>()
            every { roots.resolveGit(any<UUID>()) } returns GitExchangeRoot(
                Path.of("/tmp/case"),
                CaseResourceBinding(rootCaseId = rootId, namespaceId = namespaceId,
                    integrationConfigId = UUID.randomUUID(), status = CaseResourceStatus.READY),
                rootId,
            )
            val gate = GitCaseLaunchGate(roots, repository)
            val agent = finishingAgent()
            val service = buildService(agent = agent, caseRepository = repository, caseLaunchGate = gate)
            try {
                holder.start()
                entered.await(5, TimeUnit.SECONDS) shouldBe true
                val case = service.create(Case(namespaceId = namespaceId))
                val started = System.nanoTime()
                service.addMessage(case.id, userActor, listOf(MessageContent.Text("Wait for workspace")))
                (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1_000) shouldBe true
                release.count shouldBe 1L
                service.getById(case.id).status shouldBe CaseStatus.PENDING
                verify(exactly = 0) { agent.run(any<List<CaseEvent>>(), any()) }
                release.countDown()
                withTimeout(3_000) { while (service.getById(case.id).status != CaseStatus.IDLE) delay(10) }
                verify(exactly = 1) { agent.run(any<List<CaseEvent>>(), any()) }
            } finally {
                release.countDown()
                holder.join(5_000)
                service.shutdown()
            }
        }

        "a new service does not automatically replay persisted pending input but accepts a fresh message" {
            val repository = InMemoryCaseRepository()
            val events = CaseEventServiceImpl(InMemoryCaseEventRepository())
            val gate = TestLaunchGate()
            val agent = finishingAgent()
            val before = buildService(agent = agent, caseRepository = repository, caseEventService = events, caseLaunchGate = gate)
            val after = buildService(agent = agent, caseRepository = repository, caseEventService = events)
            try {
                val case = before.create(Case(namespaceId = namespaceId))
                before.addMessage(case.id, userActor, listOf(MessageContent.Text("Before restart")))
                // Simulate another process with the same persisted case/events but no live queue.
                // Opening the conversation may rehydrate a runtime; it must not imply admission.
                after.getCaseRuntime(case.id)
                after.resumeIfPending(case.id)
                delay(100)
                verify(exactly = 0) { agent.run(any<List<CaseEvent>>(), any()) }
                events.findByParent(case.id).filterIsInstance<MessageEvent>().single().content shouldBe
                    listOf(MessageContent.Text("Before restart"))
                after.addMessage(case.id, userActor, listOf(MessageContent.Text("Continue manually")))
                withTimeout(3_000) {
                    while (after.getById(case.id).status != CaseStatus.IDLE) delay(10)
                }
                verify(exactly = 1) { agent.run(any<List<CaseEvent>>(), any()) }
            } finally { before.shutdown(); after.shutdown() }
        }

        listOf(CaseStatus.IDLE, CaseStatus.PENDING, CaseStatus.RUNNING).forEach { statusBeforeShutdown ->
            "shutdown leaves a $statusBeforeShutdown Git case open for fresh input without replay" {
                val repository = InMemoryCaseRepository()
                val events = CaseEventServiceImpl(InMemoryCaseEventRepository())
                val bindings = InMemoryCaseResourceBindingService()
                val gate = gitGate(repository, bindings)
                val entered = CompletableDeferred<Unit>()
                val stopped = CompletableDeferred<Unit>()
                val holdAgent = CompletableDeferred<Unit>()
                val oldAgent = finishingAgent()
                every { oldAgent.run(any<List<CaseEvent>>(), any()) } returns flow {
                    entered.complete(Unit)
                    try { holdAgent.await() } finally { stopped.complete(Unit) }
                }
                val before = buildService(agent = oldAgent, caseRepository = repository,
                    caseEventService = events, caseLaunchGate = gate)
                val case = before.create(Case(namespaceId = namespaceId, status = CaseStatus.IDLE))
                val binding = equip(bindings, case.id, if (statusBeforeShutdown == CaseStatus.PENDING)
                    CaseResourceStatus.PREPARING else CaseResourceStatus.READY)
                try {
                    if (statusBeforeShutdown != CaseStatus.IDLE) {
                        before.addMessage(case.id, userActor, listOf(MessageContent.Text("Before shutdown")))
                    }
                    if (statusBeforeShutdown == CaseStatus.RUNNING) withTimeout(3_000) { entered.await() }
                    before.getById(case.id).status shouldBe statusBeforeShutdown
                    before.shutdown()
                    if (statusBeforeShutdown == CaseStatus.RUNNING) withTimeout(3_000) { stopped.await() }
                    withTimeout(3_000) { while (before.hasRunningExecutions(listOf(case.id))) delay(10) }
                    before.getById(case.id).status shouldBe CaseStatus.IDLE
                    before.findActiveRuntime(case.id) shouldBe null
                    bindings.markStatus(binding.id, CaseResourceStatus.READY, null)

                    // Construct the replacement only after the old service and execution stopped.
                    val newAgent = finishingAgent()
                    val after = buildService(agent = newAgent, caseRepository = repository,
                        caseEventService = events, caseLaunchGate = gitGate(repository, bindings))
                    try {
                        after.getCaseRuntime(case.id)
                        after.resumeIfPending(case.id)
                        after.trackedExecutionCount shouldBe 0
                        verify(exactly = 0) { newAgent.run(any<List<CaseEvent>>(), any()) }
                        after.addMessage(case.id, userActor, listOf(MessageContent.Text("Continue manually")))
                        withTimeout(3_000) { while (after.getById(case.id).status != CaseStatus.IDLE) delay(10) }
                        verify(exactly = 1) {
                            newAgent.run(match<List<CaseEvent>> { history ->
                                history.filterIsInstance<MessageEvent>().last { it.actor.role == ActorRole.USER }.content ==
                                    listOf(MessageContent.Text("Continue manually"))
                            }, any())
                        }
                    } finally { after.shutdown() }
                } finally { before.shutdown() }
            }
        }

        listOf(CaseStatus.KILLED, CaseStatus.ERROR).forEach { terminal ->
            "shutdown preserves an equipped $terminal case even if opening its events rehydrated it" {
                val repository = InMemoryCaseRepository()
                val bindings = InMemoryCaseResourceBindingService()
                val case = repository.save(Case(namespaceId = namespaceId, status = terminal))
                equip(bindings, case.id)
                val before = buildService(caseRepository = repository, caseLaunchGate = gitGate(repository, bindings))
                before.getCaseRuntime(case.id)
                before.shutdown()
                before.getById(case.id).status shouldBe terminal
                val after = buildService(caseRepository = repository, caseLaunchGate = gitGate(repository, bindings))
                try {
                    shouldThrow<ConflictException> {
                        after.addMessage(case.id, userActor, listOf(MessageContent.Text("Must stay terminal")))
                    }
                } finally { after.shutdown() }
            }
        }

        "shutdown retains the historical non-Git status and fresh-message behavior" {
            val repository = InMemoryCaseRepository()
            val bindings = InMemoryCaseResourceBindingService()
            val before = buildService(caseRepository = repository, caseLaunchGate = gitGate(repository, bindings))
            val case = before.create(Case(namespaceId = namespaceId))
            before.shutdown()
            before.getById(case.id).status shouldBe CaseStatus.KILLED
            val agent = finishingAgent()
            val after = buildService(agent = agent, caseRepository = repository, caseLaunchGate = gitGate(repository, bindings))
            try {
                after.addMessage(case.id, userActor, listOf(MessageContent.Text("Fresh non-Git instruction")))
                withTimeout(3_000) { while (after.getById(case.id).status != CaseStatus.IDLE) delay(10) }
                verify(exactly = 1) { agent.run(any<List<CaseEvent>>(), any()) }
            } finally { after.shutdown() }
        }

        "a user Kill during shutdown remains terminal" {
            val repository = InMemoryCaseRepository()
            val bindings = InMemoryCaseResourceBindingService()
            val realGate = gitGate(repository, bindings)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val gate = object : CaseLaunchGate by realGate {
                override fun keepOpenOnShutdown(caseId: UUID): Boolean {
                    val keepOpen = realGate.keepOpenOnShutdown(caseId)
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    return keepOpen
                }
            }
            val service = buildService(caseRepository = repository, caseLaunchGate = gate)
            val case = service.create(Case(namespaceId = namespaceId))
            equip(bindings, case.id)
            val stopping = async(Dispatchers.IO) { service.shutdown() }
            try {
                entered.await(5, TimeUnit.SECONDS) shouldBe true
                service.killCase(case.id)
                release.countDown()
                withTimeout(3_000) { stopping.await() }
                service.getById(case.id).status shouldBe CaseStatus.KILLED
                service.findActiveRuntime(case.id) shouldBe null
            } finally { release.countDown(); stopping.await() }
        }

        listOf(1, 2).forEach { barrierCheck ->
            "Kill wins over an older Git message paused after acceptance check $barrierCheck" {
                val repository = InMemoryCaseRepository()
                val bindings = InMemoryCaseResourceBindingService()
                val realGate = gitGate(repository, bindings)
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                val checks = AtomicInteger()
                val gate = object : CaseLaunchGate by realGate {
                    override fun requireAccepting(caseId: UUID) {
                        realGate.requireAccepting(caseId)
                        if (checks.incrementAndGet() == barrierCheck) {
                            entered.countDown()
                            check(release.await(5, TimeUnit.SECONDS))
                        }
                    }
                }
                val agent = finishingAgent()
                val events = CaseEventServiceImpl(InMemoryCaseEventRepository())
                val service = buildService(agent = agent, caseRepository = repository,
                    caseEventService = events, caseLaunchGate = gate)
                val case = service.create(Case(namespaceId = namespaceId))
                equip(bindings, case.id)
                val sending = async(Dispatchers.IO) {
                    runCatching { service.addMessage(case.id, userActor, listOf(MessageContent.Text("Older instruction"))) }
                }
                try {
                    entered.await(5, TimeUnit.SECONDS) shouldBe true
                    service.killCase(case.id)
                    val statusesAtKill = events.findByParent(case.id).filterIsInstance<CaseStatusEvent>().size
                    release.countDown()
                    val result = withTimeout(3_000) { sending.await() }
                    if (barrierCheck == 1) result.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
                    else result.isSuccess shouldBe true
                    service.getById(case.id).status shouldBe CaseStatus.KILLED
                    service.findActiveRuntime(case.id) shouldBe null
                    service.trackedExecutionCount shouldBe 0
                    events.findByParent(case.id).filterIsInstance<CaseStatusEvent>().drop(statusesAtKill) shouldBe emptyList()
                    verify(exactly = 0) { agent.run(any<List<CaseEvent>>(), any()) }
                } finally { release.countDown(); sending.await(); service.shutdown() }
            }
        }

        "Kill without a runtime serializes its status write with a concurrent hydration" {
            val storedCases = InMemoryCaseRepository()
            val writingKill = CountDownLatch(1)
            val finishKill = CountDownLatch(1)
            val repository = object : CaseRepository by storedCases {
                override fun save(entity: Case): Case {
                    if (entity.status == CaseStatus.KILLED) {
                        writingKill.countDown()
                        check(finishKill.await(5, TimeUnit.SECONDS))
                    }
                    return storedCases.save(entity)
                }
            }
            val bindings = InMemoryCaseResourceBindingService()
            val realGate = gitGate(repository, bindings)
            val accepted = CountDownLatch(1)
            val hydrated = CountDownLatch(1)
            val checks = AtomicInteger()
            val gate = object : CaseLaunchGate by realGate {
                override fun requireAccepting(caseId: UUID) {
                    val check = checks.incrementAndGet()
                    if (check == 2) hydrated.countDown()
                    realGate.requireAccepting(caseId)
                    if (check == 1) accepted.countDown()
                }
            }
            val agent = finishingAgent()
            val service = buildService(agent = agent, caseRepository = repository, caseLaunchGate = gate)
            val case = storedCases.save(Case(namespaceId = namespaceId))
            equip(bindings, case.id)
            val killing = async(Dispatchers.IO) { service.killCase(case.id) }
            try {
                writingKill.await(5, TimeUnit.SECONDS) shouldBe true
                val sending = async(Dispatchers.IO) {
                    runCatching { service.addMessage(case.id, userActor, listOf(MessageContent.Text("Concurrent instruction"))) }
                }
                try {
                    accepted.await(5, TimeUnit.SECONDS) shouldBe true
                    // Hydration cannot return a stale IDLE runtime while the Kill is being saved.
                    hydrated.await(100, TimeUnit.MILLISECONDS) shouldBe false
                    finishKill.countDown()
                    withTimeout(3_000) { killing.await() }
                    withTimeout(3_000) { sending.await() }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
                    service.getById(case.id).status shouldBe CaseStatus.KILLED
                    service.findActiveRuntime(case.id) shouldBe null
                    verify(exactly = 0) { agent.run(any<List<CaseEvent>>(), any()) }
                } finally { finishKill.countDown(); sending.await() }
            } finally { finishKill.countDown(); killing.await(); service.shutdown() }
        }

        "Kill cancels an admitted Git launch before it can reset the runtime flags" {
            val repository = InMemoryCaseRepository()
            val bindings = InMemoryCaseResourceBindingService()
            val realGate = gitGate(repository, bindings)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val checks = AtomicInteger()
            val gate = object : CaseLaunchGate by realGate {
                override fun canLaunch(caseId: UUID): Boolean {
                    val canLaunch = realGate.canLaunch(caseId)
                    if (checks.incrementAndGet() == 2) {
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    }
                    return canLaunch
                }
            }
            val agent = finishingAgent()
            val service = buildService(agent = agent, caseRepository = repository, caseLaunchGate = gate)
            val case = service.create(Case(namespaceId = namespaceId))
            equip(bindings, case.id)
            try {
                service.addMessage(case.id, userActor, listOf(MessageContent.Text("Admitted instruction")))
                entered.await(5, TimeUnit.SECONDS) shouldBe true
                service.findActiveRuntime(case.id)!!.isRunning() shouldBe false
                service.killCase(case.id)
                release.countDown()
                withTimeout(3_000) { while (service.hasRunningExecutions(listOf(case.id))) delay(10) }
                service.getById(case.id).status shouldBe CaseStatus.KILLED
                service.findActiveRuntime(case.id) shouldBe null
                verify(exactly = 0) { agent.run(any<List<CaseEvent>>(), any()) }
            } finally { release.countDown(); service.shutdown() }
        }

        // -------------------------------------------------------------------------
        // Regression: a held-back run must not revive a case that is no longer PENDING
        // -------------------------------------------------------------------------

        /**
         * Reproduces the real sequence: a message arrives while the workspace is preparing, so the
         * gate holds the run back and the message stays persisted. The case is then left in
         * [statusWhileWaiting] before the gate opens and the sweep resumes it.
         *
         * The message matters: a case with no pending message never reaches the agent anyway, so a
         * test built on an empty case would pass with or without the guard.
         */
        suspend fun resumeAfterDeferral(statusWhileWaiting: CaseStatus): Agent {
            val agent = finishingAgent()
            val caseRepository = InMemoryCaseRepository()
            val gate = TestLaunchGate()
            val service = buildService(agent = agent, caseRepository = caseRepository, caseLaunchGate = gate)
            val case = service.create(Case(namespaceId = namespaceId))

            service.addMessage(
                caseId = case.id,
                actor = userActor,
                content = listOf(MessageContent.Text("hello")),
            )
            caseRepository.save(case.copy(status = statusWhileWaiting))

            gate.open = true
            service.resumeIfPending(case.id)
            // Give a launch, if one happens, the chance to reach the agent before asserting.
            delay(200)
            return agent
        }

        "resumeIfPending does not relaunch a case killed while its workspace was preparing" {
            // A kill sets its flags on the runtime that was live at the time, and CaseRuntime.run()
            // clears them on entry. Without a status guard read from the store, the case comes back
            // as RUNNING the moment preparation ends — the late run the plan forbids.
            val agent = resumeAfterDeferral(CaseStatus.KILLED)

            coVerify(exactly = 0) { agent.run(any<List<CaseEvent>>(), any()) }
        }

        "resumeIfPending does not relaunch a case that already consumed its message" {
            val agent = resumeAfterDeferral(CaseStatus.IDLE)

            coVerify(exactly = 0) { agent.run(any<List<CaseEvent>>(), any()) }
        }

        "resumeIfPending still launches a case that is genuinely pending" {
            // The guard must not turn into a blanket refusal: this is the case the sweep resumes.
            val agent = resumeAfterDeferral(CaseStatus.PENDING)

            coVerify(exactly = 1) { agent.run(any<List<CaseEvent>>(), any()) }
        }

        "a transient launch check failure is retried and the message still runs" {
            val checks = AtomicInteger()
            val gate = object : CaseLaunchGate {
                override fun canLaunch(caseId: UUID): Boolean {
                    if (checks.incrementAndGet() == 1) throw IllegalStateException("Neo4j session expired")
                    return true
                }
            }
            val agent = finishingAgent()
            val service = buildService(agent = agent, caseLaunchGate = gate)
            try {
                val case = service.create(Case(namespaceId = namespaceId))

                service.addMessage(case.id, userActor, listOf(MessageContent.Text("Do the work")))

                withTimeout(3_000) { while (service.getById(case.id).status != CaseStatus.IDLE) delay(10) }
                coVerify(exactly = 1) { agent.run(any<List<CaseEvent>>(), any()) }
            } finally {
                service.shutdown()
            }
        }

        "a failing admission coordination does not fail the stored message" {
            val attempts = AtomicInteger()
            val gate = object : CaseLaunchGate {
                override fun canLaunch(caseId: UUID): Boolean = true

                override fun withAdmission(caseId: UUID, onAvailable: () -> Unit, action: () -> Unit) {
                    if (attempts.incrementAndGet() == 1) throw IllegalStateException("Neo4j session expired")
                    action()
                }
            }
            val agent = finishingAgent()
            val service = buildService(agent = agent, caseLaunchGate = gate)
            try {
                val case = service.create(Case(namespaceId = namespaceId))

                service.addMessage(case.id, userActor, listOf(MessageContent.Text("Do the work")))

                withTimeout(3_000) { while (service.getById(case.id).status != CaseStatus.IDLE) delay(10) }
                coVerify(exactly = 1) { agent.run(any<List<CaseEvent>>(), any()) }
            } finally {
                service.shutdown()
            }
        }

        "a launch check that keeps failing warns the user and returns the case to IDLE" {
            val gate = object : CaseLaunchGate {
                override fun canLaunch(caseId: UUID): Boolean = throw IllegalStateException("Neo4j unavailable")
            }
            val events = CaseEventServiceImpl(InMemoryCaseEventRepository())
            val agent = finishingAgent()
            val service = buildService(agent = agent, caseLaunchGate = gate, caseEventService = events)
            try {
                val case = service.create(Case(namespaceId = namespaceId))

                service.addMessage(case.id, userActor, listOf(MessageContent.Text("Do the work")))

                withTimeout(3_000) { while (events.findByParent(case.id).none { it is WarnEvent }) delay(10) }
                withTimeout(3_000) { while (service.getById(case.id).status != CaseStatus.IDLE) delay(10) }
                service.hasRunningExecutions(listOf(case.id)) shouldBe false
                coVerify(exactly = 0) { agent.run(any<List<CaseEvent>>(), any()) }
                // Giving up is final for this instruction: a later resume must not run it silently.
                service.resumeIfPending(case.id)
                coVerify(exactly = 0) { agent.run(any<List<CaseEvent>>(), any()) }
            } finally {
                service.shutdown()
            }
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
            verify(exactly = 1) {
                allowAllAgentConfigService.findDeployedByNamespaceIdAndUserIdAndName(
                    namespaceId,
                    userId,
                    agentName,
                )
            }
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
            verify(exactly = 0) {
                allowAllAgentConfigService.findDeployedByNamespaceIdAndUserIdAndName(
                    any(),
                    any(),
                    any(),
                )
            }
        }

        "case transitions to ERROR when userId does not resolve to a known user" {
            val unknownUserId = UUID.randomUUID()
            val actorWithUnknownUser =
                Actor(id = unknownUserId.toString(), displayName = "Ghost", role = ActorRole.USER)
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
            verify(exactly = 1) {
                allowAllAgentConfigService.findDeployedByNamespaceIdAndUserIdAndName(
                    any(),
                    any(),
                    agentName,
                )
            }
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
                    agentService = agentService,
                    agentConfigService = allowAllAgentConfigService,
                    agentConfigProperties = AgentConfigProperties(),
                    caseRepository = InMemoryCaseRepository(),
                    caseEventService = caseEventService,
                    userService = userService,
                    namespaceService = namespaceService,
                    caseConfig = CaseConfigProperties(),
                    permissionService = permissionService,
                    promptService = promptService,
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
            verify(exactly = 1) {
                allowAllAgentConfigService.findDeployedByNamespaceIdAndUserIdAndName(
                    namespaceId,
                    userId,
                    agentName,
                )
            }
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
                    promptService = promptService,
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
                    promptService = promptService,
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
            verify(exactly = 1) {
                allowAllAgentConfigService.findDeployedByNamespaceIdAndUserIdAndName(
                    namespaceId,
                    userId,
                    agentName,
                )
            }
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
                    promptService = promptService,
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
            ) {
                allowAllAgentConfigService.findDeployedByNamespaceIdAndUserIdAndName(
                    namespaceId,
                    userId,
                    namespaceDefaultName,
                )
            }
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
                    agentService = agentService,
                    agentConfigService = allowAllAgentConfigService,
                    agentConfigProperties = AgentConfigProperties(agentName = null), // no environment default either
                    caseRepository = InMemoryCaseRepository(),
                    caseEventService = caseEventService,
                    userService = userService,
                    namespaceService = namespaceService,
                    caseConfig = CaseConfigProperties(),
                    permissionService = permissionService,
                    promptService = promptService,
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
            verify(exactly = 0) {
                allowAllAgentConfigService.findDeployedByNamespaceIdAndUserIdAndName(
                    any(),
                    any(),
                    any(),
                )
            }
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
            verify(exactly = 1) {
                allowAllAgentConfigService.findDeployedByNamespaceIdAndUserIdAndName(
                    namespaceId,
                    userId,
                    agentName,
                )
            }
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
                    promptService = promptService,
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
            verify(exactly = 0) {
                allowAllAgentConfigService.findDeployedByNamespaceIdAndUserIdAndName(
                    any(),
                    any(),
                    any(),
                )
            }
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
                    promptService = promptService,
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
            ) {
                allowAllAgentConfigService.findDeployedByNamespaceIdAndUserIdAndName(
                    namespaceId,
                    userId,
                    unavailableAgentName,
                )
            }
            verify(exactly = 1) {
                allowAllAgentConfigService.findDeployedByNamespaceIdAndUserIdAndName(
                    namespaceId,
                    userId,
                    agentName,
                )
            }
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
                    promptService = promptService,
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
            ) {
                allowAllAgentConfigService.findDeployedByNamespaceIdAndUserIdAndName(
                    namespaceId,
                    userId,
                    selectedAgentName,
                )
            }
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
                    promptService = promptService,
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
            verify(exactly = 1) {
                allowAllAgentConfigService.findDeployedByNamespaceIdAndUserIdAndName(
                    namespaceId,
                    userId,
                    inspectorName,
                )
            }
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

        "completed execution jobs are released after idle eviction and a later message still runs" {
            val agent = finishingAgent()
            val service = buildService(agent = agent, idleEvictionGraceMs = 25L)
            try {
                val case = service.create(Case(namespaceId = namespaceId))
                repeat(2) { index ->
                    service.addMessage(case.id, userActor, listOf(MessageContent.Text("message $index")))
                    withTimeout(3_000) {
                        while (service.findActiveRuntime(case.id) != null) delay(10)
                    }
                    service.hasRunningExecutions(listOf(case.id)) shouldBe false
                    service.trackedExecutionCount shouldBe 0
                }
                verify(exactly = 2) { agent.run(any<List<CaseEvent>>(), any()) }
            } finally {
                service.shutdown()
            }
        }

        "an admitted launch blocks cleanup until its gate check completes and can resume afterward" {
            val enteredGate = CountDownLatch(1)
            val releaseGate = CountDownLatch(1)
            val checks = AtomicInteger()
            val gate = object : CaseLaunchGate {
                override fun canLaunch(caseId: UUID): Boolean {
                    if (checks.incrementAndGet() != 2) return true
                    enteredGate.countDown()
                    check(releaseGate.await(5, TimeUnit.SECONDS))
                    return false
                }
            }
            val agent = finishingAgent()
            val service = buildService(agent = agent, caseLaunchGate = gate)
            try {
                val case = service.create(Case(namespaceId = namespaceId))
                service.addMessage(case.id, userActor, listOf(MessageContent.Text("pending instruction")))
                enteredGate.await(5, TimeUnit.SECONDS) shouldBe true
                // The launch is owned even though the runtime has not started yet.
                service.findActiveRuntime(case.id)!!.isRunning() shouldBe false
                service.hasRunningExecutions(listOf(case.id)) shouldBe true
                service.trackedExecutionCount shouldBe 1

                releaseGate.countDown()
                withTimeout(3_000) { while (service.trackedExecutionCount != 0) delay(10) }
                service.hasRunningExecutions(listOf(case.id)) shouldBe false
                verify(exactly = 0) { agent.run(any<List<CaseEvent>>(), any()) }

                service.resumeIfPending(case.id)
                withTimeout(3_000) {
                    while (service.getById(case.id).status != CaseStatus.IDLE || service.trackedExecutionCount != 0) delay(10)
                }
                verify(exactly = 1) { agent.run(any<List<CaseEvent>>(), any()) }
            } finally {
                releaseGate.countDown()
                service.shutdown()
            }
        }

        "shutdown during admission does not retain a job cancelled before insertion" {
            lateinit var service: CaseServiceImpl
            val gate = object : CaseLaunchGate {
                override fun canLaunch(caseId: UUID): Boolean {
                    service.shutdown()
                    return true
                }
            }
            val agent = finishingAgent()
            service = buildService(agent = agent, caseLaunchGate = gate)
            try {
                val case = service.create(Case(namespaceId = namespaceId))
                // The scope is cancelled between admission and job creation. launch() returns
                // an already-completed job, so cleanup must be registered after map insertion.
                service.addMessage(case.id, userActor, listOf(MessageContent.Text("pending instruction")))
                service.trackedExecutionCount shouldBe 0
                service.hasRunningExecutions(listOf(case.id)) shouldBe false
                verify(exactly = 0) { agent.run(any<List<CaseEvent>>(), any()) }
            } finally {
                service.shutdown()
            }
        }

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
                    promptService = promptService,
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
                    promptService = promptService,
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
            verify(exactly = 1) {
                allowAllAgentConfigService.findDeployedByNamespaceIdAndUserIdAndName(
                    namespaceId,
                    userId,
                    inspectorName,
                )
            }
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
            verify(exactly = 2) {
                allowAllAgentConfigService.findDeployedByNamespaceIdAndUserIdAndName(
                    namespaceId,
                    userId,
                    agentName,
                )
            }
        }

        // -------------------------------------------------------------------------
        // Rehydration: crash recovery from persisted AgentRunningEvent
        // -------------------------------------------------------------------------

        // -------------------------------------------------------------------------
        // Multi-line prompt resolution
        // -------------------------------------------------------------------------

        "multi-command prompt resolution executes each command as a separate sequential agent turn" {
            // Override the default promptService mock to return a real prompt with 3 content lines
            val multiPromptService =
                mockk<PromptService>(relaxed = true) {
                    every { findEffective(any(), any()) } returns
                        listOf(
                            Prompt(
                                metadata = EntityMetadata(),
                                name = "multi-prompt",
                                content = listOf("resolved-1", "resolved-2", "resolved-3"),
                            ),
                        )
                }

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
                    InMemoryCaseRepository(),
                    CaseEventServiceImpl(InMemoryCaseEventRepository()),
                    userService,
                    namespaceService,
                    caseConfig = CaseConfigProperties(),
                    permissionService = permissionService,
                    promptService = multiPromptService,
                    caseNamingService = noOpCaseNamingService,
                )

            val case = service.create(Case(namespaceId = namespaceId))
            val runtime = service.getCaseRuntime(case.id)
            val scope = CoroutineScope(Dispatchers.IO)

            val idleCount =
                java.util.concurrent.atomic
                    .AtomicInteger(0)
            val awaiter =
                scope.launch {
                    withTimeout(8_000) {
                        runtime.events
                            .filterIsInstance<CaseStatusEvent>()
                            .first { event ->
                                when {
                                    event.status == CaseStatus.ERROR -> true
                                    event.status == CaseStatus.IDLE -> idleCount.incrementAndGet() >= 3
                                    else -> false
                                }
                            }
                    }
                }
            awaitSubscribers(runtime)
            service.addMessage(
                caseId = case.id,
                actor = userActor,
                content = listOf(MessageContent.Text("/multi-prompt")),
            )
            awaiter.join()

            runCallCount shouldBe 3
            service.getById(case.id).status shouldBe CaseStatus.IDLE
        }

        // -------------------------------------------------------------------------
        // Prompt resolution failure: case must still reach IDLE
        // -------------------------------------------------------------------------

        "PromptResolutionException emits WarnEvent and case reaches IDLE" {
            // Regression test for the missing scope.launch { runtime.run() } in the
            // PromptResolutionException catch block.
            //
            // Before the fix, addMessage() caught the exception, called addUserMessage()
            // (which stored MessageEvent + AgentSelectedEvent), emitted a WarnEvent,
            // then returned without launching run(). The runtime had a pending
            // AgentSelectedEvent in its history but no one ever called run(), so the
            // case stayed in PENDING status forever — it never transitioned to IDLE.
            //
            // After the fix, run() is launched even when prompt resolution fails,
            // and the runtime processes the AgentSelectedEvent normally.

            val throwingPromptService =
                mockk<PromptService>(relaxed = true) {
                    every { findEffective(any(), any()) } throws
                        io.whozoss.agentos.exception
                            .PromptResolutionException("cycle detected")
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
                    every { resolveAgentName(any(), any(), any()) } returns agentName
                    coEvery { findAgentByName(agentName, any(), any()) } returns finishingAgent()
                }
            val userService =
                mockk<UserService> {
                    every { findById(userId) } returns activeUser
                    every { getById(userId) } returns activeUser
                }
            val caseEventService = CaseEventServiceImpl(InMemoryCaseEventRepository())
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
                    promptService = throwingPromptService,
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
                content = listOf(MessageContent.Text("/some-prompt")),
            )
            awaiter.join()

            // The case must reach IDLE — not stay blocked in PENDING
            service.getById(case.id).status shouldBe CaseStatus.IDLE
            // A WarnEvent must have been persisted describing the resolution failure
            val persistedEvents = caseEventService.findByParent(case.id)
            persistedEvents.filterIsInstance<WarnEvent>() shouldHaveAtLeastSize 1
            persistedEvents
                .filterIsInstance<WarnEvent>()
                .any { it.message.contains("Prompt resolution failed") } shouldBe true
        }

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
                    promptService = promptService,
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
