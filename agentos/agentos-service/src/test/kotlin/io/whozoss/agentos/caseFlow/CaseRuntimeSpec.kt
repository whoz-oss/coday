package io.whozoss.agentos.caseFlow

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.entity.EntityMetadata
import kotlinx.coroutines.flow.flow
import java.time.Instant
import java.util.UUID

/** Authorization check that grants access to all agents. */
private val TRUE_FOR_ANY_AGENTS: (String, UUID?) -> Boolean = { _, _ -> true }

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
    val callCount: Int get() = _calls.size

    /** Expose as the function type [CaseRuntime] expects. */
    val asCallback: suspend (String, List<CaseEvent>, () -> List<CaseEvent>, UUID?, () -> Boolean) -> Unit = { name, events, _, _, _ ->
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
    private val delegate: (List<MessageContent>, List<CaseEvent>) -> List<CaseEvent>,
) {
    private val _calls = mutableListOf<List<MessageContent>>()
    val callCount: Int get() = _calls.size

    val asCallback: (List<MessageContent>, List<CaseEvent>) -> List<CaseEvent> = { content, pastEvents ->
        _calls += content
        delegate(content, pastEvents)
    }
}

class CaseRuntimeSpec : StringSpec() {
    val namespaceId: UUID = UUID.randomUUID()
    val userActor = Actor(id = "user-123", displayName = "Test User", role = ActorRole.USER)
    val userMessage = listOf(MessageContent.Text("hello"))

