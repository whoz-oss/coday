package io.whozoss.agentos.caseFlow

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.agent.Agent
import io.whozoss.agentos.sdk.caseEvent.*
import io.whozoss.agentos.sdk.entity.EntityMetadata
import kotlinx.coroutines.flow.flow
import java.util.*

class CaseRuntimeTest :
    StringSpec({
        timeout = 5000

        val projectId: UUID = UUID.randomUUID()
        val userActor = Actor(id = "user-123", displayName = "Test User", role = ActorRole.USER)
        val userMessage = listOf(MessageContent.Text("hello"))

        /**
         * Build a mock Agent whose run() immediately emits AgentFinishedEvent.
         */
        fun finishingAgent(name: String): Agent {
            val agentId = UUID.nameUUIDFromBytes(name.toByteArray())
            return mockk<Agent>(name = "agent-$name") {
                every { metadata } returns EntityMetadata(id = agentId)
                every { this@mockk.name } returns name
                every { run(any<List<CaseEvent>>()) } answers {
                    val caseId = firstArg<List<CaseEvent>>().first().caseId
                    flow {
                        emit(
                            AgentFinishedEvent(
                                projectId = projectId,
                                caseId = caseId,
                                agentId = agentId,
                                agentName = name,
                            ),
                        )
                    }
                }
            }
        }

        /**
         * Build a [CaseRuntime] with controlled callbacks.
         *
         * [findAgent] and [resolveAgent] are mockable function references so tests can
         * use [verify] on them. [savedEvents] captures everything passed to [emitAndStoreEvent].
         */
        data class TestFixture(
            val runtime: CaseRuntime,
            val findAgent: (String) -> Agent,
            val resolveAgent: (String) -> String?,
            val getDefaultAgentName: () -> String?,
            val savedEvents: MutableList<CaseEvent>,
        )

        fun buildRuntime(
            agentName: String = "default-agent",
            agent: Agent = finishingAgent(agentName),
        ): TestFixture {
            val findAgentFn = mockk<(String) -> Agent>()
            every { findAgentFn(agentName) } returns agent

            val resolveAgentFn = mockk<(String) -> String?>()
            every { resolveAgentFn(any()) } returns null

            val getDefaultAgentNameFn = mockk<() -> String?>()
            every { getDefaultAgentNameFn() } returns agentName

            val savedEvents = mutableListOf<CaseEvent>()

            val runtime =
                CaseRuntime(
                    id = UUID.randomUUID(),
                    projectId = projectId,
                    updateStatus = { _, _ -> },
                    emitAndStoreEvent = { event, caseRuntime ->
                        savedEvents.add(event)
                        caseRuntime.emitEventFromThisCase(event)
                    },
                    resolveAgent = resolveAgentFn,
                    getDefaultAgentName = getDefaultAgentNameFn,
                    findAgent = findAgentFn,
                )
            return TestFixture(runtime, findAgentFn, resolveAgentFn, getDefaultAgentNameFn, savedEvents)
        }

        // -------------------------------------------------------------------------
        // Core regression: agent instantiated exactly once per run
        // -------------------------------------------------------------------------

        "agent is instantiated exactly once when using the default agent" {
            val (runtime, findAgent) = buildRuntime()

            runtime.addUserMessage(userActor, userMessage)

            verify(exactly = 1) { findAgent(any()) }
        }

        "getDefaultAgentName is called exactly once when selecting the default agent" {
            val (runtime, _, _, getDefaultAgentName) = buildRuntime()

            runtime.addUserMessage(userActor, userMessage)

            verify(exactly = 1) { getDefaultAgentName() }
        }

        // -------------------------------------------------------------------------
        // Event sequence
        // -------------------------------------------------------------------------

        "AgentSelectedEvent then AgentRunningEvent then AgentFinishedEvent are saved in order" {
            val (runtime, _, _, _, savedEvents) = buildRuntime()

            runtime.addUserMessage(userActor, userMessage)

            val agentEvents =
                savedEvents.filter {
                    it is AgentSelectedEvent || it is AgentRunningEvent || it is AgentFinishedEvent
                }
            agentEvents shouldHaveAtLeastSize 3
            agentEvents[0].shouldBeInstanceOf<AgentSelectedEvent>()
            agentEvents[1].shouldBeInstanceOf<AgentRunningEvent>()
            agentEvents[2].shouldBeInstanceOf<AgentFinishedEvent>()
        }

        "AgentSelectedEvent and AgentRunningEvent carry the same agentId and agentName" {
            val (runtime, _, _, _, savedEvents) = buildRuntime(agentName = "gemini-flash")

            runtime.addUserMessage(userActor, userMessage)

            val selected = savedEvents.filterIsInstance<AgentSelectedEvent>().first()
            val running = savedEvents.filterIsInstance<AgentRunningEvent>().first()

            selected.agentName shouldBe "gemini-flash"
            running.agentName shouldBe "gemini-flash"
            selected.agentId shouldBe running.agentId
        }

        // -------------------------------------------------------------------------
        // detectAgentSelection — @mention path
        // -------------------------------------------------------------------------

        "@mention resolves the agent by name without instantiating it at selection time" {
            val mentionedAgent = finishingAgent("special-agent")
            val (runtime, findAgent, resolveAgent) = buildRuntime()

            every { resolveAgent("special-agent") } returns "special-agent"
            every { findAgent("special-agent") } returns mentionedAgent

            runtime.addUserMessage(userActor, listOf(MessageContent.Text("@special-agent do something")))

            verify(exactly = 1) { resolveAgent("special-agent") }
            verify(exactly = 1) { findAgent("special-agent") }
        }

        "@mention instantiates the agent exactly once even though it went through AgentSelectedEvent" {
            val mentionedAgent = finishingAgent("special-agent")
            val (runtime, findAgent, resolveAgent) = buildRuntime()

            every { resolveAgent("special-agent") } returns "special-agent"
            every { findAgent("special-agent") } returns mentionedAgent

            runtime.addUserMessage(userActor, listOf(MessageContent.Text("@special-agent do something")))

            verify(exactly = 1) { findAgent("special-agent") }
        }

        "@mention does not call getDefaultAgentName when an explicit agent is selected" {
            val mentionedAgent = finishingAgent("special-agent")
            val (runtime, findAgent, resolveAgent, getDefaultAgentName) = buildRuntime()

            every { resolveAgent("special-agent") } returns "special-agent"
            every { findAgent("special-agent") } returns mentionedAgent

            runtime.addUserMessage(userActor, listOf(MessageContent.Text("@special-agent do something")))

            verify(exactly = 0) { getDefaultAgentName() }
        }

        "@mention emits WarnEvent when the agent is not found and falls back to default" {
            val (runtime, _, _, _, savedEvents) = buildRuntime(agentName = "default-agent")

            runtime.addUserMessage(userActor, listOf(MessageContent.Text("@unknown-agent hello")))

            val warn = savedEvents.filterIsInstance<WarnEvent>().firstOrNull()
            warn.shouldNotBeNull()
            warn.message shouldBe "Agent 'unknown-agent' not found"

            savedEvents.filterIsInstance<AgentSelectedEvent>().first().agentName shouldBe "default-agent"
        }

        // -------------------------------------------------------------------------
        // processNextStep scope: must not run agents, only advance state
        // -------------------------------------------------------------------------

        "processNextStep does not call findAgent when it sees AgentSelectedEvent — only emits AgentRunningEvent" {
            val agentName = "state-machine-agent"
            val blockingAgent: Agent =
                mockk {
                    every { metadata } returns EntityMetadata(id = UUID.nameUUIDFromBytes(agentName.toByteArray()))
                    every { name } returns agentName
                    every { run(any<List<CaseEvent>>()) } answers {
                        flow<CaseEvent> {
                            emit(
                                AgentFinishedEvent(
                                    projectId = projectId,
                                    caseId = firstArg<List<CaseEvent>>().first().caseId,
                                    agentId = UUID.nameUUIDFromBytes(agentName.toByteArray()),
                                    agentName = agentName,
                                ),
                            )
                        }
                    }
                }

            val findAgentFn = mockk<(String) -> Agent>()
            every { findAgentFn(agentName) } returns blockingAgent

            val resolveAgentFn = mockk<(String) -> String?>()
            every { resolveAgentFn(any()) } returns null

            val getDefaultAgentNameFn = mockk<() -> String?>()
            every { getDefaultAgentNameFn() } returns agentName

            val savedEvents = mutableListOf<CaseEvent>()
            val runtime =
                CaseRuntime(
                    id = UUID.randomUUID(),
                    projectId = projectId,
                    updateStatus = { _, _ -> },
                    emitAndStoreEvent = { event, caseRuntime ->
                        savedEvents.add(event)
                        caseRuntime.emitEventFromThisCase(event)
                    },
                    resolveAgent = resolveAgentFn,
                    getDefaultAgentName = getDefaultAgentNameFn,
                    findAgent = findAgentFn,
                )

            runtime.addUserMessage(userActor, userMessage)

            savedEvents.filterIsInstance<AgentRunningEvent>().shouldHaveAtLeastSize(1)
            verify(exactly = 1) { findAgentFn(agentName) }
        }

        "processNextStep emits AgentRunningEvent before findAgent is ever called" {
            val agentName = "ordered-agent"
            val callOrder = mutableListOf<String>()

            val orderedAgent: Agent =
                mockk {
                    every { metadata } returns EntityMetadata(id = UUID.nameUUIDFromBytes(agentName.toByteArray()))
                    every { name } returns agentName
                    every { run(any<List<CaseEvent>>()) } answers {
                        callOrder.add("agent.run")
                        flow {
                            emit(
                                AgentFinishedEvent(
                                    projectId = projectId,
                                    caseId = firstArg<List<CaseEvent>>().first().caseId,
                                    agentId = UUID.nameUUIDFromBytes(agentName.toByteArray()),
                                    agentName = agentName,
                                ),
                            )
                        }
                    }
                }

            val findAgentFn = mockk<(String) -> Agent>()
            every { findAgentFn(agentName) } answers {
                callOrder.add("findAgent")
                orderedAgent
            }

            val resolveAgentFn = mockk<(String) -> String?>()
            every { resolveAgentFn(any()) } returns null

            val getDefaultAgentNameFn = mockk<() -> String?>()
            every { getDefaultAgentNameFn() } returns agentName

            val runtime =
                CaseRuntime(
                    id = UUID.randomUUID(),
                    projectId = projectId,
                    updateStatus = { _, _ -> },
                    emitAndStoreEvent = { event, caseRuntime ->
                        if (event is AgentRunningEvent) callOrder.add("AgentRunningEvent saved")
                        caseRuntime.emitEventFromThisCase(event)
                    },
                    resolveAgent = resolveAgentFn,
                    getDefaultAgentName = getDefaultAgentNameFn,
                    findAgent = findAgentFn,
                )

            runtime.addUserMessage(userActor, userMessage)

            val runningIdx = callOrder.indexOf("AgentRunningEvent saved")
            val findIdx = callOrder.indexOf("findAgent")
            val runIdx = callOrder.indexOf("agent.run")

            (runningIdx >= 0) shouldBe true
            (findIdx > runningIdx) shouldBe true
            (runIdx > findIdx) shouldBe true
        }

        // -------------------------------------------------------------------------
        // AgentRunningEvent already in history (case resumed mid-run)
        // -------------------------------------------------------------------------

        "agent is instantiated exactly once when AgentRunningEvent is already in the event list" {
            val agentName = "gemini-flash"
            val caseId = UUID.randomUUID()
            val agentId = UUID.nameUUIDFromBytes(agentName.toByteArray())
            val agent = finishingAgent(agentName)

            val findAgentFn = mockk<(String) -> Agent>()
            every { findAgentFn(agentName) } returns agent

            val resolveAgentFn = mockk<(String) -> String?>()
            every { resolveAgentFn(any()) } returns null

            val getDefaultAgentNameFn = mockk<() -> String?>()
            every { getDefaultAgentNameFn() } returns agentName

            val savedEvents = mutableListOf<CaseEvent>()

            val existingUserMessage =
                MessageEvent(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    projectId = projectId,
                    caseId = caseId,
                    actor = userActor,
                    content = userMessage,
                )
            val existingRunningEvent =
                AgentRunningEvent(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    projectId = projectId,
                    caseId = caseId,
                    agentId = agentId,
                    agentName = agentName,
                )

            val runtime =
                CaseRuntime(
                    id = caseId,
                    projectId = projectId,
                    updateStatus = { _, _ -> },
                    emitAndStoreEvent = { event, caseRuntime ->
                        savedEvents.add(event)
                        caseRuntime.emitEventFromThisCase(event)
                    },
                    resolveAgent = resolveAgentFn,
                    getDefaultAgentName = getDefaultAgentNameFn,
                    findAgent = findAgentFn,
                )
            runtime.pushEvents(listOf(existingUserMessage, existingRunningEvent))

            runtime.addUserMessage(userActor, userMessage)

            verify(exactly = 1) { findAgentFn(agentName) }
            savedEvents.filterIsInstance<AgentSelectedEvent>() shouldBe emptyList()
        }
    })
