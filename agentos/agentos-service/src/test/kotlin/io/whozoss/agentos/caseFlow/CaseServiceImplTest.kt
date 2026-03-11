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
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.entity.EntityMetadata
import kotlinx.coroutines.flow.flow
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
 * The [CaseRuntimeTest] unit tests exercise [CaseRuntime] in isolation with a mock
 * runAgent that calls pushEvents directly. These service-level tests catch regressions
 * in [CaseServiceImpl.runAgent] itself — specifically that it pushes agent-produced
 * events back into the runtime so the loop can terminate.
 */
class CaseServiceImplTest : StringSpec({
    timeout = 10_000

    val projectId: UUID = UUID.randomUUID()
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
                            projectId = projectId,
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

    "agent runs exactly once and case reaches STOPPED after a single message" {
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
                                projectId = projectId,
                                caseId = caseId,
                                agentId = agentId,
                                agentName = agentName,
                            ),
                        )
                    }
                }
            }

        val service = buildService(countingAgent)
        val case = service.create(Case(projectId = projectId))

        service.addMessage(
            caseId = case.id,
            actor = userActor,
            content = listOf(MessageContent.Text("hello")),
        )

        // Give the background coroutine time to complete.
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val current = service.getById(case.id)
            if (current.status == CaseStatus.STOPPED || current.status == CaseStatus.ERROR) break
            Thread.sleep(50)
        }

        runCallCount shouldBe 1
        service.getById(case.id).status shouldBe CaseStatus.STOPPED
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
        val case = service.create(Case(projectId = projectId))

        service.addMessage(
            caseId = case.id,
            actor = userActor,
            content = listOf(MessageContent.Text("hi")),
        )

        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (service.getById(case.id).status == CaseStatus.STOPPED) break
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
                                projectId = projectId,
                                caseId = caseId,
                                agentId = agentId,
                                agentName = agentName,
                            ),
                        )
                    }
                }
            }

        val service = buildService(countingAgent)
        val case = service.create(Case(projectId = projectId))

        // First message
        service.addMessage(
            caseId = case.id,
            actor = userActor,
            content = listOf(MessageContent.Text("first")),
        )
        val deadline1 = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline1) {
            if (service.getById(case.id).status == CaseStatus.STOPPED) break
            Thread.sleep(50)
        }
        service.getById(case.id).status shouldBe CaseStatus.STOPPED
        runCallCount shouldBe 1

        // Wait until the runtime is fully evicted from activeRuntimes before sending the
        // second message. The runtime is evicted synchronously in handleStatusChange when
        // STOPPED is reached, but run()'s finally block (which clears runInFlight) executes
        // after that. If addMessage reuses the same runtime instance while runInFlight is
        // still true, run() exits immediately via the AtomicBoolean guard and the second
        // agent call never happens.
        val evictDeadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < evictDeadline) {
            if (service.findActiveRuntime(case.id) == null) break
            Thread.sleep(10)
        }

        // Second message — the case is STOPPED; addMessage must restart it.
        // We wait for runCallCount to reach 2 rather than polling status, which avoids
        // a race where the status briefly reads STOPPED from the previous run before
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
        // Give the second run a moment to complete and persist STOPPED
        val deadline3 = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline3) {
            if (service.getById(case.id).status == CaseStatus.STOPPED) break
            Thread.sleep(50)
        }
        service.getById(case.id).status shouldBe CaseStatus.STOPPED
    }
})
