package io.whozoss.agentos.workflow

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.ToolRequestEvent
import io.whozoss.agentos.sdk.caseEvent.ToolResponseEvent
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import io.whozoss.agentos.user.User
import java.util.UUID

/**
 * Unit tests for [LoopWorkflowRunnerService]: tool resolution, input building and hand-over between
 * steps, transcript events, and outcome variants.
 */
class LoopWorkflowRunnerUnitSpec :
    StringSpec({
        val objectMapper = ObjectMapper().registerKotlinModule()
        val namespaceId: UUID = UUID.randomUUID()
        val caseId: UUID = UUID.randomUUID()
        val triggerUser = User(metadata = EntityMetadata(id = UUID.randomUUID()), externalId = "ext-trigger", email = "t@example.com")
        val runner = LoopWorkflowRunnerService(objectMapper)
        val emittedEvents = mutableListOf<CaseEvent>()

        fun json(text: String): JsonNode = objectMapper.readTree(text)

        fun step(
            id: String,
            tool: String,
            input: String = "{}",
            feedsNext: String? = null,
        ) = LoopStep(id = id, tool = tool, input = json(input), feedsNext = feedsNext)

        fun context(
            tools: Collection<StandardTool<*>>,
            user: User? = triggerUser,
        ) = LoopRunContext(
            namespaceId = namespaceId,
            caseId = caseId,
            agentName = "scheduler-loop",
            triggerUser = user,
            tools = tools,
            caseEvents = emptyList(),
            emitEvent = { emittedEvents.add(it) },
        )

        fun tool(
            name: String,
            result: ToolExecutionResult = ToolExecutionResult.success("ok"),
        ): StandardTool<*> =
            mockk<StandardTool<*>>().also {
                every { it.name } returns name
                coEvery { it.executeWithJson(any(), any()) } returns result
            }

        beforeEach { emittedEvents.clear() }

        "aborts without a triggering user" {
            runner.run(LoopConfig(listOf(step("s1", "T"))), context(emptyList(), user = null)) { true }
                .shouldBeInstanceOf<LoopRunOutcome.Aborted>()
                .reason shouldContain "identified user"
        }

        "aborts when a step's tool is not granted, naming the step and the tool" {
            val reason =
                runner.run(LoopConfig(listOf(step("s1", "Missing"))), context(emptyList())) { true }
                    .shouldBeInstanceOf<LoopRunOutcome.Aborted>()
                    .reason
            reason shouldContain "s1"
            reason shouldContain "Missing"
        }

        "resolves a tool by exact name or by its integration prefix" {
            val bare = tool("Search")
            val prefixed = tool("CASE__BatchCaseStarter")
            runner.run(LoopConfig(listOf(step("a", "Search"), step("b", "BatchCaseStarter"))), context(listOf(bare, prefixed))) { true }
                .shouldBeInstanceOf<LoopRunOutcome.Completed>()
            coVerify(exactly = 1) { bare.executeWithJson(any(), any()) }
            coVerify(exactly = 1) { prefixed.executeWithJson(any(), any()) }
        }

        "passes the step input and the trigger user identity to the tool" {
            val t = tool("T")
            val input = slot<String>()
            val ctx = slot<ToolContext>()
            coEvery { t.executeWithJson(capture(input), capture(ctx)) } returns ToolExecutionResult.success("ok")

            runner.run(LoopConfig(listOf(step("s1", "T", """{"k":"v"}"""))), context(listOf(t))) { true }

            json(input.captured)["k"].asText() shouldBe "v"
            ctx.captured.userId shouldBe triggerUser.metadata.id
            ctx.captured.namespaceId shouldBe namespaceId
        }

        "hands the feedsNext field of a step's output to the next step's input" {
            val search = tool("Search", ToolExecutionResult.success("found", structuredOutput = json("""{"targets":[{"userExternalId":"a"}],"x":1}""")))
            val start = tool("Start")
            val startInput = slot<String>()
            coEvery { start.executeWithJson(capture(startInput), any()) } returns ToolExecutionResult.success("ok")

            runner.run(
                LoopConfig(listOf(step("s1", "Search", feedsNext = "targets"), step("s2", "Start", """{"agentName":"A"}"""))),
                context(listOf(search, start)),
            ) { true }

            val handed = json(startInput.captured)
            handed["agentName"].asText() shouldBe "A"
            handed["targets"][0]["userExternalId"].asText() shouldBe "a"
            handed.has("x") shouldBe false
        }

        "aborts when the feedsNext field is missing from the output" {
            val search = tool("Search", ToolExecutionResult.success("found", structuredOutput = json("""{"other":[]}""")))
            val start = tool("Start")

            runner.run(LoopConfig(listOf(step("s1", "Search", feedsNext = "targets"), step("s2", "Start"))), context(listOf(search, start))) { true }
                .shouldBeInstanceOf<LoopRunOutcome.Aborted>()
                .reason shouldContain "'targets'"
            coVerify(exactly = 0) { start.executeWithJson(any(), any()) }
        }

        "aborts readably when a step input is not a JSON object" {
            runner.run(LoopConfig(listOf(step("s1", "T", "[1]"))), context(listOf(tool("T")))) { true }
                .shouldBeInstanceOf<LoopRunOutcome.Aborted>()
                .reason shouldContain "JSON object"
        }

        "emits one request and one response event per step" {
            runner.run(LoopConfig(listOf(step("s1", "T"))), context(listOf(tool("T", ToolExecutionResult.success("done"))))) { true }

            emittedEvents.filterIsInstance<ToolRequestEvent>().size shouldBe 1
            (emittedEvents.filterIsInstance<ToolResponseEvent>().single().output as MessageContent.Text).content shouldBe "done"
        }

        "aborts on a tool failure or exception and runs no further step" {
            val next = tool("Next")
            runner.run(LoopConfig(listOf(step("s1", "Bad"), step("s2", "Next"))), context(listOf(tool("Bad", ToolExecutionResult.error("nope")), next))) { true }
                .shouldBeInstanceOf<LoopRunOutcome.Aborted>()
                .reason shouldContain "nope"
            coVerify(exactly = 0) { next.executeWithJson(any(), any()) }

            val throwing = mockk<StandardTool<*>>().also {
                every { it.name } returns "Boom"
                coEvery { it.executeWithJson(any(), any()) } throws RuntimeException("boom")
            }
            runner.run(LoopConfig(listOf(step("s1", "Boom"))), context(listOf(throwing))) { true }
                .shouldBeInstanceOf<LoopRunOutcome.Aborted>()
                .reason shouldContain "boom"
        }

        "returns Interrupted when shouldContinue turns false between steps" {
            val first = tool("A")
            val second = tool("B")
            var checks = 0
            runner.run(LoopConfig(listOf(step("s1", "A"), step("s2", "B"))), context(listOf(first, second))) { checks++ == 0 }
                .shouldBeInstanceOf<LoopRunOutcome.Interrupted>()
            coVerify(exactly = 0) { second.executeWithJson(any(), any()) }
        }

        "returns Completed with the last step's output" {
            runner.run(
                LoopConfig(listOf(step("s1", "A"), step("s2", "B"))),
                context(listOf(tool("A", ToolExecutionResult.success("first")), tool("B", ToolExecutionResult.success("second")))),
            ) { true }.shouldBeInstanceOf<LoopRunOutcome.Completed>().lastStepOutput shouldBe "second"
        }
    })
