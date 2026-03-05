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
         * Convenience: build an [AgentSelectedEvent] the way [CaseServiceImpl.selectAgent] would.
         */
        fun agentSelectedEvent(
            caseId: UUID,
            agentName: String,
        ) = AgentSelectedEvent(
            projectId = projectId,
            caseId = caseId,
            agentId = UUID.nameUUIDFromBytes(agentName.toByteArray()),
            agentName = agentName,
        )

        /**
         * Build a [CaseRuntime] with controlled callbacks.
         *
         * [selectAgent] and [runAgent] are MockK lambdas so tests can use [verify]/[coVerify] on them.
         * [savedEvents] captures everything passed to [emitAndStoreEvent].
         *
         * [runAgent] mirrors what [CaseServiceImpl] does: drives the agent flow and feeds each event
         * back through [CaseRuntime.emitEventFromThisCase] so the event list is updated and the
         * loop can detect [AgentFinishedEvent] and stop.
         */
        data class TestFixture(
            val runtime: CaseRuntime,
            val selectAgent: (List<MessageContent>) -> List<CaseEvent>,
            val runAgent: suspend (String, List<CaseEvent>, CaseRuntime) -> Unit,
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

            val runAgentFn = mockk<suspend (String, List<CaseEvent>, CaseRuntime) -> Unit>()

            var runtime =
                CaseRuntime(
                    id = runtimeId,
                    projectId = projectId,
                    updateStatus = { _, _ -> },
                    emitAndStoreEvent = { event, caseRuntime ->
                        savedEvents.add(event)
                        caseRuntime.emitEventFromThisCase(event)
                    },
                    selectAgent = selectAgentFn,
                    runAgent = runAgentFn,
                )

            coEvery { runAgentFn(agentName, any(), runtime) } coAnswers {
                agent.run(secondArg<List<CaseEvent>>()).collect { event ->
                    savedEvents.add(event)
                    runtime.emitEventFromThisCase(event)
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

            coVerify(exactly = 1) { runAgent(any(), any(), runtime) }
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
            lateinit var runtime: CaseRuntime

            val runAgentFn = mockk<suspend (String, List<CaseEvent>, CaseRuntime) -> Unit>()
            runtime =
                CaseRuntime(
                    id = runtimeId,
                    projectId = projectId,
                    updateStatus = { _, _ -> },
                    emitAndStoreEvent = { event, caseRuntime ->
                        savedEvents.add(event)
                        caseRuntime.emitEventFromThisCase(event)
                    },
                    selectAgent = {
                        listOf(
                            WarnEvent(projectId = projectId, caseId = runtimeId, message = "Agent 'unknown' not found"),
                            agentSelectedEvent(runtimeId, agentName),
                        )
                    },
                    runAgent = runAgentFn,
                )
            coEvery { runAgentFn(agentName, any(), runtime) } coAnswers {
                agent.run(secondArg<List<CaseEvent>>()).collect { event ->
                    savedEvents.add(event)
                    runtime.emitEventFromThisCase(event)
                }
            }

            runtime.addUserMessage(userActor, listOf(MessageContent.Text("@unknown hello")))

            val warn = savedEvents.filterIsInstance<WarnEvent>().firstOrNull()
            warn.shouldNotBeNull()
            warn.message shouldBe "Agent 'unknown' not found"
            savedEvents.filterIsInstance<AgentSelectedEvent>().first().agentName shouldBe agentName
        }

        // -------------------------------------------------------------------------
        // processNextStep: AgentSelectedEvent -> AgentRunningEvent transition
        // -------------------------------------------------------------------------

        "processNextStep emits AgentRunningEvent before runAgent is ever called" {
            val agentName = "ordered-agent"
            val callOrder = mutableListOf<String>()
            val agentId = UUID.nameUUIDFromBytes(agentName.toByteArray())

            val orderedAgent: Agent =
                mockk {
                    every { metadata } returns EntityMetadata(id = agentId)
                    every { name } returns agentName
                    every { run(any<List<CaseEvent>>()) } answers {
                        callOrder.add("agent.run")
                        flow {
                            emit(
                                AgentFinishedEvent(
                                    projectId = projectId,
                                    caseId = firstArg<List<CaseEvent>>().first().caseId,
                                    agentId = agentId,
                                    agentName = agentName,
                                ),
                            )
                        }
                    }
                }

            val runtimeId = UUID.randomUUID()
            lateinit var runtime: CaseRuntime
            runtime =
                CaseRuntime(
                    id = runtimeId,
                    projectId = projectId,
                    updateStatus = { _, _ -> },
                    emitAndStoreEvent = { event, caseRuntime ->
                        if (event is AgentRunningEvent) callOrder.add("AgentRunningEvent saved")
                        caseRuntime.emitEventFromThisCase(event)
                    },
                    selectAgent = { listOf(agentSelectedEvent(runtimeId, agentName)) },
                    runAgent = { name, events, caseRuntime ->
                        callOrder.add("runAgent")
                        orderedAgent.run(events).collect { event ->
                            runtime.emitEventFromThisCase(event)
                        }
                    },
                )

            runtime.addUserMessage(userActor, userMessage)

            val runningIdx = callOrder.indexOf("AgentRunningEvent saved")
            val runIdx = callOrder.indexOf("runAgent")

            (runningIdx >= 0) shouldBe true
            (runIdx > runningIdx) shouldBe true
        }

        // -------------------------------------------------------------------------
        // AgentRunningEvent already in history (case resumed mid-run)
        // -------------------------------------------------------------------------

        "runAgent is called exactly once when AgentRunningEvent is already in the event list" {
            // Simulates a case being resumed mid-run: history already contains a MessageEvent
            // and an AgentRunningEvent. Calling run() directly (not addUserMessage) skips
            // agent selection and goes straight to executing the in-progress agent.
            val agentName = "gemini-flash"
            val caseId = UUID.randomUUID()
            val agentId = UUID.nameUUIDFromBytes(agentName.toByteArray())
            val agent = finishingAgent(agentName)

            val savedEvents = mutableListOf<CaseEvent>()
            lateinit var runtime: CaseRuntime
            val runAgentFn = mockk<suspend (String, List<CaseEvent>, CaseRuntime) -> Unit>()

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

            runtime =
                CaseRuntime(
                    id = caseId,
                    projectId = projectId,
                    updateStatus = { _, _ -> },
                    emitAndStoreEvent = { event, caseRuntime ->
                        savedEvents.add(event)
                        caseRuntime.emitEventFromThisCase(event)
                    },
                    selectAgent = { listOf(agentSelectedEvent(caseId, agentName)) },
                    runAgent = runAgentFn,
                )
            coEvery { runAgentFn(agentName, any(), runtime) } coAnswers {
                agent.run(secondArg<List<CaseEvent>>()).collect { event ->
                    savedEvents.add(event)
                    runtime.emitEventFromThisCase(event)
                }
            }
            runtime.pushEvents(listOf(existingUserMessage, existingRunningEvent))

            // Resume directly — no new message, no agent selection.
            runtime.run()

            coVerify(exactly = 1) { runAgentFn(agentName, any(), runtime) }
            savedEvents.filterIsInstance<AgentSelectedEvent>() shouldBe emptyList()
        }
    })
