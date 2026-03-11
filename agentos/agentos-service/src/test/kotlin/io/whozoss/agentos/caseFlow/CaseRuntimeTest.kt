package io.whozoss.agentos.caseFlow

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.agent.Agent
import io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent
import io.whozoss.agentos.sdk.caseEvent.AgentRunningEvent
import io.whozoss.agentos.sdk.caseEvent.AgentSelectedEvent
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.WarnEvent
import io.whozoss.agentos.sdk.entity.EntityMetadata
import kotlinx.coroutines.flow.flow
import java.util.*

class CaseRuntimeTest :
    StringSpec({
        timeout = 5000

        val namespaceId: UUID = UUID.randomUUID()
        val userActor = Actor(id = "user-123", displayName = "Test User", role = ActorRole.USER)
        val userMessage = listOf(MessageContent.Text("hello"))

        /** Build a mock Agent whose run() immediately emits AgentFinishedEvent. */
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
                                namespaceId = namespaceId,
                                caseId = caseId,
                                agentId = agentId,
                                agentName = name,
                            ),
                        )
                    }
                }
            }
        }

        /** Convenience: build an [AgentSelectedEvent] the way [CaseServiceImpl.selectAgent] would. */
        fun agentSelectedEvent(
            caseId: UUID,
            agentName: String,
        ) = AgentSelectedEvent(
            namespaceId = namespaceId,
            caseId = caseId,
            agentId = UUID.nameUUIDFromBytes(agentName.toByteArray()),
            agentName = agentName,
        )

        /**
         * Build a [CaseRuntime] with controlled callbacks.
         *
         * - [storeEvent] records each event and returns it unchanged (no real persistence).
         *   The runtime adds it to its list and emits it on the SSE flow as normal.
         * - [runAgent] mirrors what [CaseServiceImpl] does: drives the agent flow and feeds each
         *   produced event back through the same [storeEvent] path so the event list is
         *   updated and the loop can detect [AgentFinishedEvent] and stop.
         */
        data class TestFixture(
            val runtime: CaseRuntime,
            val selectAgent: (List<MessageContent>) -> List<CaseEvent>,
            val runAgent: suspend (String, List<CaseEvent>) -> Unit,
            val savedEvents: MutableList<CaseEvent>,
        )

        fun buildRuntime(
            agentName: String = "default-agent",
            agent: Agent = finishingAgent(agentName),
        ): TestFixture {
            val savedEvents = mutableListOf<CaseEvent>()
            val runtimeId = UUID.randomUUID()

            val selectAgentFn = mockk<(List<MessageContent>) -> List<CaseEvent>>()
            every { selectAgentFn(any()) } returns listOf(agentSelectedEvent(runtimeId, agentName))

            val runAgentFn = mockk<suspend (String, List<CaseEvent>) -> Unit>()

            val runtime =
                CaseRuntime(
                    id = runtimeId,
                    namespaceId = namespaceId,
                    updateStatus = { _, _ -> },
                    storeEvent = { event ->
                        savedEvents.add(event)
                        event // return unchanged (no real persistence in tests)
                    },
                    selectAgent = selectAgentFn,
                    runAgent = runAgentFn,
                )

            // runAgent drives the agent flow and records events via the same emitAndStoreEvent path,
            // mirroring what CaseServiceImpl.runAgent does.
            coEvery { runAgentFn(agentName, any()) } coAnswers {
                agent.run(secondArg<List<CaseEvent>>()).collect { event ->
                    val saved = event.also { savedEvents.add(it) }
                    runtime.emitEventFromOtherCase(saved) // bubble as sub-case event would
                    // Also push into the runtime's event list so processNextStep can see it.
                    runtime.pushEvents(listOf(saved))
                }
            }

            return TestFixture(runtime, selectAgentFn, runAgentFn, savedEvents)
        }

        // -------------------------------------------------------------------------
        // Core regression: runAgent called exactly once per run
        // -------------------------------------------------------------------------

        "runAgent is called exactly once when using the default agent" {
            val (runtime, _, runAgent) = buildRuntime()

            runtime.addUserMessage(userActor, userMessage)
            runtime.run()

            coVerify(exactly = 1) { runAgent(any(), any()) }
        }

        "selectAgent is called exactly once per user message" {
            val (runtime, selectAgent) = buildRuntime()

            runtime.addUserMessage(userActor, userMessage)

            verify(exactly = 1) { selectAgent(any()) }
        }

        // -------------------------------------------------------------------------
        // Event sequence
        // -------------------------------------------------------------------------

        "AgentSelectedEvent then AgentRunningEvent then AgentFinishedEvent are saved in order" {
            val (runtime, _, _, savedEvents) = buildRuntime()

            runtime.addUserMessage(userActor, userMessage)
            runtime.run()

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
            val (runtime, _, _, savedEvents) = buildRuntime(agentName = "gemini-flash")

            runtime.addUserMessage(userActor, userMessage)
            runtime.run()

            val selected = savedEvents.filterIsInstance<AgentSelectedEvent>().first()
            val running = savedEvents.filterIsInstance<AgentRunningEvent>().first()

            selected.agentName shouldBe "gemini-flash"
            running.agentName shouldBe "gemini-flash"
            selected.agentId shouldBe running.agentId
        }

        // -------------------------------------------------------------------------
        // selectAgent returning a WarnEvent + AgentSelectedEvent
        // -------------------------------------------------------------------------

        "WarnEvent followed by AgentSelectedEvent are both stored when selectAgent returns both" {
            val agentName = "default-agent"
            val agent = finishingAgent(agentName)
            val savedEvents = mutableListOf<CaseEvent>()
            val runtimeId = UUID.randomUUID()

            val runAgentFn = mockk<suspend (String, List<CaseEvent>) -> Unit>()
            val runtime =
                CaseRuntime(
                    id = runtimeId,
                    namespaceId = namespaceId,
                    updateStatus = { _, _ -> },
                    storeEvent = { event ->
                        savedEvents.add(event)
                        event
                    },
                    selectAgent = {
                        listOf(
                            WarnEvent(namespaceId = namespaceId, caseId = runtimeId, message = "Agent 'unknown' not found"),
                            agentSelectedEvent(runtimeId, agentName),
                        )
                    },
                    runAgent = runAgentFn,
                )
            coEvery { runAgentFn(agentName, any()) } coAnswers {
                agent.run(secondArg<List<CaseEvent>>()).collect { event ->
                    savedEvents.add(event)
                    runtime.pushEvents(listOf(event))
                }
            }

            runtime.addUserMessage(userActor, listOf(MessageContent.Text("@unknown hello")))
            runtime.run()

            val warn = savedEvents.filterIsInstance<WarnEvent>().firstOrNull()
            warn.shouldNotBeNull()
            warn.message shouldBe "Agent 'unknown' not found"
            savedEvents.filterIsInstance<AgentSelectedEvent>().first().agentName shouldBe agentName
        }

        // -------------------------------------------------------------------------
        // processNextStep: AgentSelectedEvent -> AgentRunningEvent ordering
        // -------------------------------------------------------------------------

        "processNextStep emits AgentRunningEvent before runAgent is ever called" {
            val agentName = "ordered-agent"
            val callOrder = mutableListOf<String>()
            val agentId = UUID.nameUUIDFromBytes(agentName.toByteArray())
            val runtimeId = UUID.randomUUID()

            val orderedAgent: Agent =
                mockk {
                    every { metadata } returns EntityMetadata(id = agentId)
                    every { name } returns agentName
                    every { run(any<List<CaseEvent>>()) } answers {
                        callOrder.add("agent.run")
                        flow {
                            emit(
                                AgentFinishedEvent(
                                    namespaceId = namespaceId,
                                    caseId = firstArg<List<CaseEvent>>().first().caseId,
                                    agentId = agentId,
                                    agentName = agentName,
                                ),
                            )
                        }
                    }
                }

            lateinit var runtime: CaseRuntime
            runtime =
                CaseRuntime(
                    id = runtimeId,
                    namespaceId = namespaceId,
                    updateStatus = { _, _ -> },
                    storeEvent = { event ->
                        if (event is AgentRunningEvent) callOrder.add("AgentRunningEvent saved")
                        event
                    },
                    selectAgent = { listOf(agentSelectedEvent(runtimeId, agentName)) },
                    runAgent = { _, events ->
                        callOrder.add("runAgent")
                        orderedAgent.run(events).collect { event ->
                            runtime.pushEvents(listOf(event))
                        }
                    },
                )

            runtime.addUserMessage(userActor, userMessage)
            runtime.run()

            val runningIdx = callOrder.indexOf("AgentRunningEvent saved")
            val runIdx = callOrder.indexOf("runAgent")

            (runningIdx >= 0) shouldBe true
            (runIdx > runningIdx) shouldBe true
        }

        // -------------------------------------------------------------------------
        // AgentRunningEvent already in history (case resumed mid-run)
        // -------------------------------------------------------------------------

        "runAgent is called exactly once when AgentRunningEvent is already in the event list" {
            val agentName = "gemini-flash"
            val caseId = UUID.randomUUID()
            val agentId = UUID.nameUUIDFromBytes(agentName.toByteArray())
            val agent = finishingAgent(agentName)

            val savedEvents = mutableListOf<CaseEvent>()
            val runAgentFn = mockk<suspend (String, List<CaseEvent>) -> Unit>()

            val existingUserMessage =
                MessageEvent(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = namespaceId,
                    caseId = caseId,
                    actor = userActor,
                    content = userMessage,
                )
            val existingRunningEvent =
                AgentRunningEvent(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = namespaceId,
                    caseId = caseId,
                    agentId = agentId,
                    agentName = agentName,
                )

            val runtime =
                CaseRuntime(
                    id = caseId,
                    namespaceId = namespaceId,
                    updateStatus = { _, _ -> },
                    storeEvent = { event ->
                        savedEvents.add(event)
                        event
                    },
                    selectAgent = { listOf(agentSelectedEvent(caseId, agentName)) },
                    runAgent = runAgentFn,
                )
            coEvery { runAgentFn(agentName, any()) } coAnswers {
                agent.run(secondArg<List<CaseEvent>>()).collect { event ->
                    savedEvents.add(event)
                    runtime.pushEvents(listOf(event))
                }
            }
            runtime.pushEvents(listOf(existingUserMessage, existingRunningEvent))

            // Resume directly — no new message, no agent selection.
            runtime.run()

            coVerify(exactly = 1) { runAgentFn(agentName, any()) }
            savedEvents.filterIsInstance<AgentSelectedEvent>() shouldBe emptyList()
        }
    })
