package io.whozoss.agentos.workflow

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.string.shouldContain
import io.whozoss.agentos.exception.BadRequestException

class WorkflowValidatorUnitSpec :
    StringSpec({
        val mapper = jacksonObjectMapper()

        fun step(
            id: String,
            tool: String = "ListAgentUsers",
            from: String? = null,
            params: String? = null,
        ) = LoopStep(id = id, tool = tool, input = LoopStepInput(params = params?.let(mapper::readTree), from = from))

        fun workflow(vararg steps: LoopStep) = Workflow(name = "wf", steps = steps.toList())

        "the default workflow is valid" {
            WorkflowValidator.problems(DefaultWorkflowSeeder.defaultWorkflow()).shouldBeEmpty()
        }

        "a workflow without steps is refused" {
            WorkflowValidator.problems(workflow()).single() shouldContain "at least one step"
        }

        "an unsupported version is refused" {
            WorkflowValidator.problems(workflow(step("a")).copy(version = 2)).single() shouldContain "version"
        }

        "a blank name is refused" {
            WorkflowValidator.problems(workflow(step("a")).copy(name = " ")).single() shouldContain "name"
        }

        "duplicate step ids are refused" {
            WorkflowValidator.problems(workflow(step("a"), step("a"))).single() shouldContain "duplicate"
        }

        "an unknown tool is refused" {
            WorkflowValidator.problems(workflow(step("a", tool = "DeleteEverything"))).single() shouldContain "unknown tool"
        }

        "'from' must reference an earlier step" {
            WorkflowValidator.problems(workflow(step("a", from = "b"), step("b"))).single() shouldContain "earlier step"
            WorkflowValidator.problems(workflow(step("a", from = "a"))).single() shouldContain "earlier step"
        }

        "params must be a JSON object" {
            WorkflowValidator.problems(workflow(step("a", params = "[1, 2]"))).single() shouldContain "JSON object"
        }

        "an unknown binding is refused" {
            WorkflowValidator.problems(workflow(step("a", params = """{"x": "{{run.password}}"}"""))).single() shouldContain
                "unknown binding"
        }

        "a partial binding is refused" {
            WorkflowValidator.problems(workflow(step("a", params = """{"x": "Hello {{run.agentName}}"}"""))).single() shouldContain
                "whole value"
        }

        "every problem is reported, and requireValid throws a 400" {
            val invalid = workflow(step("a", tool = "Nope", from = "z"))
            WorkflowValidator.problems(invalid) shouldHaveSize 2
            shouldThrow<BadRequestException> { WorkflowValidator.requireValid(invalid) }.message shouldContain "Nope"
        }
    })
