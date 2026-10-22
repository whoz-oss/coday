package io.whozoss.agentos.agent

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.agent.Agent
import io.whozoss.agentos.sdk.agent.CaseWorker
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
import io.whozoss.agentos.workflow.LoopConfigResolver
import io.whozoss.agentos.workflow.LoopRunContext
import io.whozoss.agentos.workflow.LoopRunOutcome
import io.whozoss.agentos.workflow.LoopWorkflowRunnerService
import kotlinx.coroutines.flow.toList
import java.util.UUID

/**
 * Unit tests for [AgentLoop].
 *
 * AgentLoop now reads its config exclusively from the injected [LoopConfigResolver].
 * Tests covering the old message-based config parsing (objectMapper / JSON extraction
 * from the triggering MessageEvent) have been deleted — that behaviour was deliberately
 * removed when AgentLoop was refactored to use a resolver.
 *
 * Covers:
 * - Completed / Aborted / Interrupted outcomes → correct event sequences
 * - shouldContinue=false short-circuits before the runner
 * - Empty event list throws
 * - AgentFinishedEvent / AgentRunningEvent carry null llmProvider / llmModel
 * - AgentLoop is a CaseWorker and NOT an Agent
 */
class AgentLoopUnitSpec :
    StringSpec({

        val namespaceId: UUID = UUID.randomUUID()
        val caseId: UUID = UUID.randomUUID()

        val validLoopConfig =
            LoopConfig(
                version = 1,
                steps =
                    listOf(
                        io.whozoss.agentos.workflow
                            .LoopStep(id = "search", tool = "SearchTalents"),
                    ),
            )

        val completed = LoopRunOutcome.Completed(lastStepOutput = "done")

        // -------------------------------------------------------------------------
        // Helpers
        // -------------------------------------------------------------------------

        fun runnerReturning(outcome: LoopRunOutcome): LoopWorkflowRunnerService =
            mockk<LoopWorkflowRunnerService>().also { coEvery { it.run(any(), any(), any()) } returns outcome }

        fun configResolverReturning(config: LoopConfig?): LoopConfigResolver =
            mockk<LoopConfigResolver>().also { every { it.resolve(any()) } returns config }

        fun agent(
            runner: LoopWorkflowRunnerService = runnerReturning(completed),
            configResolver: LoopConfigResolver = configResolverReturning(validLoopConfig),
            name: String = "loop-agent",
            resolvedTools: Collection<StandardTool<*>> = emptyList(),
            triggerUser: User? = null,
        ) = AgentLoop(
            metadata = EntityMetadata(id = UUID.randomUUID()),
            name = name,
            configResolver = configResolver,
            runner = runner,
            resolvedTools = resolvedTools,
            triggerUser = triggerUser,
        )

        fun userMessage(text: String = "@loop-agent run") =
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

        "context carries namespaceId, caseId and agentName" {
            val runner = runnerReturning(completed)
            val contextSlot = slot<LoopRunContext>()
            coEvery { runner.run(any(), capture(contextSlot), any()) } returns completed

            agent(runner = runner, name = "my-loop").run(listOf(userMessage())).toList()

            contextSlot.captured.namespaceId shouldBe namespaceId
            contextSlot.captured.caseId shouldBe caseId
            contextSlot.captured.agentName shouldBe "my-loop"
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
        // Resolver returns null → WarnEvent
        // -------------------------------------------------------------------------

        "resolver returning null emits a WarnEvent and does not call the runner" {
            val runner = runnerReturning(completed)

            val emitted =
                agent(
                    runner = runner,
                    configResolver = configResolverReturning(null),
                ).run(listOf(userMessage())).toList()

            emitted shouldHaveSize 3
            emitted[0].shouldBeInstanceOf<AgentRunningEvent>()
            emitted[1].shouldBeInstanceOf<WarnEvent>()
            emitted.warnMessage() shouldContain "No loop configuration"
            emitted[2].shouldBeInstanceOf<AgentFinishedEvent>()
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
