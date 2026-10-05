package io.whozoss.agentos.agent

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.ThinkingEvent
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import kotlinx.coroutines.flow.toList
import java.util.UUID

/**
 * Unit tests for [AgentLoop].
 *
 * AgentLoop is a zero-LLM agent: no Spring AI, no chat client, minimal mocks needed.
 * All tests collect the emitted [CaseEvent] flow and assert on its structure.
 */
class AgentLoopUnitSpec : StringSpec({

    val objectMapper = ObjectMapper().registerKotlinModule()
    val namespaceId: UUID = UUID.randomUUID()
    val caseId: UUID = UUID.randomUUID()
    val userId: UUID = UUID.randomUUID()

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    fun mockUser(externalId: String = "ext-user-123"): User =
        User(
            metadata = EntityMetadata(id = UUID.randomUUID()),
            externalId = externalId,
            email = "test@example.com",
        )

    fun mockSearchTool(
        name: String = "SearchTalents",
        structuredOutput: String = """
            {"data": [{"entityType": "TALENT", "entityId": "ext-user-123"}],
             "metadata": {"totalCount": 1, "next": null}}
        """.trimIndent(),
        success: Boolean = true,
    ): StandardTool<*> {
        val tool = mockk<StandardTool<*>>()
        every { tool.name } returns name
        val node = objectMapper.readTree(structuredOutput)
        io.mockk.coEvery {
            tool.executeWithJson(any(), any())
        } returns ToolExecutionResult(
            output = "search done",
            success = success,
            structuredOutput = node,
        )
        return tool
    }

    fun agent(
        name: String = "loop-agent",
        resolvedTools: Collection<StandardTool<*>> = emptyList(),
        userService: UserService? = null,
        triggerUserId: UUID? = userId,
    ) = AgentLoop(
        metadata = EntityMetadata(id = UUID.randomUUID()),
        name = name,
        objectMapper = objectMapper,
        resolvedTools = resolvedTools,
        userService = userService,
        triggerUserId = triggerUserId,
    )

    fun userMessage(text: String) =
        MessageEvent(
            namespaceId = namespaceId,
            caseId = caseId,
            actor = Actor(id = "user-1", displayName = "Alice", role = ActorRole.USER),
            content = listOf(MessageContent.Text(text)),
        )

    fun agentMessage(text: String) =
        MessageEvent(
            namespaceId = namespaceId,
            caseId = caseId,
            actor = Actor(id = "agent-1", displayName = "loop-agent", role = ActorRole.AGENT),
            content = listOf(MessageContent.Text(text)),
        )

    val validPayload =
        """
        {
            "tool": "SearchTalents",
            "searchInput": {"endDatePeriod": ["THIS_WEEK"], "resolveTargets": ["OWNER"]},
            "act": {"agentName": "talent-analyzer", "promptTemplate": "Analyse this entity: {entityId}"}
        }
        """.trimIndent()

    // -------------------------------------------------------------------------
    // Happy path — with no tools/services (skeleton still finishes cleanly)
    // -------------------------------------------------------------------------

    "run with valid payload but no search tool emits ThinkingEvent then AgentFinishedEvent" {
        val events: List<CaseEvent> = listOf(userMessage(validPayload))

        val emitted = agent().run(events).toList()

        emitted shouldHaveSize 2
        emitted[0].shouldBeInstanceOf<ThinkingEvent>()
        emitted[1].shouldBeInstanceOf<AgentFinishedEvent>()
    }

    "run emits AgentFinishedEvent with correct agentName and no llmProvider" {
        val events: List<CaseEvent> = listOf(userMessage(validPayload))
        val loop = agent(name = "my-loop")

        val finished = loop.run(events).toList().last() as AgentFinishedEvent

        finished.agentName shouldBe "my-loop"
        finished.agentId shouldBe loop.id
        finished.llmProvider shouldBe null
        finished.llmModel shouldBe null
    }

    "run emits events with correct namespaceId and caseId" {
        val events: List<CaseEvent> = listOf(userMessage(validPayload))

        val emitted = agent().run(events).toList()

        emitted.forEach { event ->
            event.namespaceId shouldBe namespaceId
            event.caseId shouldBe caseId
        }
    }

    // -------------------------------------------------------------------------
    // Full SEARCH → ACT happy path
    // -------------------------------------------------------------------------

    "run with search tool and matching user logs intent and counts as launched" {
        val searchTool = mockSearchTool()
        val user = mockUser()
        val userServiceMock = mockk<UserService>()

        every { userServiceMock.findByExternalId("ext-user-123") } returns user

        val events: List<CaseEvent> = listOf(userMessage(validPayload))
        val emitted =
            agent(
                resolvedTools = listOf(searchTool),
                userService = userServiceMock,
            ).run(events).toList()

        // ThinkingEvent + MessageEvent (summary) + AgentFinishedEvent
        emitted shouldHaveSize 3
        emitted[0].shouldBeInstanceOf<ThinkingEvent>()
        emitted[1].shouldBeInstanceOf<MessageEvent>()
        emitted[2].shouldBeInstanceOf<AgentFinishedEvent>()

        val summary = (emitted[1] as MessageEvent).content.filterIsInstance<MessageContent.Text>().first().content
        summary.contains("launched 1") shouldBe true
        summary.contains("skipped 0") shouldBe true
    }

    "run skips entity when userService returns null for entityId" {
        val searchTool = mockSearchTool()
        val userServiceMock = mockk<UserService>()

        every { userServiceMock.findByExternalId("ext-user-123") } returns null

        val events: List<CaseEvent> = listOf(userMessage(validPayload))
        val emitted =
            agent(
                resolvedTools = listOf(searchTool),
                userService = userServiceMock,
            ).run(events).toList()

        // ThinkingEvent + MessageEvent (summary: 0 launched, 1 skipped) + AgentFinishedEvent
        emitted shouldHaveSize 3
        emitted[0].shouldBeInstanceOf<ThinkingEvent>()
        emitted[1].shouldBeInstanceOf<MessageEvent>()
        emitted[2].shouldBeInstanceOf<AgentFinishedEvent>()

        val summary = (emitted[1] as MessageEvent).content.filterIsInstance<MessageContent.Text>().first().content
        summary.contains("launched 0") shouldBe true
        summary.contains("skipped 1") shouldBe true
    }

    "run finishes immediately when search tool is not found" {
        val events: List<CaseEvent> = listOf(userMessage(validPayload))
        // No tools registered — SearchTalents will not be found.
        val emitted = agent(resolvedTools = emptyList()).run(events).toList()

        emitted shouldHaveSize 2
        emitted[0].shouldBeInstanceOf<ThinkingEvent>()
        emitted[1].shouldBeInstanceOf<AgentFinishedEvent>()
    }

    "run finishes immediately when search tool returns failure" {
        val failingTool =
            mockSearchTool(
                structuredOutput = """{"data": [], "metadata": {"totalCount": 0, "next": null}}""",
                success = false,
            )
        val events: List<CaseEvent> = listOf(userMessage(validPayload))
        val emitted = agent(resolvedTools = listOf(failingTool)).run(events).toList()

        emitted shouldHaveSize 2
        emitted[0].shouldBeInstanceOf<ThinkingEvent>()
        emitted[1].shouldBeInstanceOf<AgentFinishedEvent>()
    }

    // -------------------------------------------------------------------------
    // shouldContinue = false — early exit before ThinkingEvent
    // -------------------------------------------------------------------------

    "run with shouldContinue=false emits only AgentFinishedEvent without ThinkingEvent" {
        val events: List<CaseEvent> = listOf(userMessage(validPayload))

        val emitted = agent().run(events, shouldContinue = { false }).toList()

        emitted shouldHaveSize 1
        emitted[0].shouldBeInstanceOf<AgentFinishedEvent>()
    }

    // -------------------------------------------------------------------------
    // Malformed / missing payload — fail-safe finish
    // -------------------------------------------------------------------------

    "run with invalid JSON payload emits ThinkingEvent then AgentFinishedEvent without throwing" {
        val events: List<CaseEvent> = listOf(userMessage("not json at all"))

        val emitted = agent().run(events).toList()

        emitted shouldHaveSize 2
        emitted[0].shouldBeInstanceOf<ThinkingEvent>()
        emitted[1].shouldBeInstanceOf<AgentFinishedEvent>()
    }

    "run with payload missing tool field emits ThinkingEvent then AgentFinishedEvent" {
        val noTool = """{"searchInput": {}, "act": {"agentName": "a", "promptTemplate": "t"}}"""
        val events: List<CaseEvent> = listOf(userMessage(noTool))

        val emitted = agent().run(events).toList()

        emitted shouldHaveSize 2
        emitted[0].shouldBeInstanceOf<ThinkingEvent>()
        emitted[1].shouldBeInstanceOf<AgentFinishedEvent>()
    }

    "run with blank payload emits ThinkingEvent then AgentFinishedEvent" {
        val events: List<CaseEvent> = listOf(userMessage("   "))

        val emitted = agent().run(events).toList()

        emitted shouldHaveSize 2
        emitted[0].shouldBeInstanceOf<ThinkingEvent>()
        emitted[1].shouldBeInstanceOf<AgentFinishedEvent>()
    }

    "run with no user MessageEvent emits ThinkingEvent then AgentFinishedEvent" {
        // Only an agent message — no user turn.
        val events: List<CaseEvent> = listOf(agentMessage("I am the agent."))

        val emitted = agent().run(events).toList()

        emitted shouldHaveSize 2
        emitted[0].shouldBeInstanceOf<ThinkingEvent>()
        emitted[1].shouldBeInstanceOf<AgentFinishedEvent>()
    }

    "run with empty event list throws IllegalArgumentException" {
        io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> {
            agent().run(emptyList()).toList()
        }
    }

    // -------------------------------------------------------------------------
    // Agent identity
    // -------------------------------------------------------------------------

    "llmProvider and llmModel are sentinel values signalling no LLM usage" {
        val loop = agent()

        loop.llmProvider shouldBe AgentLoop.PROVIDER_NAME
        loop.llmModel shouldBe AgentLoop.MODEL_NAME
    }
})
