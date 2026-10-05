package io.whozoss.agentos.caseFlow

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.agent.Agent
import io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent
import io.whozoss.agentos.sdk.caseEvent.AgentRunningEvent
import io.whozoss.agentos.sdk.caseEvent.AgentSelectedEvent
import io.whozoss.agentos.sdk.caseEvent.AnswerEvent
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.QuestionEvent
import io.whozoss.agentos.sdk.caseEvent.QuestionType
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

        // -------------------------------------------------------------------------
        // Pre-flight: findUnresolvedQuestion — tested via observable runtime behaviour
        // -------------------------------------------------------------------------

        "pre-flight emits AgentSelectedEvent when a question has been answered and no AgentFinishedEvent follows" {
            // Simulate the AwaitAnswer path: QuestionEvent was emitted, agent finished,
            // THEN the user answered. No AgentFinishedEvent after the answer.
            // Expected: pre-flight finds the question and emits AgentSelectedEvent for
            // the original agent, causing runAgent to be called.
            //
            // IMPORTANT: timestamps must be explicit and strictly increasing.
            // InMemoryCaseEventList re-sorts all events by timestamp on insertion, so
            // the order in which events appear in the source list is irrelevant — only
            // the timestamp determines their position. Without explicit timestamps every
            // event gets Instant.now() and the sort order is undefined, which breaks
            // findUnresolvedQuestion's subList-based OAuth guard.
            val agentName = "qa-agent"
            val agentId = UUID.nameUUIDFromBytes(agentName.toByteArray())
            val caseId = UUID.randomUUID()
            val savedEvents = mutableListOf<CaseEvent>()
            val runCalls = mutableListOf<String>()

            // Chronological order: MessageEvent(t1) -> AgentSelectedEvent(t2) ->
            //   AgentFinishedEvent(t3) -> QuestionEvent(t4) -> AnswerEvent(t5).
            // No AgentFinishedEvent after AnswerEvent → pre-flight must fire.
            val t1 = Instant.EPOCH.plusSeconds(1)
            val t2 = Instant.EPOCH.plusSeconds(2)
            val t3 = Instant.EPOCH.plusSeconds(3)
            val t4 = Instant.EPOCH.plusSeconds(4)
            val t5 = Instant.EPOCH.plusSeconds(5)

            val questionEvent = QuestionEvent(
                timestamp = t4,
                namespaceId = namespaceId,
                caseId = caseId,
                agentId = agentId,
                agentName = agentName,
                question = "What is your choice?",
            )
            // createAnswer() defaults timestamp to Instant.now(); override it to t5
            // so the sort order is deterministic and AnswerEvent lands after QuestionEvent.
            val answerEvent = questionEvent.createAnswer(
                Actor("user-1", "User", ActorRole.USER),
                "My answer",
            ).copy(timestamp = t5)

            val existingEvents = listOf(
                MessageEvent(
                    timestamp = t1,
                    namespaceId = namespaceId,
                    caseId = caseId,
                    actor = userActor,
                    content = userMessage,
                ),
                AgentSelectedEvent(
                    timestamp = t2,
                    namespaceId = namespaceId,
                    caseId = caseId,
                    agentId = agentId,
                    agentName = agentName,
                ),
                AgentFinishedEvent(
                    timestamp = t3,
                    namespaceId = namespaceId,
                    caseId = caseId,
                    agentId = agentId,
                    agentName = agentName,
                ),
                questionEvent,
                answerEvent,
            )

            lateinit var runtime: CaseRuntime
            runtime = CaseRuntime(
                id = caseId,
                namespaceId = namespaceId,
                caseCreatedAt = Instant.EPOCH,
                updateStatusCallback = { _, _ -> },
                storeEvent = { event -> savedEvents.add(event); event },
                selectAgent = { _, _ -> listOf(agentSelectedEvent(caseId, agentName)) },
                isAgentAuthorized = TRUE_FOR_ANY_AGENTS,
                runAgent = { name, _, _, _, _ ->
                    runCalls += name
                    // Push AgentFinishedEvent so the loop terminates cleanly.
                    runtime.pushEvents(
                        listOf(
                            AgentFinishedEvent(
                                namespaceId = namespaceId,
                                caseId = caseId,
                                agentId = UUID.nameUUIDFromBytes(name.toByteArray()),
                                agentName = name,
                            ),
                        ),
                    )
                },
            )
            runtime.pushEvents(existingEvents)

            // run() is called as it would be when CaseServiceImpl.addMessage triggers it
            // after the user posts an AnswerEvent.
            runtime.run()

            // runCalls proves runAgent was triggered (i.e. the AgentSelectedEvent from
            // the pre-flight was stored and picked up by processNextStep).
            runCalls shouldBe listOf(agentName)
            // savedEvents must contain the AgentSelectedEvent emitted by the pre-flight
            // (it passes through storeEvent, which appends to savedEvents).
            savedEvents.filterIsInstance<AgentSelectedEvent>()
                .any { it.agentName == agentName } shouldBe true
            runtime.statusFlow.value shouldBe CaseStatus.IDLE
        }

        "pre-flight resumes every legitimately answered outstanding question sequentially in question history order" {
            // Both answers exist before the run starts. The first question remains first
            // even when the answers arrive in reverse order; QuestionEvent history order
            // is the explicit deterministic scheduling rule.
            val caseId = UUID.randomUUID()
            val firstAgent = "first-agent"
            val secondAgent = "second-agent"
            val firstId = UUID.nameUUIDFromBytes(firstAgent.toByteArray())
            val secondId = UUID.nameUUIDFromBytes(secondAgent.toByteArray())
            val firstQuestion = QuestionEvent(
                timestamp = Instant.EPOCH.plusSeconds(1), namespaceId = namespaceId, caseId = caseId,
                agentId = firstId, agentName = firstAgent, question = "First?",
            )
            val secondQuestion = QuestionEvent(
                timestamp = Instant.EPOCH.plusSeconds(2), namespaceId = namespaceId, caseId = caseId,
                agentId = secondId, agentName = secondAgent, question = "Second?",
            )
            val respondent = Actor(UUID.randomUUID().toString(), "User", ActorRole.USER)
            val secondAnswer = secondQuestion.createAnswer(respondent, "second").copy(timestamp = Instant.EPOCH.plusSeconds(3))
            val firstAnswer = firstQuestion.createAnswer(respondent, "first").copy(timestamp = Instant.EPOCH.plusSeconds(4))
            val runOrder = mutableListOf<String>()
            val savedEvents = mutableListOf<CaseEvent>()
            var nextStoredTimestamp = Instant.EPOCH.plusSeconds(5)

            lateinit var runtime: CaseRuntime
            runtime = CaseRuntime(
                id = caseId,
                namespaceId = namespaceId,
                caseCreatedAt = Instant.EPOCH,
                updateStatusCallback = { _, _ -> },
                storeEvent = { event ->
                    val saved = if (event is AgentSelectedEvent) event.copy(timestamp = nextStoredTimestamp) else event
                    savedEvents += saved
                    nextStoredTimestamp = nextStoredTimestamp.plusSeconds(1)
                    saved
                },
                selectAgent = { _, _ -> emptyList() },
                isAgentAuthorized = TRUE_FOR_ANY_AGENTS,
                runAgent = { name, _, _, _, _ ->
                    runOrder += name
                    runtime.pushEvents(listOf(AgentFinishedEvent(
                        timestamp = nextStoredTimestamp,
                        namespaceId = namespaceId,
                        caseId = caseId,
                        agentId = if (name == firstAgent) firstId else secondId,
                        agentName = name,
                    )))
                    nextStoredTimestamp = nextStoredTimestamp.plusSeconds(1)
                },
            )
            runtime.pushEvents(listOf(firstQuestion, secondQuestion, secondAnswer, firstAnswer))

            runtime.run()

            runOrder shouldBe listOf(firstAgent, secondAgent)
            savedEvents.filterIsInstance<AgentSelectedEvent>().map { it.agentName } shouldBe
                listOf(firstAgent, secondAgent)
            runtime.statusFlow.value shouldBe CaseStatus.IDLE
        }

        "pre-flight leaves unanswered questions pending while resuming an answered one" {
            val caseId = UUID.randomUUID()
            val answeredAgent = "answered-agent"
            val waitingAgent = "waiting-agent"
            val answeredQuestion = QuestionEvent(
                timestamp = Instant.EPOCH.plusSeconds(1), namespaceId = namespaceId, caseId = caseId,
                agentId = UUID.nameUUIDFromBytes(answeredAgent.toByteArray()), agentName = answeredAgent, question = "Answered?",
            )
            val waitingQuestion = QuestionEvent(
                timestamp = Instant.EPOCH.plusSeconds(2), namespaceId = namespaceId, caseId = caseId,
                agentId = UUID.nameUUIDFromBytes(waitingAgent.toByteArray()), agentName = waitingAgent, question = "Waiting?",
            )
            val answer = answeredQuestion.createAnswer(Actor(UUID.randomUUID().toString(), "User", ActorRole.USER), "yes")
                .copy(timestamp = Instant.EPOCH.plusSeconds(3))
            val runCalls = mutableListOf<String>()
            lateinit var runtime: CaseRuntime
            runtime = CaseRuntime(
                id = caseId, namespaceId = namespaceId, caseCreatedAt = Instant.EPOCH,
                updateStatusCallback = { _, _ -> },
                storeEvent = { event -> if (event is AgentSelectedEvent) event.copy(timestamp = Instant.EPOCH.plusSeconds(4)) else event },
                selectAgent = { _, _ -> emptyList() },
                isAgentAuthorized = TRUE_FOR_ANY_AGENTS,
                runAgent = { name, _, _, _, _ ->
                    runCalls += name
                    runtime.pushEvents(listOf(AgentFinishedEvent(
                        timestamp = Instant.EPOCH.plusSeconds(5), namespaceId = namespaceId, caseId = caseId,
                        agentId = UUID.nameUUIDFromBytes(name.toByteArray()), agentName = name,
                    )))
                },
            )
            runtime.pushEvents(listOf(answeredQuestion, waitingQuestion, answer))

            runtime.run()

            runCalls shouldBe listOf(answeredAgent)
        }

        "pre-flight deduplicates two answered questions from the same agent by questionId" {
            val caseId = UUID.randomUUID()
            val agentName = "same-agent"
            val agentId = UUID.nameUUIDFromBytes(agentName.toByteArray())
            val first = QuestionEvent(timestamp = Instant.EPOCH.plusSeconds(1), namespaceId = namespaceId, caseId = caseId, agentId = agentId, agentName = agentName, question = "one")
            val second = QuestionEvent(timestamp = Instant.EPOCH.plusSeconds(2), namespaceId = namespaceId, caseId = caseId, agentId = agentId, agentName = agentName, question = "two")
            val actor = Actor(UUID.randomUUID().toString(), "User", ActorRole.USER)
            val firstAnswer = first.createAnswer(actor, "one").copy(timestamp = Instant.EPOCH.plusSeconds(3))
            val secondAnswer = second.createAnswer(actor, "two").copy(timestamp = Instant.EPOCH.plusSeconds(4))
            val calls = mutableListOf<String>()
            var timestamp = Instant.EPOCH.plusSeconds(5)
            lateinit var runtime: CaseRuntime
            runtime = CaseRuntime(
                id = caseId, namespaceId = namespaceId, caseCreatedAt = Instant.EPOCH,
                updateStatusCallback = { _, _ -> },
                storeEvent = { event ->
                    val stored = if (event is AgentSelectedEvent) event.copy(timestamp = timestamp) else event
                    timestamp = timestamp.plusSeconds(1)
                    stored
                },
                selectAgent = { _, _ -> emptyList() }, isAgentAuthorized = TRUE_FOR_ANY_AGENTS,
                runAgent = { name, _, _, _, _ ->
                    calls += name
                    runtime.pushEvents(listOf(AgentFinishedEvent(timestamp = timestamp, namespaceId = namespaceId, caseId = caseId, agentId = agentId, agentName = name)))
                    timestamp = timestamp.plusSeconds(1)
                },
            )
            runtime.pushEvents(listOf(first, second, firstAnswer, secondAnswer))
            runtime.run()

            calls shouldBe listOf(agentName, agentName)
        }

        "replay after first resumed question finished still resumes the second question exactly once" {
            val caseId = UUID.randomUUID()
            val agentOne = "agent-one"
            val agentTwo = "agent-two"
            val first = QuestionEvent(timestamp = Instant.EPOCH.plusSeconds(1), namespaceId = namespaceId, caseId = caseId, agentId = UUID.nameUUIDFromBytes(agentOne.toByteArray()), agentName = agentOne, question = "one")
            val second = QuestionEvent(timestamp = Instant.EPOCH.plusSeconds(2), namespaceId = namespaceId, caseId = caseId, agentId = UUID.nameUUIDFromBytes(agentTwo.toByteArray()), agentName = agentTwo, question = "two")
            val actor = Actor(UUID.randomUUID().toString(), "User", ActorRole.USER)
            val firstAnswer = first.createAnswer(actor, "one").copy(timestamp = Instant.EPOCH.plusSeconds(3))
            val secondAnswer = second.createAnswer(actor, "two").copy(timestamp = Instant.EPOCH.plusSeconds(4))
            val firstSelection = AgentSelectedEvent(timestamp = Instant.EPOCH.plusSeconds(5), namespaceId = namespaceId, caseId = caseId, agentId = first.agentId, agentName = agentOne, questionId = first.id)
            val firstFinished = AgentFinishedEvent(timestamp = Instant.EPOCH.plusSeconds(6), namespaceId = namespaceId, caseId = caseId, agentId = first.agentId, agentName = agentOne)
            val calls = mutableListOf<String>()
            lateinit var runtime: CaseRuntime
            runtime = CaseRuntime(
                id = caseId, namespaceId = namespaceId, caseCreatedAt = Instant.EPOCH,
                updateStatusCallback = { _, _ -> },
                storeEvent = { event -> if (event is AgentSelectedEvent) event.copy(timestamp = Instant.EPOCH.plusSeconds(7)) else event },
                selectAgent = { _, _ -> emptyList() }, isAgentAuthorized = TRUE_FOR_ANY_AGENTS,
                runAgent = { name, _, _, _, _ ->
                    calls += name
                    runtime.pushEvents(listOf(AgentFinishedEvent(timestamp = Instant.EPOCH.plusSeconds(8), namespaceId = namespaceId, caseId = caseId, agentId = second.agentId, agentName = name)))
                },
            )
            runtime.pushEvents(listOf(first, second, firstAnswer, secondAnswer, firstSelection, firstFinished))
            runtime.run()

            calls shouldBe listOf(agentTwo)
        }

        "pre-flight does NOT wake up agent when answer is followed by AgentFinishedEvent (OAuth guard)" {
            // Simulate the OAuth path: agent was running INSIDE its run when the answer
            // arrived, finished its turn normally, so AgentFinishedEvent comes AFTER the
            // answer. The pre-flight must NOT emit another AgentSelectedEvent.
            //
            // IMPORTANT: timestamps must be explicit and strictly increasing.
            // InMemoryCaseEventList re-sorts all events by timestamp on insertion, so
            // the order in which events appear in the source list is irrelevant — only
            // the timestamp determines their position. Without explicit timestamps every
            // event gets Instant.now() and the sort order is undefined, which would make
            // this test pass for the wrong reason (AgentFinishedEvent landing before
            // AnswerEvent instead of after it).
            val agentName = "oauth-agent"
            val agentId = UUID.nameUUIDFromBytes(agentName.toByteArray())
            val caseId = UUID.randomUUID()
            val runCalls = mutableListOf<String>()

            // Chronological order: MessageEvent(t1) -> AgentSelectedEvent(t2) ->
            //   QuestionEvent(t3) -> AnswerEvent(t4) -> AgentFinishedEvent(t5).
            // AgentFinishedEvent is AFTER AnswerEvent → OAuth guard must suppress wake-up.
            val t1 = Instant.EPOCH.plusSeconds(1)
            val t2 = Instant.EPOCH.plusSeconds(2)
            val t3 = Instant.EPOCH.plusSeconds(3)
            val t4 = Instant.EPOCH.plusSeconds(4)
            val t5 = Instant.EPOCH.plusSeconds(5)

            val questionEvent = QuestionEvent(
                timestamp = t3,
                namespaceId = namespaceId,
                caseId = caseId,
                agentId = agentId,
                agentName = agentName,
                question = "Authorize OAuth?",
                questionType = QuestionType.OAUTH_AUTHORIZE,
            )
            // createAnswer() defaults timestamp to Instant.now(); override it to t4
            // so the sort order is deterministic and AnswerEvent lands before AgentFinishedEvent.
            val answerEvent = questionEvent.createAnswer(
                Actor("user-1", "User", ActorRole.USER),
                "yes",
            ).copy(timestamp = t4)

            // OAuth order: MessageEvent -> AgentSelectedEvent -> QuestionEvent ->
            //   AnswerEvent -> AgentFinishedEvent
            // (agent finished AFTER the answer because it was already running)
            val existingEvents = listOf(
                MessageEvent(
                    timestamp = t1,
                    namespaceId = namespaceId,
                    caseId = caseId,
                    actor = userActor,
                    content = userMessage,
                ),
                AgentSelectedEvent(
                    timestamp = t2,
                    namespaceId = namespaceId,
                    caseId = caseId,
                    agentId = agentId,
                    agentName = agentName,
                ),
                questionEvent,
                answerEvent,
                AgentFinishedEvent(
                    timestamp = t5,
                    namespaceId = namespaceId,
                    caseId = caseId,
                    agentId = agentId,
                    agentName = agentName,
                ),
            )

            val runtime = CaseRuntime(
                id = caseId,
                namespaceId = namespaceId,
                caseCreatedAt = Instant.EPOCH,
                updateStatusCallback = { _, _ -> },
                storeEvent = { it },
                selectAgent = { _, _ -> listOf(agentSelectedEvent(caseId, agentName)) },
                isAgentAuthorized = TRUE_FOR_ANY_AGENTS,
                runAgent = { name, _, _, _, _ -> runCalls += name },
            )
            runtime.pushEvents(existingEvents)

            runtime.run()

            // The pre-flight must NOT have fired: the persisted OAuth question type
            // explicitly marks this answer as owned by the still-running OAuth flow.
            runCalls shouldBe emptyList()
            runtime.statusFlow.value shouldBe CaseStatus.IDLE
        }

        "pre-flight does not falsely wake an OAuth question when another question was resumed before OAuth finished" {
            // Exact interleaving regression:
            // Q1 and Q2 are outstanding; Q2 is an active OAuth flow. After Answer(Q2),
            // Answer(Q1) causes the durable correlated selection for Q1. OAuth Q2 then
            // finishes. On replay, Q2 must remain suppressed even though the Q1 selection
            // sits between its answer and the OAuth finish.
            val caseId = UUID.randomUUID()
            val q1AgentId = UUID.randomUUID()
            val oauthAgentId = UUID.randomUUID()
            val user = Actor(UUID.randomUUID().toString(), "User", ActorRole.USER)
            val q1 = QuestionEvent(
                timestamp = Instant.EPOCH.plusSeconds(1),
                namespaceId = namespaceId,
                caseId = caseId,
                agentId = q1AgentId,
                agentName = "await-agent",
                question = "Q1",
            )
            val q2 = QuestionEvent(
                timestamp = Instant.EPOCH.plusSeconds(2),
                namespaceId = namespaceId,
                caseId = caseId,
                agentId = oauthAgentId,
                agentName = "oauth-agent",
                question = "Q2",
                questionType = QuestionType.OAUTH_AUTHORIZE,
            )
            val q2Answer = q2.createAnswer(user, "authorized").copy(timestamp = Instant.EPOCH.plusSeconds(3))
            val q1Answer = q1.createAnswer(user, "answer").copy(timestamp = Instant.EPOCH.plusSeconds(4))
            val q1Selection = AgentSelectedEvent(
                timestamp = Instant.EPOCH.plusSeconds(5),
                namespaceId = namespaceId,
                caseId = caseId,
                agentId = q1AgentId,
                agentName = q1.agentName,
                questionId = q1.id,
            )
            val oauthFinished = AgentFinishedEvent(
                timestamp = Instant.EPOCH.plusSeconds(6),
                namespaceId = namespaceId,
                caseId = caseId,
                agentId = oauthAgentId,
                agentName = q2.agentName,
            )
            val runCalls = mutableListOf<String>()
            val runtime = CaseRuntime(
                id = caseId,
                namespaceId = namespaceId,
                caseCreatedAt = Instant.EPOCH,
                updateStatusCallback = { _, _ -> },
                storeEvent = { it },
                selectAgent = { _, _ -> emptyList() },
                isAgentAuthorized = TRUE_FOR_ANY_AGENTS,
                runAgent = { name, _, _, _, _ -> runCalls += name },
            )
            runtime.pushEvents(listOf(q1, q2, q2Answer, q1Answer, q1Selection, oauthFinished))

            runtime.run()

            runCalls shouldBe emptyList()
            runtime.statusFlow.value shouldBe CaseStatus.IDLE
        }

        "pre-flight does NOT wake up agent when question has no answer yet" {
            // QuestionEvent present but no AnswerEvent: the user has not replied yet.
            // The pre-flight must stay silent.
            //
            // IMPORTANT: timestamps must be explicit and strictly increasing.
            // InMemoryCaseEventList re-sorts all events by timestamp on insertion, so
            // the order in which events appear in the source list is irrelevant — only
            // the timestamp determines their position.
            val agentName = "waiting-agent"
            val agentId = UUID.nameUUIDFromBytes(agentName.toByteArray())
            val caseId = UUID.randomUUID()
            val runCalls = mutableListOf<String>()

            // Chronological order: MessageEvent(t1) -> AgentFinishedEvent(t2) -> QuestionEvent(t3).
            // No AnswerEvent → findUnresolvedQuestion returns null → no wake-up.
            val t1 = Instant.EPOCH.plusSeconds(1)
            val t2 = Instant.EPOCH.plusSeconds(2)
            val t3 = Instant.EPOCH.plusSeconds(3)

            val questionEvent = QuestionEvent(
                timestamp = t3,
                namespaceId = namespaceId,
                caseId = caseId,
                agentId = agentId,
                agentName = agentName,
                question = "Still waiting?",
            )
            val existingEvents = listOf(
                MessageEvent(
                    timestamp = t1,
                    namespaceId = namespaceId,
                    caseId = caseId,
                    actor = userActor,
                    content = userMessage,
                ),
                AgentFinishedEvent(
                    timestamp = t2,
                    namespaceId = namespaceId,
                    caseId = caseId,
                    agentId = agentId,
                    agentName = agentName,
                ),
                questionEvent,
                // No AnswerEvent
            )

            val runtime = CaseRuntime(
                id = caseId,
                namespaceId = namespaceId,
                caseCreatedAt = Instant.EPOCH,
                updateStatusCallback = { _, _ -> },
                storeEvent = { it },
                selectAgent = { _, _ -> listOf(agentSelectedEvent(caseId, agentName)) },
                isAgentAuthorized = TRUE_FOR_ANY_AGENTS,
                runAgent = { name, _, _, _, _ -> runCalls += name },
            )
            runtime.pushEvents(existingEvents)

            runtime.run()

            runCalls shouldBe emptyList()
            runtime.statusFlow.value shouldBe CaseStatus.IDLE
        }

        // -------------------------------------------------------------------------
        // Pre-flight: recipient check on addressed QuestionEvent
        // -------------------------------------------------------------------------

        "pre-flight fires when addressed question is answered by the right user" {
            // QuestionEvent.userId = aliceId. Alice answers. Pre-flight must wake the agent.
            //
            // IMPORTANT: timestamps must be explicit and strictly increasing.
            // InMemoryCaseEventList re-sorts all events by timestamp on insertion — only
            // the timestamp determines their position, not the order in the source list.
            // createAnswer() forces Instant.now(), so always .copy(timestamp = tN).
            val agentName = "addressed-agent"
            val agentId = UUID.nameUUIDFromBytes(agentName.toByteArray())
            val caseId = UUID.randomUUID()
            val aliceId = UUID.randomUUID()
            val runCalls = mutableListOf<String>()

            val t1 = Instant.EPOCH.plusSeconds(1)
            val t2 = Instant.EPOCH.plusSeconds(2)
            val t3 = Instant.EPOCH.plusSeconds(3)
            val t4 = Instant.EPOCH.plusSeconds(4)
            val t5 = Instant.EPOCH.plusSeconds(5)

            // QuestionEvent is addressed to Alice (userId = aliceId).
            val questionEvent = QuestionEvent(
                timestamp = t4,
                namespaceId = namespaceId,
                caseId = caseId,
                agentId = agentId,
                agentName = agentName,
                question = "Alice, which option?",
                userId = aliceId,
            )
            // Alice answers: actor.id must be aliceId.toString() so UUID.fromString succeeds.
            val aliceActor = Actor(id = aliceId.toString(), displayName = "Alice", role = ActorRole.USER)
            val answerEvent = questionEvent.createAnswer(aliceActor, "Option A").copy(timestamp = t5)

            val existingEvents = listOf(
                MessageEvent(
                    timestamp = t1,
                    namespaceId = namespaceId,
                    caseId = caseId,
                    actor = userActor,
                    content = userMessage,
                ),
                AgentSelectedEvent(
                    timestamp = t2,
                    namespaceId = namespaceId,
                    caseId = caseId,
                    agentId = agentId,
                    agentName = agentName,
                ),
                AgentFinishedEvent(
                    timestamp = t3,
                    namespaceId = namespaceId,
                    caseId = caseId,
                    agentId = agentId,
                    agentName = agentName,
                ),
                questionEvent,
                answerEvent,
            )

            lateinit var runtime: CaseRuntime
            runtime = CaseRuntime(
                id = caseId,
                namespaceId = namespaceId,
                caseCreatedAt = Instant.EPOCH,
                updateStatusCallback = { _, _ -> },
                storeEvent = { it },
                selectAgent = { _, _ -> listOf(agentSelectedEvent(caseId, agentName)) },
                isAgentAuthorized = TRUE_FOR_ANY_AGENTS,
                runAgent = { name, _, _, _, _ ->
                    runCalls += name
                    runtime.pushEvents(
                        listOf(
                            AgentFinishedEvent(
                                namespaceId = namespaceId,
                                caseId = caseId,
                                agentId = UUID.nameUUIDFromBytes(name.toByteArray()),
                                agentName = name,
                            ),
                        ),
                    )
                },
            )
            runtime.pushEvents(existingEvents)
            runtime.run()

            runCalls shouldBe listOf(agentName)
            runtime.statusFlow.value shouldBe CaseStatus.IDLE
        }

        "pre-flight does NOT fire when addressed question is answered by a different user" {
            // QuestionEvent.userId = aliceId. Bob answers. Pre-flight must stay silent.
            //
            // IMPORTANT: timestamps must be explicit and strictly increasing.
            val agentName = "addressed-agent-2"
            val agentId = UUID.nameUUIDFromBytes(agentName.toByteArray())
            val caseId = UUID.randomUUID()
            val aliceId = UUID.randomUUID()
            val bobId = UUID.randomUUID()
            val runCalls = mutableListOf<String>()

            val t1 = Instant.EPOCH.plusSeconds(1)
            val t2 = Instant.EPOCH.plusSeconds(2)
            val t3 = Instant.EPOCH.plusSeconds(3)
            val t4 = Instant.EPOCH.plusSeconds(4)
            val t5 = Instant.EPOCH.plusSeconds(5)

            val questionEvent = QuestionEvent(
                timestamp = t4,
                namespaceId = namespaceId,
                caseId = caseId,
                agentId = agentId,
                agentName = agentName,
                question = "Alice, which option?",
                userId = aliceId,
            )
            // Bob answers — actor.id = bobId, which != aliceId.
            val bobActor = Actor(id = bobId.toString(), displayName = "Bob", role = ActorRole.USER)
            val bobAnswerEvent = questionEvent.createAnswer(bobActor, "Option B").copy(timestamp = t5)

            val existingEvents = listOf(
                MessageEvent(
                    timestamp = t1,
                    namespaceId = namespaceId,
                    caseId = caseId,
                    actor = userActor,
                    content = userMessage,
                ),
                AgentSelectedEvent(
                    timestamp = t2,
                    namespaceId = namespaceId,
                    caseId = caseId,
                    agentId = agentId,
                    agentName = agentName,
                ),
                AgentFinishedEvent(
                    timestamp = t3,
                    namespaceId = namespaceId,
                    caseId = caseId,
                    agentId = agentId,
                    agentName = agentName,
                ),
                questionEvent,
                bobAnswerEvent,
            )

            val runtime = CaseRuntime(
                id = caseId,
                namespaceId = namespaceId,
                caseCreatedAt = Instant.EPOCH,
                updateStatusCallback = { _, _ -> },
                storeEvent = { it },
                selectAgent = { _, _ -> listOf(agentSelectedEvent(caseId, agentName)) },
                isAgentAuthorized = TRUE_FOR_ANY_AGENTS,
                runAgent = { name, _, _, _, _ -> runCalls += name },
            )
            runtime.pushEvents(existingEvents)
            runtime.run()

            // Bob's answer does not qualify — Alice has not answered yet.
            runCalls shouldBe emptyList()
            runtime.statusFlow.value shouldBe CaseStatus.IDLE
        }

        "pre-flight fires when wrong user answers first then right user answers (anti-deadlock regression)" {
            // Anti-regression for the permanent-deadlock bug: if the recipient check were
            // applied AFTER indexOfFirst (instead of inside its predicate), Bob's answer
            // would anchor the search forever and Alice's subsequent answer would never be
            // found — the agent would be stuck permanently.
            //
            // QuestionEvent.userId = aliceId.
            // Bob answers first (t5), Alice answers second (t6).
            // Expected: pre-flight finds Alice's answer as the first LEGITIMATE response
            // and wakes the agent.
            //
            // IMPORTANT: timestamps must be explicit and strictly increasing.
            val agentName = "deadlock-guard-agent"
            val agentId = UUID.nameUUIDFromBytes(agentName.toByteArray())
            val caseId = UUID.randomUUID()
            val aliceId = UUID.randomUUID()
            val bobId = UUID.randomUUID()
            val runCalls = mutableListOf<String>()

            val t1 = Instant.EPOCH.plusSeconds(1)
            val t2 = Instant.EPOCH.plusSeconds(2)
            val t3 = Instant.EPOCH.plusSeconds(3)
            val t4 = Instant.EPOCH.plusSeconds(4)
            val t5 = Instant.EPOCH.plusSeconds(5) // Bob answers first
            val t6 = Instant.EPOCH.plusSeconds(6) // Alice answers second

            val questionEvent = QuestionEvent(
                timestamp = t4,
                namespaceId = namespaceId,
                caseId = caseId,
                agentId = agentId,
                agentName = agentName,
                question = "Alice, confirm?",
                userId = aliceId,
            )
            val bobActor = Actor(id = bobId.toString(), displayName = "Bob", role = ActorRole.USER)
            val aliceActor = Actor(id = aliceId.toString(), displayName = "Alice", role = ActorRole.USER)
            val bobAnswerEvent = questionEvent.createAnswer(bobActor, "I'll answer for Alice").copy(timestamp = t5)
            val aliceAnswerEvent = questionEvent.createAnswer(aliceActor, "Confirmed").copy(timestamp = t6)

            val existingEvents = listOf(
                MessageEvent(
                    timestamp = t1,
                    namespaceId = namespaceId,
                    caseId = caseId,
                    actor = userActor,
                    content = userMessage,
                ),
                AgentSelectedEvent(
                    timestamp = t2,
                    namespaceId = namespaceId,
                    caseId = caseId,
                    agentId = agentId,
                    agentName = agentName,
                ),
                AgentFinishedEvent(
                    timestamp = t3,
                    namespaceId = namespaceId,
                    caseId = caseId,
                    agentId = agentId,
                    agentName = agentName,
                ),
                questionEvent,
                bobAnswerEvent,
                aliceAnswerEvent,
            )

            lateinit var runtime: CaseRuntime
            runtime = CaseRuntime(
                id = caseId,
                namespaceId = namespaceId,
                caseCreatedAt = Instant.EPOCH,
                updateStatusCallback = { _, _ -> },
                storeEvent = { it },
                selectAgent = { _, _ -> listOf(agentSelectedEvent(caseId, agentName)) },
                isAgentAuthorized = TRUE_FOR_ANY_AGENTS,
                runAgent = { name, _, _, _, _ ->
                    runCalls += name
                    runtime.pushEvents(
                        listOf(
                            AgentFinishedEvent(
                                namespaceId = namespaceId,
                                caseId = caseId,
                                agentId = UUID.nameUUIDFromBytes(name.toByteArray()),
                                agentName = name,
                            ),
                        ),
                    )
                },
            )
            runtime.pushEvents(existingEvents)
            runtime.run()

            // Alice's answer qualifies — agent must have been woken up despite Bob
            // having answered first.
            runCalls shouldBe listOf(agentName)
            runtime.statusFlow.value shouldBe CaseStatus.IDLE
        }

        "pre-flight fires when question has no userId and any user answers (non-regression)" {
            // QuestionEvent.userId = null (unaddressed). Any respondent qualifies.
            // This is the existing behaviour — must not regress.
            //
            // IMPORTANT: timestamps must be explicit and strictly increasing.
            val agentName = "open-question-agent"
            val agentId = UUID.nameUUIDFromBytes(agentName.toByteArray())
            val caseId = UUID.randomUUID()
            val someUserId = UUID.randomUUID()
            val runCalls = mutableListOf<String>()

            val t1 = Instant.EPOCH.plusSeconds(1)
            val t2 = Instant.EPOCH.plusSeconds(2)
            val t3 = Instant.EPOCH.plusSeconds(3)
            val t4 = Instant.EPOCH.plusSeconds(4)
            val t5 = Instant.EPOCH.plusSeconds(5)

            // userId = null → unaddressed question.
            val questionEvent = QuestionEvent(
                timestamp = t4,
                namespaceId = namespaceId,
                caseId = caseId,
                agentId = agentId,
                agentName = agentName,
                question = "Anyone can answer this",
                userId = null,
            )
            val someActor = Actor(id = someUserId.toString(), displayName = "Someone", role = ActorRole.USER)
            val answerEvent = questionEvent.createAnswer(someActor, "Here").copy(timestamp = t5)

            val existingEvents = listOf(
                MessageEvent(
                    timestamp = t1,
                    namespaceId = namespaceId,
                    caseId = caseId,
                    actor = userActor,
                    content = userMessage,
                ),
                AgentSelectedEvent(
                    timestamp = t2,
                    namespaceId = namespaceId,
                    caseId = caseId,
                    agentId = agentId,
                    agentName = agentName,
                ),
                AgentFinishedEvent(
                    timestamp = t3,
                    namespaceId = namespaceId,
                    caseId = caseId,
                    agentId = agentId,
                    agentName = agentName,
                ),
                questionEvent,
                answerEvent,
            )

            lateinit var runtime: CaseRuntime
            runtime = CaseRuntime(
                id = caseId,
                namespaceId = namespaceId,
                caseCreatedAt = Instant.EPOCH,
                updateStatusCallback = { _, _ -> },
                storeEvent = { it },
                selectAgent = { _, _ -> listOf(agentSelectedEvent(caseId, agentName)) },
                isAgentAuthorized = TRUE_FOR_ANY_AGENTS,
                runAgent = { name, _, _, _, _ ->
                    runCalls += name
                    runtime.pushEvents(
                        listOf(
                            AgentFinishedEvent(
                                namespaceId = namespaceId,
                                caseId = caseId,
                                agentId = UUID.nameUUIDFromBytes(name.toByteArray()),
                                agentName = name,
                            ),
                        ),
                    )
                },
            )
            runtime.pushEvents(existingEvents)
            runtime.run()

            // Unaddressed question: any respondent qualifies.
            runCalls shouldBe listOf(agentName)
            runtime.statusFlow.value shouldBe CaseStatus.IDLE
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

        // -------------------------------------------------------------------------
        // Anti-redirect-loop guard
        // -------------------------------------------------------------------------

        "redirect loop guard triggers at MAX_SAME_AGENT_SELECTIONS_PER_TURN" {
            // Build a history with 1 user MessageEvent followed by exactly
            // MAX_SAME_AGENT_SELECTIONS_PER_TURN AgentSelectedEvents for "AgentA".
            // The guard must fire: WarnEvent + AgentFinishedEvent emitted, runAgent never called.
            val agentName = "AgentA"
            val caseId = UUID.randomUUID()
            val agentId = UUID.nameUUIDFromBytes(agentName.toByteArray())
            val savedEvents = mutableListOf<CaseEvent>()

            val userMsg = MessageEvent(
                namespaceId = namespaceId,
                caseId = caseId,
                actor = userActor,
                content = userMessage,
            )
            val selections = (1..CaseRuntime.MAX_SAME_AGENT_SELECTIONS_PER_TURN).map {
                AgentSelectedEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    agentId = agentId,
                    agentName = agentName,
                )
            }
            val inputEvents: List<CaseEvent> = listOf(userMsg) + selections

            lateinit var runtime: CaseRuntime
            val runAgentCalled = mutableListOf<String>()
            val isAgentAuthorizedCalled = mutableListOf<String>()

            runtime = CaseRuntime(
                id = caseId,
                namespaceId = namespaceId,
                caseCreatedAt = java.time.Instant.EPOCH,
                updateStatusCallback = { _, _ -> },
                storeEvent = { event -> savedEvents.add(event); event },
                selectAgent = { _, _ -> emptyList() },
                isAgentAuthorized = { name, _ -> isAgentAuthorizedCalled.add(name); true },
                runAgent = { name, _, _, _, _ -> runAgentCalled.add(name) },
                inputEvents = inputEvents,
            )

            runtime.run()

            // Guard must have fired: WarnEvent mentioning agentName
            val warns = savedEvents.filterIsInstance<WarnEvent>()
            warns.size shouldBe 1
            warns[0].message shouldContain agentName
            warns[0].message shouldContain "could not complete the task"

            // AgentFinishedEvent must have been emitted for durability
            val finished = savedEvents.filterIsInstance<AgentFinishedEvent>()
            finished.size shouldBe 1
            finished[0].agentName shouldBe agentName

            // runAgent must never have been called
            runAgentCalled shouldBe emptyList()

            // isAgentAuthorized must never have been called (guard fires before authorization)
            isAgentAuthorizedCalled shouldBe emptyList()

            // Status must be IDLE, not ERROR
            runtime.statusFlow.value shouldBe CaseStatus.IDLE
        }

        "redirect loop guard does not trigger below MAX_SAME_AGENT_SELECTIONS_PER_TURN" {
            // MAX_SAME_AGENT_SELECTIONS_PER_TURN - 1 occurrences: runAgent must be called normally.
            val agentName = "AgentA"
            val caseId = UUID.randomUUID()
            val agentId = UUID.nameUUIDFromBytes(agentName.toByteArray())
            val savedEvents = mutableListOf<CaseEvent>()

            val userMsg = MessageEvent(
                namespaceId = namespaceId,
                caseId = caseId,
                actor = userActor,
                content = userMessage,
            )
            // MAX - 1 prior selections (the guard counts the current one too, so
            // the slice will have MAX - 1 entries when processNextStep runs, which is
            // strictly below the threshold).
            val priorSelections = (1 until CaseRuntime.MAX_SAME_AGENT_SELECTIONS_PER_TURN).map {
                AgentSelectedEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    agentId = agentId,
                    agentName = agentName,
                )
            }
            val inputEvents: List<CaseEvent> = listOf(userMsg) + priorSelections

            val runAgentCalled = mutableListOf<String>()

            lateinit var runtime: CaseRuntime
            runtime = CaseRuntime(
                id = caseId,
                namespaceId = namespaceId,
                caseCreatedAt = java.time.Instant.EPOCH,
                updateStatusCallback = { _, _ -> },
                storeEvent = { event -> savedEvents.add(event); event },
                selectAgent = { _, _ -> emptyList() },
                isAgentAuthorized = { _, _ -> true },
                runAgent = { name, _, _, _, _ ->
                    runAgentCalled.add(name)
                    // Push AgentFinishedEvent so the loop exits cleanly
                    runtime.pushEvents(listOf(
                        AgentFinishedEvent(
                            namespaceId = namespaceId,
                            caseId = caseId,
                            agentId = agentId,
                            agentName = agentName,
                        ),
                    ))
                },
                inputEvents = inputEvents,
            )

            runtime.run()

            // runAgent must have been called (no guard triggered)
            runAgentCalled shouldBe listOf(agentName)

            // No WarnEvent from the guard
            savedEvents.filterIsInstance<WarnEvent>() shouldBe emptyList()

            runtime.statusFlow.value shouldBe CaseStatus.IDLE
        }

        "redirect loop guard does not produce cross-turn false positives" {
            // MAX_SAME_AGENT_SELECTIONS_PER_TURN prior selections of AgentA in turn 1,
            // then a new user MessageEvent, then 1 AgentSelectedEvent for AgentA in turn 2.
            // The guard must NOT fire in turn 2 because the counter is scoped to the current turn.
            val agentName = "AgentA"
            val caseId = UUID.randomUUID()
            val agentId = UUID.nameUUIDFromBytes(agentName.toByteArray())
            val savedEvents = mutableListOf<CaseEvent>()

            val firstUserMsg = MessageEvent(
                namespaceId = namespaceId,
                caseId = caseId,
                actor = userActor,
                content = userMessage,
            )
            val previousTurnSelections = (1..CaseRuntime.MAX_SAME_AGENT_SELECTIONS_PER_TURN).map {
                AgentSelectedEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    agentId = agentId,
                    agentName = agentName,
                )
            }
            val secondUserMsg = MessageEvent(
                namespaceId = namespaceId,
                caseId = caseId,
                actor = userActor,
                content = listOf(MessageContent.Text("second turn")),
            )
            // One selection in the new turn
            val newTurnSelection = AgentSelectedEvent(
                namespaceId = namespaceId,
                caseId = caseId,
                agentId = agentId,
                agentName = agentName,
            )
            val inputEvents: List<CaseEvent> =
                listOf(firstUserMsg) + previousTurnSelections + listOf(secondUserMsg, newTurnSelection)

            val runAgentCalled = mutableListOf<String>()

            lateinit var runtime: CaseRuntime
            runtime = CaseRuntime(
                id = caseId,
                namespaceId = namespaceId,
                caseCreatedAt = java.time.Instant.EPOCH,
                updateStatusCallback = { _, _ -> },
                storeEvent = { event -> savedEvents.add(event); event },
                selectAgent = { _, _ -> emptyList() },
                isAgentAuthorized = { _, _ -> true },
                runAgent = { name, _, _, _, _ ->
                    runAgentCalled.add(name)
                    runtime.pushEvents(listOf(
                        AgentFinishedEvent(
                            namespaceId = namespaceId,
                            caseId = caseId,
                            agentId = agentId,
                            agentName = agentName,
                        ),
                    ))
                },
                inputEvents = inputEvents,
            )

            runtime.run()

            // No guard triggered: only 1 selection in the current turn
            runAgentCalled shouldBe listOf(agentName)
            savedEvents.filterIsInstance<WarnEvent>() shouldBe emptyList()
            runtime.statusFlow.value shouldBe CaseStatus.IDLE
        }

        "redirect loop guard counts per agent name, not globally" {
            // MAX_SAME_AGENT_SELECTIONS_PER_TURN selections of AgentA interleaved with
            // fewer-than-threshold selections of AgentB. Guard must fire for AgentA (the
            // last event in the scan), not for AgentB.
            val agentA = "AgentA"
            val agentB = "AgentB"
            val caseId = UUID.randomUUID()
            val agentAId = UUID.nameUUIDFromBytes(agentA.toByteArray())
            val agentBId = UUID.nameUUIDFromBytes(agentB.toByteArray())
            val savedEvents = mutableListOf<CaseEvent>()

            val userMsg = MessageEvent(
                namespaceId = namespaceId,
                caseId = caseId,
                actor = userActor,
                content = userMessage,
            )

            // Build interleaved list: A, B, A, B, ..., A (AgentA appears MAX times, AgentB fewer)
            val maxA = CaseRuntime.MAX_SAME_AGENT_SELECTIONS_PER_TURN
            val interleaved = mutableListOf<CaseEvent>()
            for (i in 0 until maxA) {
                interleaved.add(AgentSelectedEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    agentId = agentAId,
                    agentName = agentA,
                ))
                if (i < maxA - 1) { // AgentB appears maxA - 1 times, safely below threshold
                    interleaved.add(AgentSelectedEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        agentId = agentBId,
                        agentName = agentB,
                    ))
                }
            }
            val inputEvents: List<CaseEvent> = listOf(userMsg) + interleaved

            val runAgentCalled = mutableListOf<String>()

            lateinit var runtime: CaseRuntime
            runtime = CaseRuntime(
                id = caseId,
                namespaceId = namespaceId,
                caseCreatedAt = java.time.Instant.EPOCH,
                updateStatusCallback = { _, _ -> },
                storeEvent = { event -> savedEvents.add(event); event },
                selectAgent = { _, _ -> emptyList() },
                isAgentAuthorized = { _, _ -> true },
                runAgent = { name, _, _, _, _ -> runAgentCalled.add(name) },
                inputEvents = inputEvents,
            )

            runtime.run()

            // Guard must have fired for AgentA
            val warns = savedEvents.filterIsInstance<WarnEvent>()
            warns.size shouldBe 1
            warns[0].message shouldContain agentA

            // runAgent never called
            runAgentCalled shouldBe emptyList()
            runtime.statusFlow.value shouldBe CaseStatus.IDLE
        }

        "redirect loop guard: cut is durable without a new user message" {
            // After the guard fires and emits AgentFinishedEvent, re-running the same
            // runtime without a new user message must not call runAgent.
            // The newly emitted AgentFinishedEvent is now the last event; processNextStep
            // finds it first and returns AGENT_FINISHED without launching the agent.
            val agentName = "AgentA"
            val caseId = UUID.randomUUID()
            val agentId = UUID.nameUUIDFromBytes(agentName.toByteArray())
            val savedEvents = mutableListOf<CaseEvent>()

            val userMsg = MessageEvent(
                namespaceId = namespaceId,
                caseId = caseId,
                actor = userActor,
                content = userMessage,
            )
            val selections = (1..CaseRuntime.MAX_SAME_AGENT_SELECTIONS_PER_TURN).map {
                AgentSelectedEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    agentId = agentId,
                    agentName = agentName,
                )
            }
            val inputEvents: List<CaseEvent> = listOf(userMsg) + selections

            val runAgentCalled = mutableListOf<String>()

            lateinit var runtime: CaseRuntime
            runtime = CaseRuntime(
                id = caseId,
                namespaceId = namespaceId,
                caseCreatedAt = java.time.Instant.EPOCH,
                updateStatusCallback = { _, _ -> },
                storeEvent = { event -> savedEvents.add(event); event },
                selectAgent = { _, _ -> emptyList() },
                isAgentAuthorized = { _, _ -> true },
                runAgent = { name, _, _, _, _ -> runAgentCalled.add(name) },
                inputEvents = inputEvents,
            )

            // First run: guard fires, emits WarnEvent + AgentFinishedEvent
            runtime.run()
            runAgentCalled shouldBe emptyList()
            runtime.statusFlow.value shouldBe CaseStatus.IDLE

            // Second run without a new user message: the AgentFinishedEvent is now last,
            // processNextStep returns AGENT_FINISHED immediately — runAgent still not called.
            runtime.run()
            runAgentCalled shouldBe emptyList()
            runtime.statusFlow.value shouldBe CaseStatus.IDLE
        }

        // -------------------------------------------------------------------------
        // Rehydration from AgentRunningEvent
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
