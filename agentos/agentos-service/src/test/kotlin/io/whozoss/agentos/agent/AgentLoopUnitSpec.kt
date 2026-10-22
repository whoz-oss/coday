package io.whozoss.agentos.agent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.whozoss.agentos.sdk.agent.Agent
import io.whozoss.agentos.sdk.agent.CaseWorker
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent
import io.whozoss.agentos.sdk.caseEvent.AgentRunningEvent
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.WarnEvent
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.user.User
import io.whozoss.agentos.workflow.LoopConfig
import io.whozoss.agentos.workflow.LoopRunContext
import io.whozoss.agentos.workflow.LoopRunOutcome
import io.whozoss.agentos.workflow.LoopWorkflowRunnerService
import kotlinx.coroutines.flow.toList
import java.util.UUID

/**
 * Unit tests for [AgentLoop].
 *
 * AgentLoop reads its payload exclusively from the triggering [MessageEvent] (the last user
 * message in the case events). The config JSON is extracted from that message's text content.
 * These tests cover loop config parsing from the message and the mapping of [LoopRunOutcome]
 * to case events. The workflow itself is covered by [LoopWorkflowRunnerUnitSpec].
 */
class AgentLoopUnitSpec :
    StringSpec({

        val objectMapper = ObjectMapper().registerKotlinModule()
        val namespaceId: UUID = UUID.randomUUID()
        val caseId: UUID = UUID.randomUUID()

        val validLoopConfigJson =
            """
            {
                "version": 1,
                "steps": [
                    {"id": "search", "tool": "SearchTalents", "input": {"params": {"resolveTargets": ["OWNER"]}}},
                    {"id": "act", "tool": "FreeCaseStarterForUser", "input": {"from": "search"}}
                ]
            }
            """.trimIndent()

        val completed = LoopRunOutcome.Completed(lastStepOutput = "done")

        // -------------------------------------------------------------------------
        // Helpers
        // -------------------------------------------------------------------------

        fun runnerReturning(outcome: LoopRunOutcome): LoopWorkflowRunnerService =
            mockk<LoopWorkflowRunnerService>().also { coEvery { it.run(any(), any(), any()) } returns outcome }

        fun agent(
            runner: LoopWorkflowRunnerService = runnerReturning(completed),
            name: String = "loop-agent",
            resolvedTools: Collection<StandardTool<*>> = emptyList(),
            triggerUser: User? = null,
        ) = AgentLoop(
            metadata = EntityMetadata(id = UUID.randomUUID()),
            name = name,
            objectMapper = objectMapper,
            runner = runner,
            resolvedTools = resolvedTools,
            triggerUser = triggerUser,
        )

        fun userMessage(text: String = "@loop-agent $validLoopConfigJson") =
            MessageEvent(
                namespaceId = namespaceId,
                caseId = caseId,
                actor = Actor(id = "user-1", displayName = "Alice", role = ActorRole.USER),
                content = listOf(MessageContent.Text(text)),
            )

        fun List<CaseEvent>.warnMessage(): String = filterIsInstance<WarnEvent>().single().message

        // -------------------------------------------------------------------------
        // Completed outcome
        // -------------------------------------------------------------------------

        "completed run emits AgentRunningEvent, summary MessageEvent then AgentFinishedEvent" {
            val emitted = agent().run(listOf(userMessage())).toList()

            emitted shouldHaveSize 3
            emitted[0].shouldBeInstanceOf<AgentRunningEvent>()
            val summary = emitted[1].shouldBeInstanceOf<MessageEvent>()
            summary.actor.role shouldBe ActorRole.AGENT
            (summary.content.single() as MessageContent.Text).content shouldBe "done"
            emitted[2].shouldBeInstanceOf<AgentFinishedEvent>()
        }

        "completed run with null lastStepOutput emits a fallback summary" {
            val emitted =
                agent(runner = runnerReturning(LoopRunOutcome.Completed(lastStepOutput = null)))
                    .run(listOf(userMessage()))
                    .toList()

            emitted[1].shouldBeInstanceOf<MessageEvent>().let {
                (it.content.single() as MessageContent.Text).content shouldContain "completed"
            }
        }

        "config is parsed from the triggering message and passed to the runner" {
            val runner = runnerReturning(completed)
            val configSlot = slot<LoopConfig>()
            coEvery { runner.run(capture(configSlot), any(), any()) } returns completed

            agent(runner = runner).run(listOf(userMessage())).toList()

            configSlot.captured.version shouldBe 1
            configSlot.captured.steps.size shouldBe 2
            configSlot.captured.steps[0].id shouldBe "search"
            configSlot.captured.steps[1].id shouldBe "act"
        }

        "context carries namespaceId, caseId, agentName and emitEvent" {
            val runner = runnerReturning(completed)
            val contextSlot = slot<LoopRunContext>()
            coEvery { runner.run(any(), capture(contextSlot), any()) } returns completed

            agent(runner = runner, name = "my-loop").run(listOf(userMessage())).toList()

            contextSlot.captured.namespaceId shouldBe namespaceId
            contextSlot.captured.caseId shouldBe caseId
            contextSlot.captured.agentName shouldBe "my-loop"
        }

        "unknown fields in loop config JSON are ignored" {
            val runner = runnerReturning(completed)
            val messageWithExtra = userMessage(
                "@loop-agent {\"version\": 1, \"steps\": [{\"id\": \"s1\", \"tool\": \"MyTool\", \"extra\": \"x\"}], \"note\": \"ignored\"}"
            )

            agent(runner = runner).run(listOf(messageWithExtra)).toList()

            coVerify(exactly = 1) { runner.run(match { it.steps.size == 1 }, any(), any()) }
        }

        "config is read from the LAST user message when multiple exist" {
            val runner = runnerReturning(completed)
            val configSlot = slot<LoopConfig>()
            coEvery { runner.run(capture(configSlot), any(), any()) } returns completed

            val firstMessage = userMessage("@loop-agent {\"version\": 1, \"steps\": [{\"id\": \"old\", \"tool\": \"OldTool\"}]}")
            val lastMessage = userMessage("@loop-agent {\"version\": 1, \"steps\": [{\"id\": \"new\", \"tool\": \"NewTool\"}]}")

            agent(runner = runner).run(listOf(firstMessage, lastMessage)).toList()

            configSlot.captured.steps[0].id shouldBe "new"
        }

        // -------------------------------------------------------------------------
        // Aborted outcome
        // -------------------------------------------------------------------------

        "aborted run emits a WarnEvent carrying the reason" {
            val emitted =
                agent(runner = runnerReturning(LoopRunOutcome.Aborted("something went wrong")))
                    .run(listOf(userMessage()))
                    .toList()

            emitted shouldHaveSize 3
            emitted[0].shouldBeInstanceOf<AgentRunningEvent>()
            emitted.warnMessage() shouldContain "something went wrong"
            emitted[2].shouldBeInstanceOf<AgentFinishedEvent>()
        }

        // -------------------------------------------------------------------------
        // Interrupted outcome
        // -------------------------------------------------------------------------

        "interrupted run emits only AgentRunningEvent and AgentFinishedEvent (no extra event)" {
            val emitted =
                agent(runner = runnerReturning(LoopRunOutcome.Interrupted))
                    .run(listOf(userMessage()))
                    .toList()

            emitted shouldHaveSize 2
            emitted[0].shouldBeInstanceOf<AgentRunningEvent>()
            emitted[1].shouldBeInstanceOf<AgentFinishedEvent>()
        }

        // -------------------------------------------------------------------------
        // Missing / invalid loop config in the triggering message
        // -------------------------------------------------------------------------

        "no user message in events emits a WarnEvent and does not call the runner" {
            val runner = runnerReturning(completed)
            // Provide only an agent message — no user message
            val agentMessage = MessageEvent(
                namespaceId = namespaceId,
                caseId = caseId,
                actor = Actor(id = "agent-1", displayName = "Bot", role = ActorRole.AGENT),
                content = listOf(MessageContent.Text("hello")),
            )

            val emitted = agent(runner = runner).run(listOf(agentMessage)).toList()

            emitted shouldHaveSize 3
            emitted[0].shouldBeInstanceOf<AgentRunningEvent>()
            emitted[1].shouldBeInstanceOf<WarnEvent>()
            emitted.warnMessage() shouldContain "No triggering user message"
            emitted[2].shouldBeInstanceOf<AgentFinishedEvent>()
            coVerify(exactly = 0) { runner.run(any(), any(), any()) }
        }

        "user message with no JSON braces emits a WarnEvent" {
            val runner = runnerReturning(completed)

            val emitted = agent(runner = runner).run(listOf(userMessage("start without config"))).toList()

            emitted[1].shouldBeInstanceOf<WarnEvent>()
            emitted.warnMessage() shouldContain "JSON object"
            coVerify(exactly = 0) { runner.run(any(), any(), any()) }
        }

        "loop config with version != 1 emits a WarnEvent" {
            val runner = runnerReturning(completed)

            val emitted = agent(runner = runner).run(
                listOf(userMessage("{\"version\": 2, \"steps\": [{\"id\": \"s1\", \"tool\": \"T\"}]}"))
            ).toList()

            emitted[1].shouldBeInstanceOf<WarnEvent>()
            emitted.warnMessage() shouldContain "version"
            coVerify(exactly = 0) { runner.run(any(), any(), any()) }
        }

        "loop config with empty steps emits a WarnEvent" {
            val runner = runnerReturning(completed)

            val emitted = agent(runner = runner).run(
                listOf(userMessage("{\"version\": 1, \"steps\": []}"))
            ).toList()

            emitted[1].shouldBeInstanceOf<WarnEvent>()
            emitted.warnMessage() shouldContain "step"
            coVerify(exactly = 0) { runner.run(any(), any(), any()) }
        }

        // -------------------------------------------------------------------------
        // Interruption and invariants
        // -------------------------------------------------------------------------

        "shouldContinue=false emits only AgentFinishedEvent and does not call the runner" {
            val runner = runnerReturning(completed)

            val emitted = agent(runner = runner).run(listOf(userMessage()), shouldContinue = { false }).toList()

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

            val finished = loop.run(listOf(userMessage())).toList().last() as AgentFinishedEvent

            finished.agentName shouldBe "my-loop"
            finished.agentId shouldBe loop.id
            finished.llmProvider shouldBe null
            finished.llmModel shouldBe null
        }

        "all events carry the case namespaceId and caseId" {
            agent().run(listOf(userMessage())).toList().forEach { event ->
                event.namespaceId shouldBe namespaceId
                event.caseId shouldBe caseId
            }
        }

        "AgentRunningEvent carries null llmProvider and llmModel" {
            val running = agent().run(listOf(userMessage())).toList().first() as AgentRunningEvent

            running.llmProvider shouldBe null
            running.llmModel shouldBe null
        }

        "AgentLoop is a CaseWorker and is NOT an Agent (no LLM identity)" {
            val loop = agent()
            loop.shouldBeInstanceOf<CaseWorker>()
            (loop is Agent) shouldBe false
        }
    })
