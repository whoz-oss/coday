package io.whozoss.agentos.caseFlow

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.agent.AgentService
import io.whozoss.agentos.caseEvent.CaseEventServiceImpl
import io.whozoss.agentos.caseEvent.InMemoryCaseEventRepository
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
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.entity.EntityMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.UUID

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
        val userActor = Actor(id = "user-1", displayName = "Test User", role = ActorRole.USER)
        val agentName = "test-agent"
        val agentId: UUID = UUID.nameUUIDFromBytes(agentName.toByteArray())

        /** Build a mock Agent that immediately emits AgentFinishedEvent. */
        fun finishingAgent(): Agent =
            mockk<Agent> {
                every { metadata } returns EntityMetadata(id = agentId)
                every { name } returns agentName
                every { run(any<List<CaseEvent>>()) } answers {
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

        /** Build a fully-wired [CaseServiceImpl] backed by in-memory repositories. */
        fun buildService(agent: Agent = finishingAgent()): CaseServiceImpl {
            val agentService =
                mockk<AgentService> {
                    every { getDefaultAgentName() } returns agentName
                    every { findAgentByName(agentName) } returns agent
                }
            val caseRepository = InMemoryCaseRepository()
            val caseEventService = CaseEventServiceImpl(InMemoryCaseEventRepository())
            return CaseServiceImpl(agentService, caseRepository, caseEventService)
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
                    every { run(any<List<CaseEvent>>()) } answers {
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

            service.addMessage(
                caseId = case.id,
                actor = userActor,
                content = listOf(MessageContent.Text("hello")),
            )

            // Give the background coroutine time to complete.
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline) {
                val current = service.getById(case.id)
                if (current.status == CaseStatus.IDLE || current.status == CaseStatus.ERROR) break
                Thread.sleep(50)
            }

            runCallCount shouldBe 1
            service.getById(case.id).status shouldBe CaseStatus.IDLE
        }

        // -------------------------------------------------------------------------
        // Event sequence persisted to the event store
        // -------------------------------------------------------------------------

        "persisted events contain the full agent lifecycle sequence" {
            val caseEventService = CaseEventServiceImpl(InMemoryCaseEventRepository())
            val agentService =
                mockk<AgentService> {
                    every { getDefaultAgentName() } returns agentName
                    every { findAgentByName(agentName) } returns finishingAgent()
                }
            val service = CaseServiceImpl(agentService, InMemoryCaseRepository(), caseEventService)
            val case = service.create(Case(namespaceId = namespaceId))

            service.addMessage(
                caseId = case.id,
                actor = userActor,
                content = listOf(MessageContent.Text("hi")),
            )

            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline) {
                if (service.getById(case.id).status == CaseStatus.IDLE) break
                Thread.sleep(50)
            }

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
            // Use a separate CoroutineScope so the collector runs concurrently with
            // the service calls on the main test thread.
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

            // Give the collector a moment to subscribe to the SharedFlow.
            Thread.sleep(100)

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

            Thread.sleep(100)

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

            Thread.sleep(100)

            // Route the ERROR status change through handleStatusChange.
            service.update(case.copy(status = CaseStatus.ERROR))

            collectJob.join()

            errorEventReceived.get() shouldBe true
        }

        // -------------------------------------------------------------------------
        // Second message after the first completes
        // -------------------------------------------------------------------------

        "agent runs once per message when two messages are sent sequentially" {
            var runCallCount = 0
            val countingAgent =
                mockk<Agent> {
                    every { metadata } returns EntityMetadata(id = agentId)
                    every { name } returns agentName
                    every { run(any<List<CaseEvent>>()) } answers {
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

            // First message
            service.addMessage(
                caseId = case.id,
                actor = userActor,
                content = listOf(MessageContent.Text("first")),
            )
            val deadline1 = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline1) {
                if (service.getById(case.id).status == CaseStatus.IDLE) break
                Thread.sleep(50)
            }
            service.getById(case.id).status shouldBe CaseStatus.IDLE
            runCallCount shouldBe 1

            // Wait until runInFlight is cleared (run()'s finally block) before sending the
            // second message. The runtime stays alive (IDLE is non-terminal), but run() must
            // have fully exited so the AtomicBoolean guard allows re-entry.
            val idleDeadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < idleDeadline) {
                if (!service.getCaseRuntime(case.id).isRunning()) break
                Thread.sleep(10)
            }

            // Second message — the runtime is still alive (IDLE is non-terminal).
            // We wait for runCallCount to reach 2 rather than polling status, which avoids
            // a race where the status briefly reads IDLE from the previous run before
            // the second run transitions it to RUNNING.
            service.addMessage(
                caseId = case.id,
                actor = userActor,
                content = listOf(MessageContent.Text("second")),
            )
            val deadline2 = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline2) {
                if (runCallCount >= 2) break
                Thread.sleep(50)
            }
            runCallCount shouldBe 2
            // Give the second run a moment to complete and reach IDLE
            val deadline3 = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline3) {
                if (service.getById(case.id).status == CaseStatus.IDLE) break
                Thread.sleep(50)
            }
            service.getById(case.id).status shouldBe CaseStatus.IDLE
        }
    })
