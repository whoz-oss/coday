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
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * A simple recording wrapper for the runAgent callback.
 *
 * MockK's interception of suspend function-type lambdas is unreliable.
 * A plain recording class avoids that fragility.
 */
class RecordingRunAgent(
    private val delegate: suspend (String, List<CaseEvent>) -> Unit,
) {
    private val _calls = mutableListOf<Pair<String, List<CaseEvent>>>()
    val callCount: Int get() = _calls.size

    val asCallback: suspend (String, List<CaseEvent>, () -> Boolean) -> Unit = { name, events, _ ->
        _calls += name to events
        delegate(name, events)
    }
}

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

class CaseRuntimeSpec : StringSpec() {
    val namespaceId: UUID = UUID.randomUUID()
    val userActor = Actor(id = "user-123", displayName = "Test User", role = ActorRole.USER)
    val userMessage = listOf(MessageContent.Text("hello"))

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
        val statusHistory: MutableList<CaseStatus>,
    )

    /**
     * Build a [CaseRuntime] and immediately call [CaseRuntime.startLoop].
     *
     * The loop runs in the background. Use [awaitIdle] to wait for IDLE transition
     * before asserting.
     */
    fun buildRuntime(
        agentName: String = "default-agent",
        agent: Agent = finishingAgent(agentName),
    ): TestFixture {
        val savedEvents = mutableListOf<CaseEvent>()
        val statusHistory = mutableListOf<CaseStatus>()
        val runtimeId = UUID.randomUUID()

        val selectAgent = RecordingSelectAgent { listOf(agentSelectedEvent(runtimeId, agentName)) }

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
                updateStatus = { _, status -> synchronized(statusHistory) { statusHistory.add(status) } },
                storeEvent = { event ->
                    savedEvents.add(event)
                    event
                },
                selectAgent = selectAgent.asCallback,
                runAgent = runAgent.asCallback,
            )
        runtime.startLoop()

        return TestFixture(runtime, selectAgent, runAgent, savedEvents, statusHistory)
    }

    /**
     * Wait until the runtime's status history contains [expectedStatus] or the deadline passes.
     * Returns true if the status was observed.
     */
    fun awaitStatus(
        statusHistory: MutableList<CaseStatus>,
        expectedStatus: CaseStatus,
        timeoutMs: Long = 5_000,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (synchronized(statusHistory) { statusHistory.contains(expectedStatus) }) return true
            Thread.sleep(20)
        }
        return false
    }

    init {

        // -------------------------------------------------------------------------
        // Core regression: runAgent called exactly once per turn
        // -------------------------------------------------------------------------

        "runAgent is called exactly once when using the default agent" {
            val (runtime, _, runAgent, _, statusHistory) = buildRuntime()

            runtime.addUserMessage(userActor, userMessage)
            awaitStatus(statusHistory, CaseStatus.IDLE) shouldBe true

            runAgent.callCount shouldBe 1
        }

        "selectAgent is called exactly once per user message" {
            val (runtime, selectAgent, _, _, statusHistory) = buildRuntime()

            runtime.addUserMessage(userActor, userMessage)
            awaitStatus(statusHistory, CaseStatus.IDLE) shouldBe true

            selectAgent.callCount shouldBe 1
        }

        // -------------------------------------------------------------------------
        // Event sequence
        // -------------------------------------------------------------------------

        "AgentSelectedEvent then AgentRunningEvent then AgentFinishedEvent are saved in order" {
            val (runtime, _, _, savedEvents, statusHistory) = buildRuntime()

            runtime.addUserMessage(userActor, userMessage)
            awaitStatus(statusHistory, CaseStatus.IDLE) shouldBe true

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
            val (runtime, _, _, savedEvents, statusHistory) = buildRuntime(agentName = "gemini-flash")

            runtime.addUserMessage(userActor, userMessage)
            awaitStatus(statusHistory, CaseStatus.IDLE) shouldBe true

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
            val statusHistory = mutableListOf<CaseStatus>()
            val runtimeId = UUID.randomUUID()

            lateinit var runtime: CaseRuntime
            runtime =
                CaseRuntime(
                    id = runtimeId,
                    namespaceId = namespaceId,
                    updateStatus = { _, status -> synchronized(statusHistory) { statusHistory.add(status) } },
                    storeEvent = { event ->
                        savedEvents.add(event)
                        event
                    },
                    selectAgent = {
                        listOf(
                            WarnEvent(
                                namespaceId = namespaceId,
                                caseId = runtimeId,
                                message = "Agent 'unknown' not found",
                            ),
                            agentSelectedEvent(runtimeId, agentName),
                        )
                    },
                    runAgent = { _, events, _ ->
                        agent.run(events).collect { event ->
                            savedEvents.add(event)
                            runtime.pushEvents(listOf(event))
                        }
                    },
                )
            runtime.startLoop()

            runtime.addUserMessage(userActor, listOf(MessageContent.Text("@unknown hello")))
            awaitStatus(statusHistory, CaseStatus.IDLE) shouldBe true

            val warn = savedEvents.filterIsInstance<WarnEvent>().firstOrNull()
            warn.shouldNotBeNull()
            warn.message shouldBe "Agent 'unknown' not found"
            savedEvents.filterIsInstance<AgentSelectedEvent>().first().agentName shouldBe agentName
        }

        // -------------------------------------------------------------------------
        // processNextStep ordering
        // -------------------------------------------------------------------------

        "processNextStep emits AgentRunningEvent before runAgent is ever called" {
            val agentName = "ordered-agent"
            val callOrder = mutableListOf<String>()
            val agentId = UUID.nameUUIDFromBytes(agentName.toByteArray())
            val runtimeId = UUID.randomUUID()
            val statusHistory = mutableListOf<CaseStatus>()

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
                    updateStatus = { _, status -> synchronized(statusHistory) { statusHistory.add(status) } },
                    storeEvent = { event ->
                        if (event is AgentRunningEvent) callOrder.add("AgentRunningEvent saved")
                        event
                    },
                    selectAgent = { listOf(agentSelectedEvent(runtimeId, agentName)) },
                    runAgent = { _, events, _ ->
                        callOrder.add("runAgent")
                        orderedAgent.run(events).collect { event ->
                            runtime.pushEvents(listOf(event))
                        }
                    },
                )
            runtime.startLoop()

            runtime.addUserMessage(userActor, userMessage)
            awaitStatus(statusHistory, CaseStatus.IDLE) shouldBe true

            val runningIdx = callOrder.indexOf("AgentRunningEvent saved")
            val runIdx = callOrder.indexOf("runAgent")

            (runningIdx >= 0) shouldBe true
            (runIdx > runningIdx) shouldBe true
        }

        // -------------------------------------------------------------------------
        // shouldContinue lambda contract
        // -------------------------------------------------------------------------

        "shouldContinue lambda returns false after requestInterrupt is called" {
            // The lambda passed to runAgent must reflect the interrupt flag.
            val runtimeId = UUID.randomUUID()
            val capturedShouldContinue = AtomicReference<(() -> Boolean)?>(null)
            val statusHistory = mutableListOf<CaseStatus>()
            val runAgentStarted = CountDownLatch(1)

            val runtime =
                CaseRuntime(
                    id = runtimeId,
                    namespaceId = namespaceId,
                    updateStatus = { _, status -> synchronized(statusHistory) { statusHistory.add(status) } },
                    storeEvent = { it },
                    selectAgent = { listOf(agentSelectedEvent(runtimeId, "agent")) },
                    runAgent = { _, _, shouldContinue ->
                        capturedShouldContinue.set(shouldContinue)
                        runAgentStarted.countDown()
                        // Do not push AgentFinishedEvent — we want to interrupt mid-turn.
                    },
                )
            runtime.startLoop()

            runtime.addUserMessage(userActor, userMessage)
            // Wait for runAgent to be entered before interrupting.
            runAgentStarted.await(5, TimeUnit.SECONDS) shouldBe true

            // shouldContinue must be true before the interrupt
            capturedShouldContinue.get()!!.invoke() shouldBe true

            runtime.requestInterrupt()
            // Lambda should now return false
            capturedShouldContinue.get()!!.invoke() shouldBe false
            // Runtime should return to IDLE
            awaitStatus(statusHistory, CaseStatus.IDLE) shouldBe true
        }

        "shouldContinue lambda returns false after requestKill is called" {
            val runtimeId = UUID.randomUUID()
            val capturedShouldContinue = AtomicReference<(() -> Boolean)?>(null)
            val statusHistory = mutableListOf<CaseStatus>()
            val runAgentStarted = CountDownLatch(1)
            val killedStatus = AtomicBoolean(false)

            val runtime =
                CaseRuntime(
                    id = runtimeId,
                    namespaceId = namespaceId,
                    updateStatus = { _, status ->
                        synchronized(statusHistory) { statusHistory.add(status) }
                        if (status == CaseStatus.KILLED) killedStatus.set(true)
                    },
                    storeEvent = { it },
                    selectAgent = { listOf(agentSelectedEvent(runtimeId, "agent")) },
                    onKilled = { _ ->
                        killedStatus.set(true)
                    },
                    runAgent = { _, _, shouldContinue ->
                        capturedShouldContinue.set(shouldContinue)
                        runAgentStarted.countDown()
                    },
                )
            runtime.startLoop()

            runtime.addUserMessage(userActor, userMessage)
            runAgentStarted.await(5, TimeUnit.SECONDS) shouldBe true

            capturedShouldContinue.get()!!.invoke() shouldBe true

            runtime.requestKill()
            capturedShouldContinue.get()!!.invoke() shouldBe false
            // onKilled was called
            killedStatus.get() shouldBe true
        }

        "shouldContinue lambda returns true during execution when neither interrupt nor kill has been requested" {
            // Positive-path: lambda must return true while runAgent is executing
            // and no interrupt or kill has been signalled.
            val runtimeId = UUID.randomUUID()
            val lambdaResultDuringRun = AtomicReference<Boolean?>(null)
            val statusHistory = mutableListOf<CaseStatus>()

            lateinit var runtime: CaseRuntime
            runtime =
                CaseRuntime(
                    id = runtimeId,
                    namespaceId = namespaceId,
                    updateStatus = { _, status -> synchronized(statusHistory) { statusHistory.add(status) } },
                    storeEvent = { it },
                    selectAgent = { listOf(agentSelectedEvent(runtimeId, "agent")) },
                    runAgent = { _, _, shouldContinue ->
                        // Sample BEFORE pushing AgentFinishedEvent: no interrupt/kill yet.
                        lambdaResultDuringRun.set(shouldContinue())
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
            runtime.startLoop()

            runtime.addUserMessage(userActor, userMessage)
            awaitStatus(statusHistory, CaseStatus.IDLE) shouldBe true

            lambdaResultDuringRun.get() shouldBe true
        }

        // -------------------------------------------------------------------------
        // Interrupt: runtime returns to IDLE and accepts a second message
        // -------------------------------------------------------------------------

        "after interrupt the runtime accepts a second message and runs again" {
            val runtimeId = UUID.randomUUID()
            val runAgentCallCount = AtomicInteger(0)
            val statusHistory = mutableListOf<CaseStatus>()
            val firstRunStarted = CountDownLatch(1)
            val interruptDone = CountDownLatch(1)

            lateinit var runtime: CaseRuntime
            runtime =
                CaseRuntime(
                    id = runtimeId,
                    namespaceId = namespaceId,
                    updateStatus = { _, status -> synchronized(statusHistory) { statusHistory.add(status) } },
                    storeEvent = { it },
                    selectAgent = { listOf(agentSelectedEvent(runtimeId, "agent")) },
                    runAgent = { _, _, _ ->
                        val count = runAgentCallCount.incrementAndGet()
                        if (count == 1) {
                            firstRunStarted.countDown()
                            // Wait for interrupt signal before completing
                            interruptDone.await(5, TimeUnit.SECONDS)
                        } else {
                            // Second run: finish immediately
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
                        }
                    },
                )
            runtime.startLoop()

            // First message: agent starts but hangs
            runtime.addUserMessage(userActor, userMessage)
            firstRunStarted.await(5, TimeUnit.SECONDS) shouldBe true

            // Interrupt the first run
            runtime.requestInterrupt()
            interruptDone.countDown()

            // Wait for IDLE
            awaitStatus(statusHistory, CaseStatus.IDLE) shouldBe true

            // Second message: should run cleanly to IDLE again
            val idleCountBefore = synchronized(statusHistory) { statusHistory.count { it == CaseStatus.IDLE } }
            runtime.addUserMessage(userActor, userMessage)
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline) {
                if (synchronized(statusHistory) { statusHistory.count { it == CaseStatus.IDLE } } > idleCountBefore) break
                Thread.sleep(20)
            }

            runAgentCallCount.get() shouldBe 2
            synchronized(statusHistory) { statusHistory.count { it == CaseStatus.IDLE } } shouldBe (idleCountBefore + 1)
        }

        // -------------------------------------------------------------------------
        // Rehydration: AgentRunningEvent already in history
        // -------------------------------------------------------------------------

        "runAgent is called exactly once when AgentRunningEvent is already in the event list" {
            val agentName = "gemini-flash"
            val caseId = UUID.randomUUID()
            val agentId = UUID.nameUUIDFromBytes(agentName.toByteArray())
            val agent = finishingAgent(agentName)
            val savedEvents = mutableListOf<CaseEvent>()
            val statusHistory = mutableListOf<CaseStatus>()

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
                    updateStatus = { _, status -> synchronized(statusHistory) { statusHistory.add(status) } },
                    storeEvent = { event ->
                        savedEvents.add(event)
                        event
                    },
                    selectAgent = { listOf(agentSelectedEvent(caseId, agentName)) },
                    runAgent = recorder.asCallback,
                    inputEvents = listOf(existingUserMessage, existingRunningEvent),
                )
            runtime.startLoop() // hasPendingWork() detects AgentRunningEvent -> signals workChannel

            awaitStatus(statusHistory, CaseStatus.IDLE) shouldBe true

            recorder.callCount shouldBe 1
            savedEvents.filterIsInstance<AgentSelectedEvent>() shouldBe emptyList()
        }
    }
}
