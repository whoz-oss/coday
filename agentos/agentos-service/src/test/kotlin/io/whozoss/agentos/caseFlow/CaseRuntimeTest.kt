package io.whozoss.agentos.caseFlow

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.agent.AgentService
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
         * The caseId is read from the first event in the provided list so the event
         * is recognised by CaseRuntime.processNextStep() as belonging to the current case.
         */
        fun finishingAgent(name: String): Agent {
            val agentId = UUID.nameUUIDFromBytes(name.toByteArray())
            return mockk<Agent>(name = "agent-$name") {
                every { metadata } returns EntityMetadata(id = agentId)
                every { this@mockk.name } returns name
                every { run(any<List<CaseEvent>>()) } answers {
                    val events = firstArg<List<CaseEvent>>()
                    val caseId = events.first().caseId
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
         * Build a [CaseRuntime] with mocked dependencies.
         *
         * All events passed through the [onEvent] callback are captured in [savedEvents]
         * so tests can assert on the full ordered sequence without relying on the hot SharedFlow.
         */
        data class TestFixture(
            val runtime: CaseRuntime,
            val agentService: AgentService,
            val savedEvents: MutableList<CaseEvent>,
        )

        fun buildRuntime(
            agentName: String = "default-agent",
            agent: Agent = finishingAgent(agentName),
        ): TestFixture {
            val agentService: AgentService = mockk()
            every { agentService.getDefaultAgentName() } returns agentName
            every { agentService.findAgentByName(agentName) } returns agent
            every { agentService.resolveAgentName(any<String>()) } returns null
            coEvery { agentService.cleanup() } returns Unit

            val savedEvents = mutableListOf<CaseEvent>()

            val runtime =
                CaseRuntime(
                    id = UUID.randomUUID(),
                    projectId = projectId,
                    agentService = agentService,
                    updateStatus = { _, _ -> },
                    emitAndStoreEvent = { event, caseRuntime ->
                        savedEvents.add(event)
                        caseRuntime.emitEventFromThisCase(event)
                    },
                )
            return TestFixture(runtime, agentService, savedEvents)
        }

        // -------------------------------------------------------------------------
        // Core regression: agent instantiated exactly once per run
        // -------------------------------------------------------------------------

        "agent is instantiated exactly once when using the default agent" {
            val (runtime, agentService) = buildRuntime()

            runtime.addUserMessage(userActor, userMessage)

            verify(exactly = 1) { agentService.findAgentByName(any<String>()) }
        }

        "getDefaultAgent is never called when selecting the default agent" {
            val (runtime, agentService) = buildRuntime()

            runtime.addUserMessage(userActor, userMessage)

            verify(exactly = 0) { agentService.getDefaultAgent() }
            verify(exactly = 1) { agentService.getDefaultAgentName() }
        }

        // -------------------------------------------------------------------------
        // Event sequence
        // -------------------------------------------------------------------------

        "AgentSelectedEvent then AgentRunningEvent then AgentFinishedEvent are saved in order" {
            val (runtime, _, savedEvents) = buildRuntime()

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
            val (runtime, _, savedEvents) = buildRuntime(agentName = "gemini-flash")

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
            val (runtime, agentService) = buildRuntime()

            every { agentService.resolveAgentName("special-agent") } returns "special-agent"
            every { agentService.findAgentByName("special-agent") } returns mentionedAgent

            runtime.addUserMessage(userActor, listOf(MessageContent.Text("@special-agent do something")))

            verify(exactly = 1) { agentService.resolveAgentName("special-agent") }
            verify(exactly = 1) { agentService.findAgentByName("special-agent") }
            verify(exactly = 0) { agentService.getDefaultAgentName() }
        }

        "@mention instantiates the agent exactly once even though it went through AgentSelectedEvent" {
            val mentionedAgent = finishingAgent("special-agent")
            val (runtime, agentService) = buildRuntime()

            every { agentService.resolveAgentName("special-agent") } returns "special-agent"
            every { agentService.findAgentByName("special-agent") } returns mentionedAgent

            runtime.addUserMessage(userActor, listOf(MessageContent.Text("@special-agent do something")))

            verify(exactly = 1) { agentService.findAgentByName("special-agent") }
        }

        "@mention emits WarnEvent when the agent is not found and falls back to default" {
            val (runtime, agentService, savedEvents) = buildRuntime(agentName = "default-agent")

            every { agentService.resolveAgentName("unknown-agent") } returns null

            runtime.addUserMessage(userActor, listOf(MessageContent.Text("@unknown-agent hello")))

            val warn = savedEvents.filterIsInstance<WarnEvent>().firstOrNull()
            warn.shouldNotBeNull()
            warn.message shouldBe "Agent 'unknown-agent' not found"

            savedEvents.filterIsInstance<AgentSelectedEvent>().first().agentName shouldBe "default-agent"
        }

        // -------------------------------------------------------------------------
        // processNextStep scope: must not run agents, only advance state
        // -------------------------------------------------------------------------

        "processNextStep does not call findAgentByName when it sees AgentSelectedEvent — only emits AgentRunningEvent" {
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

            val agentService: AgentService = mockk()
            every { agentService.getDefaultAgentName() } returns agentName
            every { agentService.findAgentByName(agentName) } returns blockingAgent
            every { agentService.resolveAgentName(any<String>()) } returns null
            coEvery { agentService.cleanup() } returns Unit

            val savedEvents = mutableListOf<CaseEvent>()
            val runtime =
                CaseRuntime(
                    id = UUID.randomUUID(),
                    projectId = projectId,
                    agentService = agentService,
                    updateStatus = { _, _ -> },
                    emitAndStoreEvent = { event, caseRuntime ->
                        savedEvents.add(event)
                        caseRuntime.emitEventFromThisCase(event)
                    },
                )

            runtime.addUserMessage(userActor, userMessage)

            savedEvents.filterIsInstance<AgentRunningEvent>().shouldHaveAtLeastSize(1)
            verify(exactly = 1) { agentService.findAgentByName(agentName) }
        }

        "processNextStep emits AgentRunningEvent before findAgentByName is ever called" {
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

            val agentService: AgentService = mockk()
            every { agentService.getDefaultAgentName() } returns agentName
            every { agentService.findAgentByName(agentName) } answers {
                callOrder.add("findAgentByName")
                orderedAgent
            }
            every { agentService.resolveAgentName(any<String>()) } returns null
            coEvery { agentService.cleanup() } returns Unit

            val runtime =
                CaseRuntime(
                    id = UUID.randomUUID(),
                    projectId = projectId,
                    agentService = agentService,
                    updateStatus = { _, _ -> },
                    emitAndStoreEvent = { event, caseRuntime ->
                        if (event is AgentRunningEvent) callOrder.add("AgentRunningEvent saved")
                        caseRuntime.emitEventFromThisCase(event)
                    },
                )

            runtime.addUserMessage(userActor, userMessage)

            val runningIdx = callOrder.indexOf("AgentRunningEvent saved")
            val findIdx = callOrder.indexOf("findAgentByName")
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

            val agentService: AgentService = mockk()
            every { agentService.findAgentByName(agentName) } returns agent
            every { agentService.resolveAgentName(any<String>()) } returns null
            every { agentService.getDefaultAgentName() } returns agentName
            coEvery { agentService.cleanup() } returns Unit

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
                    agentService = agentService,
                    updateStatus = { _, _ -> },
                    emitAndStoreEvent = { event, caseRuntime ->
                        savedEvents.add(event)
                        caseRuntime.emitEventFromThisCase(event)
                    },
                )
            runtime.pushEvents(listOf(existingUserMessage, existingRunningEvent))

            runtime.addUserMessage(userActor, userMessage)

            verify(exactly = 1) { agentService.findAgentByName(agentName) }
            savedEvents.filterIsInstance<AgentSelectedEvent>() shouldBe emptyList()
        }
    })
