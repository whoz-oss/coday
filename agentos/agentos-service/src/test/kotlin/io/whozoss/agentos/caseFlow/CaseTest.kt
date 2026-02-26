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
import io.whozoss.agentos.caseEvent.CaseEventService
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

class CaseTest : StringSpec({
    timeout = 5000

    val projectId: UUID = UUID.randomUUID()
    val userActor = Actor(id = "user-123", displayName = "Test User", role = ActorRole.USER)
    val userMessage = listOf(MessageContent.Text("hello"))

    /**
     * Build a mock Agent whose run() immediately emits AgentFinishedEvent.
     * The caseId is read from the first event in the provided list so the event
     * is recognised by Case.processNextStep() as belonging to the current case.
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
     * Build a Case with mocked dependencies.
     *
     * All events passed through caseEventService.save() are captured in [savedEvents]
     * so tests can assert on the full ordered sequence without relying on the hot SharedFlow.
     */
    data class TestFixture(
        val case: Case,
        val agentService: AgentService,
        val savedEvents: MutableList<CaseEvent>,
    )

    fun buildCase(
        agentName: String = "default-agent",
        agent: Agent = finishingAgent(agentName),
    ): TestFixture {
        val agentService: AgentService = mockk()
        every { agentService.getDefaultAgentName() } returns agentName
        every { agentService.findAgentByName(agentName) } returns agent
        every { agentService.resolveAgentName(any<String>()) } returns null
        coEvery { agentService.cleanup() } returns Unit

        val caseService: CaseService = mockk {
            every { save(any()) } returns mockk(relaxed = true)
        }

        val savedEvents = mutableListOf<CaseEvent>()
        val caseEventService: CaseEventService = mockk {
            every { save(any()) } answers {
                val event = firstArg<CaseEvent>()
                savedEvents.add(event)
                event
            }
        }

        val case = Case(
            projectId = projectId,
            agentService = agentService,
            caseService = caseService,
            caseEventService = caseEventService,
        )
        return TestFixture(case, agentService, savedEvents)
    }

    // -------------------------------------------------------------------------
    // Core regression: agent instantiated exactly once per run
    // -------------------------------------------------------------------------

    "agent is instantiated exactly once when using the default agent" {
        val (case, agentService) = buildCase()

        case.addUserMessage(userActor, userMessage)

        // findAgentByName must be called exactly once — inside AgentSelectedEvent handling
        verify(exactly = 1) { agentService.findAgentByName(any<String>()) }
    }

    "getDefaultAgent is never called when selecting the default agent" {
        val (case, agentService) = buildCase()

        case.addUserMessage(userActor, userMessage)

        // Selection reads only the name cheaply, never builds a full Agent
        verify(exactly = 0) { agentService.getDefaultAgent() }
        verify(exactly = 1) { agentService.getDefaultAgentName() }
    }

    // -------------------------------------------------------------------------
    // Event sequence
    // -------------------------------------------------------------------------

    "AgentSelectedEvent then AgentRunningEvent then AgentFinishedEvent are saved in order" {
        val (case, _, savedEvents) = buildCase()

        case.addUserMessage(userActor, userMessage)

        val agentEvents = savedEvents.filter {
            it is AgentSelectedEvent || it is AgentRunningEvent || it is AgentFinishedEvent
        }
        agentEvents shouldHaveAtLeastSize 3
        agentEvents[0].shouldBeInstanceOf<AgentSelectedEvent>()
        agentEvents[1].shouldBeInstanceOf<AgentRunningEvent>()
        agentEvents[2].shouldBeInstanceOf<AgentFinishedEvent>()
    }

    "AgentSelectedEvent and AgentRunningEvent carry the same agentId and agentName" {
        val (case, _, savedEvents) = buildCase(agentName = "gemini-flash")

        case.addUserMessage(userActor, userMessage)

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
        val (case, agentService) = buildCase()

        every { agentService.resolveAgentName("special-agent") } returns "special-agent"
        every { agentService.findAgentByName("special-agent") } returns mentionedAgent

        case.addUserMessage(userActor, listOf(MessageContent.Text("@special-agent do something")))

        // resolveAgentName at selection, findAgentByName at execution — each exactly once
        verify(exactly = 1) { agentService.resolveAgentName("special-agent") }
        verify(exactly = 1) { agentService.findAgentByName("special-agent") }
        // getDefaultAgentName must NOT be called — an explicit agent was selected
        verify(exactly = 0) { agentService.getDefaultAgentName() }
    }

    "@mention instantiates the agent exactly once even though it went through AgentSelectedEvent" {
        val mentionedAgent = finishingAgent("special-agent")
        val (case, agentService) = buildCase()

        every { agentService.resolveAgentName("special-agent") } returns "special-agent"
        every { agentService.findAgentByName("special-agent") } returns mentionedAgent

        case.addUserMessage(userActor, listOf(MessageContent.Text("@special-agent do something")))

        verify(exactly = 1) { agentService.findAgentByName("special-agent") }
    }

    "@mention emits WarnEvent when the agent is not found and falls back to default" {
        val (case, agentService, savedEvents) = buildCase(agentName = "default-agent")

        every { agentService.resolveAgentName("unknown-agent") } returns null

        case.addUserMessage(userActor, listOf(MessageContent.Text("@unknown-agent hello")))

        val warn = savedEvents.filterIsInstance<WarnEvent>().firstOrNull()
        warn.shouldNotBeNull()
        warn.message shouldBe "Agent 'unknown-agent' not found"

        // Default agent still runs after the warn
        savedEvents.filterIsInstance<AgentSelectedEvent>().first().agentName shouldBe "default-agent"
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

        val caseService: CaseService = mockk {
            every { save(any()) } returns mockk(relaxed = true)
        }
        val savedEvents = mutableListOf<CaseEvent>()
        val caseEventService: CaseEventService = mockk {
            every { save(any()) } answers {
                val event = firstArg<CaseEvent>()
                savedEvents.add(event)
                event
            }
        }

        val existingUserMessage = MessageEvent(
            metadata = EntityMetadata(id = UUID.randomUUID()),
            projectId = projectId,
            caseId = caseId,
            actor = userActor,
            content = userMessage,
        )
        val existingRunningEvent = AgentRunningEvent(
            metadata = EntityMetadata(id = UUID.randomUUID()),
            projectId = projectId,
            caseId = caseId,
            agentId = agentId,
            agentName = agentName,
        )

        val case = Case(
            id = caseId,
            projectId = projectId,
            agentService = agentService,
            caseService = caseService,
            caseEventService = caseEventService,
            inputEvents = listOf(existingUserMessage, existingRunningEvent),
        )

        case.addUserMessage(userActor, userMessage)

        // AgentRunningEvent already present — processNextStep goes straight to
        // findAgentByName without emitting a new AgentSelectedEvent first
        verify(exactly = 1) { agentService.findAgentByName(agentName) }
        savedEvents.filterIsInstance<AgentSelectedEvent>() shouldBe emptyList()
    }
})
