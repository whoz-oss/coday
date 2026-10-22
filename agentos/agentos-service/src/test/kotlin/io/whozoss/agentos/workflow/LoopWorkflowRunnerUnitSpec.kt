package io.whozoss.agentos.workflow

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
import io.whozoss.agentos.sdk.caseEvent.ToolRequestEvent
import io.whozoss.agentos.sdk.caseEvent.ToolResponseEvent
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import io.whozoss.agentos.user.User
import java.util.UUID

/**
 * Unit tests for [LoopWorkflowRunnerService]: guards, step resolution, input forwarding,
 * event emission, and outcome variants.
 */
class LoopWorkflowRunnerUnitSpec :
    StringSpec({

        val objectMapper = ObjectMapper().registerKotlinModule()
        val namespaceId: UUID = UUID.randomUUID()
        val caseId: UUID = UUID.randomUUID()

        val triggerUser =
            User(
                metadata = EntityMetadata(id = UUID.randomUUID()),
                externalId = "ext-trigger",
                email = "t@example.com",
            )

        val runner = LoopWorkflowRunnerService(objectMapper)

        val emittedEvents = mutableListOf<CaseEvent>()

        fun context(
            tools: Collection<StandardTool<*>>,
            user: User? = triggerUser,
        ) = LoopRunContext(
            namespaceId = namespaceId,
            caseId = caseId,
            agentName = "loop-agent",
            triggerUser = user,
            tools = tools,
            caseEvents = emptyList(),
            emitEvent = { emittedEvents.add(it) },
        )

        fun mockTool(
            name: String,
            result: ToolExecutionResult,
        ): StandardTool<*> =
            mockk<StandardTool<*>>().also {
                every { it.name } returns name
                coEvery { it.executeWithJson(any(), any()) } returns result
            }

        fun successTool(
            name: String,
            output: String = "ok",
            structured: com.fasterxml.jackson.databind.JsonNode? = null,
        ) = mockTool(
            name,
            ToolExecutionResult.success(output, structuredOutput = structured),
        )

        fun failTool(
            name: String,
            output: String = "failed",
        ) = mockTool(name, ToolExecutionResult.error(output))

        beforeEach { emittedEvents.clear() }

        // -------------------------------------------------------------------------
        // Guard: trigger user
        // -------------------------------------------------------------------------

        "aborts without a triggering user" {
            val config = LoopConfig(steps = listOf(LoopStep(id = "s1", tool = "MyTool")))
            val result = runner.run(config, context(emptyList(), user = null)) { true }
            result.shouldBeInstanceOf<LoopRunOutcome.Aborted>().reason shouldContain "identified user"
        }

        // -------------------------------------------------------------------------
        // Tool resolution
        // -------------------------------------------------------------------------

        "aborts when step tool is not available" {
            val config = LoopConfig(steps = listOf(LoopStep(id = "s1", tool = "MissingTool")))
            val result = runner.run(config, context(emptyList())) { true }
            val aborted = result.shouldBeInstanceOf<LoopRunOutcome.Aborted>()
            aborted.reason shouldContain "MissingTool"
            aborted.reason shouldContain "s1"
        }

        "resolves tool by exact name" {
            val tool = successTool("MyTool")
            val config = LoopConfig(steps = listOf(LoopStep(id = "s1", tool = "MyTool")))
            runner.run(config, context(listOf(tool))) { true }.shouldBeInstanceOf<LoopRunOutcome.Completed>()
            coVerify(exactly = 1) { tool.executeWithJson(any(), any()) }
        }

        "resolves tool by suffix after __" {
            val tool = successTool("INT__MyTool")
            val config = LoopConfig(steps = listOf(LoopStep(id = "s1", tool = "MyTool")))
            runner.run(config, context(listOf(tool))) { true }.shouldBeInstanceOf<LoopRunOutcome.Completed>()
            coVerify(exactly = 1) { tool.executeWithJson(any(), any()) }
        }

        // -------------------------------------------------------------------------
        // Step execution and events
        // -------------------------------------------------------------------------

        "emits ToolRequestEvent and ToolResponseEvent for each step" {
            val tool = successTool("MyTool", output = "done")
            val config = LoopConfig(steps = listOf(LoopStep(id = "s1", tool = "MyTool")))
            runner.run(config, context(listOf(tool))) { true }

            emittedEvents.filterIsInstance<ToolRequestEvent>().size shouldBe 1
            emittedEvents.filterIsInstance<ToolResponseEvent>().size shouldBe 1
            emittedEvents.filterIsInstance<ToolResponseEvent>().single().output.let {
                (it as io.whozoss.agentos.sdk.caseEvent.MessageContent.Text).content shouldBe "done"
            }
        }

        "passes step params as input JSON" {
            val tool = successTool("MyTool")
            val paramsNode = objectMapper.readTree("""{"key":"val"}""")
            val config =
                LoopConfig(
                    steps =
                        listOf(
                            LoopStep(id = "s1", tool = "MyTool", input = LoopStepInput(params = paramsNode)),
                        ),
                )
            val jsonSlot = slot<String>()
            coEvery { tool.executeWithJson(capture(jsonSlot), any()) } returns ToolExecutionResult.success("ok")
            runner.run(config, context(listOf(tool))) { true }
            objectMapper.readTree(jsonSlot.captured)["key"].asText() shouldBe "val"
        }

        "passes trigger user identity in ToolContext" {
            val tool = successTool("MyTool")
            val ctxSlot = slot<ToolContext>()
            coEvery { tool.executeWithJson(any(), capture(ctxSlot)) } returns ToolExecutionResult.success("ok")
            val config = LoopConfig(steps = listOf(LoopStep(id = "s1", tool = "MyTool")))
            runner.run(config, context(listOf(tool))) { true }
            ctxSlot.captured.userId shouldBe triggerUser.metadata.id
            ctxSlot.captured.userExternalId shouldBe "ext-trigger"
            ctxSlot.captured.namespaceId shouldBe namespaceId
        }

        // -------------------------------------------------------------------------
        // Step output forwarding via `from`
        // -------------------------------------------------------------------------

        "forwards structured output of a prior step under previousStepOutput" {
            val structuredOutput = objectMapper.readTree("""{"items":[1,2,3]}""")
            val step1Tool = successTool("Step1Tool", structured = structuredOutput)
            val step2Tool = successTool("Step2Tool")

            val jsonSlot = slot<String>()
            coEvery { step2Tool.executeWithJson(capture(jsonSlot), any()) } returns ToolExecutionResult.success("ok")

            val config =
                LoopConfig(
                    steps =
                        listOf(
                            LoopStep(id = "step1", tool = "Step1Tool"),
                            LoopStep(id = "step2", tool = "Step2Tool", input = LoopStepInput(from = "step1")),
                        ),
                )
            runner.run(config, context(listOf(step1Tool, step2Tool))) { true }

            val inputNode = objectMapper.readTree(jsonSlot.captured)
            inputNode["previousStepOutput"]["items"].size() shouldBe 3
        }

        "aborts when `from` references a step with no structured output" {
            val step1Tool = successTool("Step1Tool", structured = null)
            val step2Tool = successTool("Step2Tool")

            val config =
                LoopConfig(
                    steps =
                        listOf(
                            LoopStep(id = "step1", tool = "Step1Tool"),
                            LoopStep(id = "step2", tool = "Step2Tool", input = LoopStepInput(from = "step1")),
                        ),
                )
            val result = runner.run(config, context(listOf(step1Tool, step2Tool))) { true }
            result.shouldBeInstanceOf<LoopRunOutcome.Aborted>().reason shouldContain "step1"
        }

        "aborts when `from` references an unknown step id" {
            val tool = successTool("MyTool")
            val config =
                LoopConfig(
                    steps =
                        listOf(
                            LoopStep(id = "s1", tool = "MyTool", input = LoopStepInput(from = "nonexistent")),
                        ),
                )
            val result = runner.run(config, context(listOf(tool))) { true }
            result.shouldBeInstanceOf<LoopRunOutcome.Aborted>().reason shouldContain "nonexistent"
        }

        // -------------------------------------------------------------------------
        // Failure handling
        // -------------------------------------------------------------------------

        "aborts when a step tool returns failure" {
            val tool = failTool("MyTool", "tool error msg")
            val config = LoopConfig(steps = listOf(LoopStep(id = "s1", tool = "MyTool")))
            val result = runner.run(config, context(listOf(tool))) { true }
            val aborted = result.shouldBeInstanceOf<LoopRunOutcome.Aborted>()
            aborted.reason shouldContain "tool error msg"
        }

        "aborts when a step tool throws" {
            val tool =
                mockk<StandardTool<*>>().also {
                    every { it.name } returns "ThrowingTool"
                    coEvery { it.executeWithJson(any(), any()) } throws RuntimeException("boom")
                }
            val config = LoopConfig(steps = listOf(LoopStep(id = "s1", tool = "ThrowingTool")))
            val result = runner.run(config, context(listOf(tool))) { true }
            result.shouldBeInstanceOf<LoopRunOutcome.Aborted>().reason shouldContain "boom"
        }

        "does not execute subsequent steps after a failure" {
            val step1 = failTool("Step1")
            val step2 = successTool("Step2")
            val config =
                LoopConfig(
                    steps =
                        listOf(
                            LoopStep(id = "s1", tool = "Step1"),
                            LoopStep(id = "s2", tool = "Step2"),
                        ),
                )
            runner.run(config, context(listOf(step1, step2))) { true }
            coVerify(exactly = 0) { step2.executeWithJson(any(), any()) }
        }

        // -------------------------------------------------------------------------
        // Interruption
        // -------------------------------------------------------------------------

        "returns Interrupted when shouldContinue is false before first step" {
            val tool = successTool("MyTool")
            val config = LoopConfig(steps = listOf(LoopStep(id = "s1", tool = "MyTool")))
            val result = runner.run(config, context(listOf(tool))) { false }
            result.shouldBeInstanceOf<LoopRunOutcome.Interrupted>()
            coVerify(exactly = 0) { tool.executeWithJson(any(), any()) }
        }

        "returns Interrupted when shouldContinue returns false between steps" {
            val step1 = successTool("Step1")
            val step2 = successTool("Step2")
            var calls = 0
            val config =
                LoopConfig(
                    steps =
                        listOf(
                            LoopStep(id = "s1", tool = "Step1"),
                            LoopStep(id = "s2", tool = "Step2"),
                        ),
                )
            val result =
                runner.run(config, context(listOf(step1, step2))) {
                    calls++ == 0 // true for first check, false for second
                }
            result.shouldBeInstanceOf<LoopRunOutcome.Interrupted>()
            coVerify(exactly = 1) { step1.executeWithJson(any(), any()) }
            coVerify(exactly = 0) { step2.executeWithJson(any(), any()) }
        }

        // -------------------------------------------------------------------------
        // Completed outcome
        // -------------------------------------------------------------------------

        "returns Completed with last step output" {
            val tool = successTool("MyTool", output = "final output")
            val config = LoopConfig(steps = listOf(LoopStep(id = "s1", tool = "MyTool")))
            val result = runner.run(config, context(listOf(tool))) { true }
            result.shouldBeInstanceOf<LoopRunOutcome.Completed>().lastStepOutput shouldBe "final output"
        }

        "returns Completed with last step output when multiple steps" {
            val step1 = successTool("Step1", output = "first")
            val step2 = successTool("Step2", output = "second")
            val config =
                LoopConfig(
                    steps =
                        listOf(
                            LoopStep(id = "s1", tool = "Step1"),
                            LoopStep(id = "s2", tool = "Step2"),
                        ),
                )
            val result = runner.run(config, context(listOf(step1, step2))) { true }
            result.shouldBeInstanceOf<LoopRunOutcome.Completed>().lastStepOutput shouldBe "second"
        }
    })