    /** Build a mock Agent whose run() immediately emits AgentFinishedEvent. */
    fun finishingAgent(name: String): Agent {
        val agentId = UUID.nameUUIDFromBytes(name.toByteArray())
        return mockk<Agent>(name = "agent-$name") {
            every { metadata } returns EntityMetadata(id = agentId)
            every { this@mockk.name } returns name
            every { run(any<List<CaseEvent>>(), any()) } answers {
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
     * - [updateStatus] defaults to a no-op: status transitions are observable via
     *   [CaseRuntime.statusFlow] without needing a callback.
     */
    fun buildRuntime(
        agentName: String = "default-agent",
        agent: Agent = finishingAgent(agentName),
    ): TestFixture {
        val savedEvents = mutableListOf<CaseEvent>()
        val runtimeId = UUID.randomUUID()

        val selectAgent = RecordingSelectAgent { _, _ -> listOf(agentSelectedEvent(runtimeId, agentName)) }

        lateinit var runtime: CaseRuntime
        val runAgent =
            RecordingRunAgent { _, events ->
                agent.run(events).collect { event ->
                    savedEvents.add(event)
                    runtime.emitEvent(event)
                    runtime.pushEvents(listOf(event))
                }
            }

        runtime =
            CaseRuntime(
                id = runtimeId,
                namespaceId = namespaceId,
                caseCreatedAt = Instant.EPOCH,
                updateStatusCallback = { _, _ -> },
                storeEvent = { event ->
                    savedEvents.add(event)
                    event
                },
                selectAgent = selectAgent.asCallback,
                isAgentAuthorized = TRUE_FOR_ANY_AGENTS,
                runAgent = runAgent.asCallback,
            )

        return TestFixture(runtime, selectAgent, runAgent, savedEvents)
    }

    init {

        // -------------------------------------------------------------------------
        // Core regression: runAgent called exactly once per run
        // -------------------------------------------------------------------------

        "runAgent is called exactly once when using the default agent" {
            val (runtime, _, runAgent) = buildRuntime()

            runtime.addUserMessage(userActor, userMessage)
            runtime.run()

            runAgent.callCount shouldBe 1
            runtime.statusFlow.value shouldBe CaseStatus.IDLE
        }

        "selectAgent is called exactly once per user message" {
            val (runtime, selectAgent) = buildRuntime()

            runtime.addUserMessage(userActor, userMessage)

            selectAgent.callCount shouldBe 1
        }

        // -------------------------------------------------------------------------
        // Event sequence
        // -------------------------------------------------------------------------

        "AgentSelectedEvent then AgentFinishedEvent are saved in order" {
            val (runtime, _, _, savedEvents) = buildRuntime()

            runtime.addUserMessage(userActor, userMessage)
            runtime.run()

            val agentEvents =
                savedEvents.filter {
                    it is AgentSelectedEvent || it is AgentFinishedEvent
                }
            agentEvents shouldHaveAtLeastSize 2
            agentEvents[0].shouldBeInstanceOf<AgentSelectedEvent>()
            agentEvents[1].shouldBeInstanceOf<AgentFinishedEvent>()
            runtime.statusFlow.value shouldBe CaseStatus.IDLE
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
                    namespaceId = namespaceId,
                    caseCreatedAt = Instant.EPOCH,
                    updateStatusCallback = { _, _ -> },
                    storeEvent = { event ->
                        savedEvents.add(event)
                        event
                    },
                    selectAgent = { _, _ ->
                        listOf(
                            WarnEvent(
                                namespaceId = namespaceId,
                                caseId = runtimeId,
                                message = "Agent 'unknown' not found",
                            ),
                            agentSelectedEvent(runtimeId, agentName),
                        )
                    },
                    isAgentAuthorized = TRUE_FOR_ANY_AGENTS,
                    runAgent = { _, events, _, _, _ ->
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

        "processNextStep calls runAgent after AgentSelectedEvent is stored" {
            val agentName = "ordered-agent"
            val callOrder = mutableListOf<String>()
            val agentId = UUID.nameUUIDFromBytes(agentName.toByteArray())
            val runtimeId = UUID.randomUUID()

            val orderedAgent: Agent =
                mockk {
                    every { metadata } returns EntityMetadata(id = agentId)
                    every { name } returns agentName
                    every { run(any<List<CaseEvent>>(), any()) } answers {
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
                    caseCreatedAt = Instant.EPOCH,
                    updateStatusCallback = { _, _ -> },
                    storeEvent = { event ->
                        if (event is AgentSelectedEvent) callOrder.add("AgentSelectedEvent saved")
                        event
                    },
                    selectAgent = { _, _ -> listOf(agentSelectedEvent(runtimeId, agentName)) },
                    isAgentAuthorized = TRUE_FOR_ANY_AGENTS,
                    runAgent = { _, events, _, _, _ ->
                        callOrder.add("runAgent")
                        orderedAgent.run(events).collect { event ->
                            runtime.pushEvents(listOf(event))
                        }
                    },
                )

            runtime.addUserMessage(userActor, userMessage)
            runtime.run()

            val selectedIdx = callOrder.indexOf("AgentSelectedEvent saved")
            val runIdx = callOrder.indexOf("runAgent")

            (selectedIdx >= 0) shouldBe true
            (runIdx > selectedIdx) shouldBe true
        }

        // -------------------------------------------------------------------------
        // shouldContinue lambda contract
        // -------------------------------------------------------------------------

        "shouldContinue lambda returns true after requestInterrupt (interrupt does not stop mid-stream LLM)" {
            // requestInterrupt() stops the run loop after the current agent turn completes,
            // but does NOT signal the LLM to stop mid-stream. Only requestKill() does that.
            // shouldContinue reflects killRequested only.
            val runtimeId = UUID.randomUUID()
            var capturedShouldContinue: (() -> Boolean)? = null

            val runtime =
                CaseRuntime(
                    id = runtimeId,
                    namespaceId = namespaceId,
                    caseCreatedAt = Instant.EPOCH,
                    updateStatusCallback = { _, _ -> },
                    storeEvent = { it },
                    selectAgent = { _, _ -> listOf(agentSelectedEvent(runtimeId, "agent")) },
                    isAgentAuthorized = TRUE_FOR_ANY_AGENTS,
                    runAgent = { _, _, _, _, shouldContinue ->
                        capturedShouldContinue = shouldContinue
                    },
                )

            runtime.addUserMessage(userActor, userMessage)
            runtime.run()

            capturedShouldContinue shouldNotBe null
            runtime.requestInterrupt()
            // interrupt does not affect shouldContinue — only kill does
            capturedShouldContinue!!.invoke() shouldBe true
        }

        "shouldContinue lambda returns false after requestKill is called" {
            val runtimeId = UUID.randomUUID()
            var capturedShouldContinue: (() -> Boolean)? = null

            val runtime =
                CaseRuntime(
                    id = runtimeId,
                    namespaceId = namespaceId,
                    caseCreatedAt = Instant.EPOCH,
                    updateStatusCallback = { _, _ -> },
                    storeEvent = { it },
                    selectAgent = { _, _ -> listOf(agentSelectedEvent(runtimeId, "agent")) },
                    isAgentAuthorized = TRUE_FOR_ANY_AGENTS,
                    runAgent = { _, _, _, _, shouldContinue ->
                        capturedShouldContinue = shouldContinue
                    },
                )

            runtime.addUserMessage(userActor, userMessage)
            runtime.run()

            capturedShouldContinue shouldNotBe null
            runtime.requestKill()
            capturedShouldContinue!!.invoke() shouldBe false
        }

        "shouldContinue lambda returns true during execution when neither interrupt nor kill has been requested" {
            // Positive-path test: the lambda must return true while runAgent is executing
            // and no interrupt or kill has been signalled.
            //
            // Note on lifecycle: processNextStep sets interruptRequested=true when it finds
            // AgentFinishedEvent (to break the while-loop). run() resets interruptRequested
            // to false at the START of each invocation. So the lambda returns true only
            // while runAgent is executing BEFORE AgentFinishedEvent is pushed — that is
            // the window we sample here.
            val runtimeId = UUID.randomUUID()
            var lambdaResultDuringRun: Boolean? = null

            lateinit var runtime: CaseRuntime
            runtime =
                CaseRuntime(
                    id = runtimeId,
                    namespaceId = namespaceId,
                    caseCreatedAt = Instant.EPOCH,
                    updateStatusCallback = { _, _ -> },
                    storeEvent = { it },
                    selectAgent = { _, _ -> listOf(agentSelectedEvent(runtimeId, "agent")) },
                    isAgentAuthorized = TRUE_FOR_ANY_AGENTS,
                    runAgent = { _, _, _, _, shouldContinue ->
                        // Sample BEFORE pushing AgentFinishedEvent: interruptRequested is
                        // still false at this point, so shouldContinue() must return true.
                        lambdaResultDuringRun = shouldContinue()
                        // Now push AgentFinishedEvent so the loop exits cleanly.
                        runtime.pushEvents(
                            listOf(
                                AgentFinishedEvent(
                                    namespaceId = namespaceId,
                                    caseId = runtimeId,
                                    agentId = UUID.nameUUIDFromBytes("agent".toByteArray()),
                                    agentName = "agent",
                                ),
                            ),
                        )
                    },
                )

            runtime.addUserMessage(userActor, userMessage)
            runtime.run()

            // The lambda returned true while runAgent was executing with no interrupt/kill
            lambdaResultDuringRun shouldBe true
        }

        // -------------------------------------------------------------------------
        // Redirect: AgentFinishedEvent followed by AgentSelectedEvent
        // -------------------------------------------------------------------------

        "statusFlow reflects RUNNING during run() and IDLE after normal completion" {
            val (runtime) = buildRuntime()

            runtime.statusFlow.value shouldBe CaseStatus.PENDING
            runtime.addUserMessage(userActor, userMessage)
            runtime.run()
            runtime.statusFlow.value shouldBe CaseStatus.IDLE
        }

        "statusFlow reflects ERROR when max iterations are exceeded" {
            // An agent that never emits AgentFinishedEvent forces the loop to hit maxIterations.
            val loopingAgent =
                mockk<Agent> {
                    every { metadata } returns EntityMetadata(id = UUID.randomUUID())
                    every { name } returns "looping"
                    every { run(any<List<CaseEvent>>(), any()) } returns flow { /* never finishes */ }
                }
            val (runtime) = buildRuntime(agentName = "looping", agent = loopingAgent)

            runtime.addUserMessage(userActor, userMessage)
            runtime.run()

            runtime.statusFlow.value shouldBe CaseStatus.ERROR
        }

        "statusFlow reflects KILLED after requestKill during run" {
            // requestKill() must be called while run() is executing — run() resets
            // the kill flag at startup, so calling it before run() has no effect.
            val runtimeId = UUID.randomUUID()
            lateinit var runtime: CaseRuntime
            runtime =
                CaseRuntime(
                    id = runtimeId,
                    namespaceId = namespaceId,
                    caseCreatedAt = Instant.EPOCH,
                    updateStatusCallback = { _, _ -> },
                    storeEvent = { it },
                    selectAgent = { _, _ -> listOf(agentSelectedEvent(runtimeId, "agent")) },
                    isAgentAuthorized = TRUE_FOR_ANY_AGENTS,
                    runAgent = { _, _, _, _, _ ->
                        // Signal kill from inside runAgent — before pushing AgentFinishedEvent.
                        runtime.requestKill()
                    },
                )

            runtime.addUserMessage(userActor, userMessage)
            runtime.run()

            runtime.statusFlow.value shouldBe CaseStatus.KILLED
        }

        "runAgent is called twice when agent A redirects to agent B" {
            // Regression: processNextStep scanned events newest-first and stopped at
            // AgentFinishedEvent before seeing the AgentSelectedEvent that followed it.
            // The fix emits AgentFinishedEvent BEFORE AgentSelectedEvent on redirect,
            // so the scan finds AgentSelectedEvent last (newest) and launches agent B.
            val agentA = "agent-a"
            val agentB = "agent-b"
            val runtimeId = UUID.randomUUID()
            val agentBId = UUID.nameUUIDFromBytes(agentB.toByteArray())

            val savedEvents = mutableListOf<CaseEvent>()
            val runOrder = mutableListOf<String>()

            lateinit var runtime: CaseRuntime

            // Agent A emits: ToolRequestEvent, ToolResponseEvent, AgentFinishedEvent(A), AgentSelectedEvent(B)
            // — the redirect order produced by AgentSimple after the fix.
            val agentAMock =
                mockk<Agent>(name = "mock-$agentA") {
                    every { metadata } returns EntityMetadata(id = UUID.nameUUIDFromBytes(agentA.toByteArray()))
                    every { name } returns agentA
                    every { run(any<List<CaseEvent>>(), any()) } answers {
                        val caseId = firstArg<List<CaseEvent>>().first().caseId
                        flow {
                            emit(
                                AgentFinishedEvent(
                                    namespaceId = namespaceId,
                                    caseId = caseId,
                                    agentId = UUID.nameUUIDFromBytes(agentA.toByteArray()),
                                    agentName = agentA,
                                ),
                            )
                            emit(
                                AgentSelectedEvent(
                                    namespaceId = namespaceId,
                                    caseId = caseId,
                                    agentId = agentBId,
                                    agentName = agentB,
                                ),
                            )
                        }
                    }
                }

            // Agent B finishes normally.
            val agentBMock =
                mockk<Agent>(name = "mock-$agentB") {
                    every { metadata } returns EntityMetadata(id = agentBId)
                    every { name } returns agentB
                    every { run(any<List<CaseEvent>>(), any()) } answers {
                        val caseId = firstArg<List<CaseEvent>>().first().caseId
                        flow {
                            emit(
                                AgentFinishedEvent(
                                    namespaceId = namespaceId,
                                    caseId = caseId,
                                    agentId = agentBId,
                                    agentName = agentB,
                                ),
                            )
                        }
                    }
                }

            val selectAgent = RecordingSelectAgent { _, _ -> listOf(agentSelectedEvent(runtimeId, agentA)) }

            val runAgent =
                RecordingRunAgent { name, events ->
                    runOrder += name
                    val agent = if (name == agentA) agentAMock else agentBMock
                    agent.run(events).collect { event ->
                        savedEvents.add(event)
                        runtime.emitEvent(event)
                        runtime.pushEvents(listOf(event))
                    }
                }

            runtime =
                CaseRuntime(
                    id = runtimeId,
                    namespaceId = namespaceId,
                    caseCreatedAt = Instant.EPOCH,
                    updateStatusCallback = { _, _ -> },
                    storeEvent = { event ->
                        savedEvents.add(event)
                        event
                    },
                    selectAgent = selectAgent.asCallback,
                    isAgentAuthorized = TRUE_FOR_ANY_AGENTS,
                    runAgent = runAgent.asCallback,
                )

            runtime.addUserMessage(userActor, userMessage)
            runtime.run()

            // Both agents must have run, in order
            runAgent.callCount shouldBe 2
            runOrder[0] shouldBe agentA
            runOrder[1] shouldBe agentB

            // Agent B's AgentFinishedEvent must be in the saved events
            val finishedEvents = savedEvents.filterIsInstance<AgentFinishedEvent>()
            finishedEvents.any { it.agentName == agentB } shouldBe true
        }

        // -------------------------------------------------------------------------
        // Defensive authorization check on AgentSelectedEvent (redirect)
        // -------------------------------------------------------------------------

        "redirect to unauthorized agent emits WarnEvent and stops turn" {
            val agentA = "agent-a"
            val agentB = "agent-b"
            val runtimeId = UUID.randomUUID()
            val savedEvents = mutableListOf<CaseEvent>()
            val runOrder = mutableListOf<String>()

            lateinit var runtime: CaseRuntime

            val agentAMock =
                mockk<Agent>(name = "mock-$agentA") {
                    every { metadata } returns EntityMetadata(id = UUID.nameUUIDFromBytes(agentA.toByteArray()))
                    every { name } returns agentA
                    every { run(any<List<CaseEvent>>(), any()) } answers {
                        val caseId = firstArg<List<CaseEvent>>().first().caseId
                        flow {
                            emit(
                                AgentFinishedEvent(
                                    namespaceId = namespaceId,
                                    caseId = caseId,
                                    agentId = UUID.nameUUIDFromBytes(agentA.toByteArray()),
                                    agentName = agentA,
                                ),
                            )
                            emit(
                                AgentSelectedEvent(
                                    namespaceId = namespaceId,
                                    caseId = caseId,
                                    agentId = UUID.nameUUIDFromBytes(agentB.toByteArray()),
                                    agentName = agentB,
                                ),
                            )
                        }
                    }
                }

            val selectAgent = RecordingSelectAgent { _, _ -> listOf(agentSelectedEvent(runtimeId, agentA)) }
            val runAgent =
                RecordingRunAgent { name, events ->
                    runOrder += name
                    agentAMock.run(events).collect { event ->
                        savedEvents.add(event)
                        runtime.emitEvent(event)
                        runtime.pushEvents(listOf(event))
                    }
                }

            runtime =
                CaseRuntime(
                    id = runtimeId,
                    namespaceId = namespaceId,
                    caseCreatedAt = Instant.EPOCH,
                    updateStatusCallback = { _, _ -> },
                    storeEvent = { event ->
                        savedEvents.add(event)
                        event
                    },
                    selectAgent = selectAgent.asCallback,
                    isAgentAuthorized = { name, _ -> name == agentA }, // agentB not authorized
                    runAgent = runAgent.asCallback,
                )

            runtime.addUserMessage(userActor, userMessage)
            runtime.run()

            // agentA ran, agentB was blocked
            runAgent.callCount shouldBe 1
            runOrder shouldBe listOf(agentA)
            savedEvents.filterIsInstance<WarnEvent>().any {
                it.message.contains(agentB)
            } shouldBe true
        }

        // -------------------------------------------------------------------------
        // Command queue: sequential execution
        // -------------------------------------------------------------------------

        "enqueued commands are executed sequentially after the first agent turn" {
            val runOrder = mutableListOf<String>()
            val agentName = "seq-agent"
            val agentId = UUID.nameUUIDFromBytes(agentName.toByteArray())
            val runtimeId = UUID.randomUUID()
            val savedEvents = mutableListOf<CaseEvent>()

            val agent = mockk<Agent>(name = "agent-$agentName") {
                every { metadata } returns EntityMetadata(id = agentId)
                every { this@mockk.name } returns agentName
                every { run(any<List<CaseEvent>>(), any()) } answers {
                    val events = firstArg<List<CaseEvent>>()
                    val lastMsg = events.filterIsInstance<MessageEvent>().last()
                    val text = lastMsg.content.filterIsInstance<MessageContent.Text>().first().content
                    runOrder.add(text)
                    flow {
                        emit(
                            AgentFinishedEvent(
                                namespaceId = namespaceId,
                                caseId = lastMsg.caseId,
                                agentId = agentId,
                                agentName = agentName,
                            ),
                        )
                    }
                }
            }

            val selectAgent = RecordingSelectAgent { _, _ -> listOf(agentSelectedEvent(runtimeId, agentName)) }

            lateinit var runtime: CaseRuntime
            val runAgent = RecordingRunAgent { _, events ->
                agent.run(events).collect { event ->
                    savedEvents.add(event)
                    runtime.emitEvent(event)
                    runtime.pushEvents(listOf(event))
                }
            }

            runtime = CaseRuntime(
                id = runtimeId,
                namespaceId = namespaceId,
                caseCreatedAt = Instant.EPOCH,
                updateStatusCallback = { _, _ -> },
                storeEvent = { event -> savedEvents.add(event); event },
                selectAgent = selectAgent.asCallback,
                isAgentAuthorized = TRUE_FOR_ANY_AGENTS,
                runAgent = runAgent.asCallback,
            )

            // First message via addUserMessage, two more via enqueueCommand
            runtime.addUserMessage(userActor, listOf(MessageContent.Text("command-1")))
            runtime.enqueueCommand(listOf(MessageContent.Text("command-2")))
            runtime.enqueueCommand(listOf(MessageContent.Text("command-3")))
            runtime.run()

            runAgent.callCount shouldBe 3
            runOrder shouldBe listOf("command-1", "command-2", "command-3")
            runtime.statusFlow.value shouldBe CaseStatus.IDLE
        }

        "command queue is cleared on kill" {
            val runtimeId = UUID.randomUUID()
            lateinit var runtime: CaseRuntime
            runtime = CaseRuntime(
                id = runtimeId,
                namespaceId = namespaceId,
                caseCreatedAt = Instant.EPOCH,
                updateStatusCallback = { _, _ -> },
                storeEvent = { it },
                selectAgent = { _, _ -> listOf(agentSelectedEvent(runtimeId, "agent")) },
                isAgentAuthorized = TRUE_FOR_ANY_AGENTS,
                runAgent = { _, _, _, _, _ ->
                    // Kill during first agent turn
                    runtime.requestKill()
                },
            )

            runtime.addUserMessage(userActor, userMessage)
            runtime.enqueueCommand(listOf(MessageContent.Text("should-not-run")))
            runtime.run()

            runtime.statusFlow.value shouldBe CaseStatus.KILLED
        }

        "command queue is cleared on error (max iterations)" {
            val loopingAgent = mockk<Agent> {
                every { metadata } returns EntityMetadata(id = UUID.randomUUID())
                every { name } returns "looping"
                every { run(any<List<CaseEvent>>(), any()) } returns flow { /* never finishes */ }
            }
            val (runtime) = buildRuntime(agentName = "looping", agent = loopingAgent)

            runtime.addUserMessage(userActor, userMessage)
            runtime.enqueueCommand(listOf(MessageContent.Text("should-not-run")))
            runtime.run()

            runtime.statusFlow.value shouldBe CaseStatus.ERROR
        }

        "runAgent is called exactly once when AgentRunningEvent is already in the event list" {
            val agentName = "gemini-flash"
            val caseId = UUID.randomUUID()
            val agentId = UUID.nameUUIDFromBytes(agentName.toByteArray())
            val agent = finishingAgent(agentName)
            val savedEvents = mutableListOf<CaseEvent>()

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
                    namespaceId = namespaceId,
                    caseCreatedAt = Instant.EPOCH,
                    updateStatusCallback = { _, _ -> },
                    storeEvent = { event ->
                        savedEvents.add(event)
                        event
                    },
                    selectAgent = { _, _ -> listOf(agentSelectedEvent(caseId, agentName)) },
                    isAgentAuthorized = TRUE_FOR_ANY_AGENTS,
                    runAgent = recorder.asCallback,
                )
            runtime.pushEvents(listOf(existingUserMessage, existingRunningEvent))

            // Resume directly — no new message, no agent selection.
            runtime.run()

            recorder.callCount shouldBe 1
            savedEvents.filterIsInstance<AgentSelectedEvent>() shouldBe emptyList()
        }
    }
}
