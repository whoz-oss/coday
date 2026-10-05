package io.whozoss.agentos.agent

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.ThinkingEvent
import io.whozoss.agentos.sdk.caseEvent.WarnEvent
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.user.User
import kotlinx.coroutines.flow.toList
import java.util.UUID

/**
 * Unit tests for [AgentLoop].
 *
 * AgentLoop is a thin adapter: these tests cover payload parsing and the mapping of
 * [LoopRunOutcome] to case events. The workflow itself is covered by [LoopWorkflowRunnerUnitSpec].
 */
class AgentLoopUnitSpec : StringSpec({

    val objectMapper = ObjectMapper().registerKotlinModule()
    val namespaceId: UUID = UUID.randomUUID()
    val caseId: UUID = UUID.randomUUID()

    val validPayload =
        """
        {
            "tool": "SearchTalents",
            "searchInput": {"endDatePeriod": ["THIS_WEEK"], "resolveTargets": ["OWNER"]},
            "act": {"agentName": "talent-analyzer", "promptTemplate": "Analyse this entity: {entityId}"}
        }
        """.trimIndent()

    val completed =
        LoopRunOutcome.Completed(
            searchTool = "SearchTalents",
            returned = 1,
            totalCount = 1,
            hasMorePages = false,
            launchedCaseIds = listOf(UUID.randomUUID()),
            unknownUser = 0,
            noAgentAccess = 0,
            failed = 0,
            overLimit = 0,
            maxItems = 50,
            targetAgent = "talent-analyzer",
            interrupted = false,
        )

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    fun runnerReturning(outcome: LoopRunOutcome): LoopWorkflowRunner =
        mockk<LoopWorkflowRunner>().also { coEvery { it.run(any(), any(), any()) } returns outcome }

    fun agent(
        runner: LoopWorkflowRunner = runnerReturning(completed),
        name: String = "loop-agent",
        resolvedTools: Collection<StandardTool<*>> = emptyList(),
        triggerUser: User? = null,
        caseLauncher: CaseLauncher? = null,
    ) = AgentLoop(
        metadata = EntityMetadata(id = UUID.randomUUID()),
        name = name,
        objectMapper = objectMapper,
        runner = runner,
        resolvedTools = resolvedTools,
        triggerUser = triggerUser,
        caseLauncher = caseLauncher,
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

    fun List<CaseEvent>.warnMessage(): String = filterIsInstance<WarnEvent>().single().message

    // -------------------------------------------------------------------------
    // Completed outcome
    // -------------------------------------------------------------------------

    "completed run emits ThinkingEvent, summary MessageEvent then AgentFinishedEvent" {
        val emitted = agent().run(listOf(userMessage(validPayload))).toList()

        emitted shouldHaveSize 3
        emitted[0].shouldBeInstanceOf<ThinkingEvent>()
        val summary = emitted[1].shouldBeInstanceOf<MessageEvent>()
        summary.actor.role shouldBe ActorRole.AGENT
        (summary.content.single() as MessageContent.Text).content shouldContain "Launched 1 case(s)"
        emitted[2].shouldBeInstanceOf<AgentFinishedEvent>()
    }

    "payload is passed to the runner with the case context" {
        val runner = runnerReturning(completed)
        val user = User(metadata = EntityMetadata(id = UUID.randomUUID()), externalId = "ext-trigger", email = "t@example.com")
        val launcher = CaseLauncher { _, _, _, _ -> UUID.randomUUID() }
        val payloadSlot = slot<AgentLoopPayload>()
        val contextSlot = slot<LoopRunContext>()

        agent(runner = runner, name = "my-loop", triggerUser = user, caseLauncher = launcher)
            .run(listOf(userMessage(validPayload)))
            .toList()

        coVerify { runner.run(capture(payloadSlot), capture(contextSlot), any()) }
        payloadSlot.captured.tool shouldBe "SearchTalents"
        payloadSlot.captured.act shouldBe AgentLoopAct("talent-analyzer", "Analyse this entity: {entityId}")
        contextSlot.captured.namespaceId shouldBe namespaceId
        contextSlot.captured.caseId shouldBe caseId
        contextSlot.captured.agentName shouldBe "my-loop"
        contextSlot.captured.triggerUser shouldBe user
        contextSlot.captured.caseLauncher shouldBe launcher
    }

    "leading @mention used to route the message is ignored when parsing" {
        val runner = runnerReturning(completed)

        agent(runner = runner).run(listOf(userMessage("@loop-agent $validPayload"))).toList()

        coVerify(exactly = 1) { runner.run(match { it.tool == "SearchTalents" }, any(), any()) }
    }

    // -------------------------------------------------------------------------
    // Aborted outcome
    // -------------------------------------------------------------------------

    "aborted run emits a WarnEvent carrying the reason" {
        val emitted =
            agent(runner = runnerReturning(LoopRunOutcome.Aborted("Search tool 'X' is not available")))
                .run(listOf(userMessage(validPayload)))
                .toList()

        emitted shouldHaveSize 3
        emitted[0].shouldBeInstanceOf<ThinkingEvent>()
        emitted.warnMessage() shouldContain "Search tool 'X' is not available"
        emitted[2].shouldBeInstanceOf<AgentFinishedEvent>()
    }

    // -------------------------------------------------------------------------
    // Malformed / missing payload — visible warning, runner never called
    // -------------------------------------------------------------------------

    listOf(
        "invalid JSON" to "not json at all",
        "missing tool field" to """{"searchInput": {}, "act": {"agentName": "a", "promptTemplate": "t"}}""",
        "blank tool field" to """{"tool": " ", "searchInput": {}, "act": {"agentName": "a", "promptTemplate": "t"}}""",
        "blank message" to "   ",
        "mention only" to "@loop-agent",
    ).forEach { (label, text) ->
        "payload with $label emits a WarnEvent and does not call the runner" {
            val runner = runnerReturning(completed)

            val emitted = agent(runner = runner).run(listOf(userMessage(text))).toList()

            emitted shouldHaveSize 3
            emitted[0].shouldBeInstanceOf<ThinkingEvent>()
            emitted[1].shouldBeInstanceOf<WarnEvent>()
            emitted[2].shouldBeInstanceOf<AgentFinishedEvent>()
            coVerify(exactly = 0) { runner.run(any(), any(), any()) }
        }
    }

    "no user MessageEvent emits a WarnEvent" {
        val emitted = agent().run(listOf(agentMessage("I am the agent."))).toList()

        emitted.warnMessage() shouldContain "No user message"
    }

    // -------------------------------------------------------------------------
    // Interruption and invariants
    // -------------------------------------------------------------------------

    "shouldContinue=false emits only AgentFinishedEvent and does not call the runner" {
        val runner = runnerReturning(completed)

        val emitted = agent(runner = runner).run(listOf(userMessage(validPayload)), shouldContinue = { false }).toList()

        emitted shouldHaveSize 1
        emitted[0].shouldBeInstanceOf<AgentFinishedEvent>()
        coVerify(exactly = 0) { runner.run(any(), any(), any()) }
    }

    "empty event list throws IllegalArgumentException" {
        shouldThrow<IllegalArgumentException> {
            agent().run(emptyList()).toList()
        }
    }

    "AgentFinishedEvent carries agent identity and no llmProvider" {
        val loop = agent(name = "my-loop")

        val finished = loop.run(listOf(userMessage(validPayload))).toList().last() as AgentFinishedEvent

        finished.agentName shouldBe "my-loop"
        finished.agentId shouldBe loop.id
        finished.llmProvider shouldBe null
        finished.llmModel shouldBe null
    }

    "all events carry the case namespaceId and caseId" {
        agent().run(listOf(userMessage(validPayload))).toList().forEach { event ->
            event.namespaceId shouldBe namespaceId
            event.caseId shouldBe caseId
        }
    }

    "llmProvider and llmModel are sentinel values signalling no LLM usage" {
        val loop = agent()

        loop.llmProvider shouldBe AgentLoop.PROVIDER_NAME
        loop.llmModel shouldBe AgentLoop.MODEL_NAME
    }
})
