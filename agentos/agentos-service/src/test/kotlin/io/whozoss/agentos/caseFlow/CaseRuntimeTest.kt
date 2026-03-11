package io.whozoss.agentos.caseFlow

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
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
import java.util.UUID

/**
 * A simple recording wrapper for the runAgent callback.
 *
 * MockK's interception of suspend function-type lambdas
 * (`mockk<suspend (A, B) -> C>()`) is unreliable: the Kotlin compiler
 * mangles suspend lambdas at the JVM level, so `coEvery { fn(a, b) }` may not
 * intercept the actual call that [CaseRuntime] makes. A plain recording class
 * avoids that fragility while still letting tests verify call counts and arguments.
 */
class RecordingRunAgent(
    private val delegate: suspend (String, List<CaseEvent>) -> Unit,
) {
    private val _calls = mutableListOf<Pair<String, List<CaseEvent>>>()
    val calls: List<Pair<String, List<CaseEvent>>> get() = _calls
    val callCount: Int get() = _calls.size

    /** Expose as the function type [CaseRuntime] expects. */
    val asCallback: suspend (String, List<CaseEvent>) -> Unit = { name, events ->
        _calls += name to events
        delegate(name, events)
    }
}

/**
 * A simple recording wrapper for the selectAgent callback, avoiding MockK entirely.
 * MockK global state can bleed between specs when stubs from one spec are still
 * registered when the next spec runs. A plain wrapper has no such risk.
 */
class RecordingSelectAgent(
    private val delegate: (List<MessageContent>) -> List<CaseEvent>,
) {
    private val _calls = mutableListOf<List<MessageContent>>()
    val callCount: Int get() = _calls.size

    val asCallback: (List<MessageContent>) -> List<CaseEvent> = { content ->
        _calls += content
        delegate(content)
    }
}

class CaseRuntimeTest :
    StringSpec({
        timeout = 5000

        val projectId: UUID = UUID.randomUUID()
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

        /** Convenience: build an [AgentSelectedEvent] the way [CaseServiceImpl.selectAgent] would. */
        fun agentSelectedEvent(
            caseId: UUID,
            agentName: String,
        ) = AgentSelectedEvent(
            projectId = projectId,
            caseId = caseId,
            agentId = UUID.nameUUIDFromBytes(agentName.toByteArray()),
            agentName = agentName,
        )

        data class TestFixture(
            val runtime: CaseRuntime,
            val selectAgent: RecordingSelectAgent,
            val runAgent: RecordingRunAgent,
            val savedEvents: MutableList<CaseEvent>,
        )

        /**
         * Build a [CaseRuntime] with controlled callbacks.
         *
         * Both [selectAgent] and [runAgent] are plain recording wrappers rather than
         * MockK mocks. MockK's global stub registry can bleed between specs when
         * multiple specs run in the same JVM: stubs registered in one spec may still
         * be active when the next spec starts, causing false mismatches. Plain
         * wrappers carry no such risk.
         *
         * - [storeEvent] records each event and returns it unchanged (no real persistence).
         * - [runAgent] mirrors what [CaseServiceImpl] does: drives the agent flow and
         *   feeds each produced event back through pushEvents so the loop can detect
         *   [AgentFinishedEvent] and stop.
         */
        fun buildRuntime(
            agentName: String = "default-agent",
            agent: Agent = finishingAgent(agentName),
        ): TestFixture {
            val savedEvents = mutableListOf<CaseEvent>()
            val runtimeId = UUID.randomUUID()

            val selectAgent = RecordingSelectAgent { listOf(agentSelectedEvent(runtimeId, agentName)) }

            lateinit var runtime: CaseRuntime
            val runAgent =
                RecordingRunAgent { _, events ->
                    agent.run(events).collect { event ->
                        savedEvents.add(event)
                        runtime.emitEventFromOtherCase(event)
                        runtime.pushEvents(listOf(event))
                    }
                }

            runtime =
                CaseRuntime(
                    id = runtimeId,
                    projectId = projectId,
                    updateStatus = { _, _ -> },
                    storeEvent = { event ->
                        savedEvents.add(event)
                        event
                    },
                    selectAgent = selectAgent.asCallback,
                    runAgent = runAgent.asCallback,
                )

            return TestFixture(runtime, selectAgent, runAgent, savedEvents)
        }

        // -------------------------------------------------------------------------
        // Core regression: runAgent called exactly once per run
        // -------------------------------------------------------------------------

        "runAgent is called exactly once when using the default agent" {
            val (runtime, _, runAgent) = buildRuntime()

            runtime.addUserMessage(userActor, userMessage)
            runtime.run()

            runAgent.callCount shouldBe 1
        }

        "selectAgent is called exactly once per user message" {
            val (runtime, selectAgent) = buildRuntime()

            runtime.addUserMessage(userActor, userMessage)

            selectAgent.callCount shouldBe 1
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

            lateinit var runtime: CaseRuntime
            runtime =
                CaseRuntime(
                    id = runtimeId,
                    projectId = projectId,
                    updateStatus = { _, _ -> },
                    storeEvent = { event ->
                        savedEvents.add(event)
                        event
                    },
                    selectAgent = {
                        listOf(
                            WarnEvent(projectId = projectId, caseId = runtimeId, message = "Agent 'unknown' not found"),
                            agentSelectedEvent(runtimeId, agentName),
                        )
                    },
                    runAgent = { _, events ->
                        agent.run(events).collect { event ->
                            savedEvents.add(event)
                            runtime.pushEvents(listOf(event))
                        }
                    },
                )

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
                                    projectId = projectId,
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
                    projectId = projectId,
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

            lateinit var runtime: CaseRuntime
            val recorder =
                RecordingRunAgent { _, events ->
                    agent.run(events).collect { event ->
                        savedEvents.add(event)
                        runtime.pushEvents(listOf(event))
                    }
                }

            runtime =
                CaseRuntime(
                    id = caseId,
                    projectId = projectId,
                    updateStatus = { _, _ -> },
                    storeEvent = { event ->
                        savedEvents.add(event)
                        event
                    },
                    selectAgent = { listOf(agentSelectedEvent(caseId, agentName)) },
                    runAgent = recorder.asCallback,
                )
            runtime.pushEvents(listOf(existingUserMessage, existingRunningEvent))

            // Resume directly — no new message, no agent selection.
            runtime.run()

            recorder.callCount shouldBe 1
            savedEvents.filterIsInstance<AgentSelectedEvent>() shouldBe emptyList()
        }
    })
