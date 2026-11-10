package io.whozoss.agentos.workflow

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.string.shouldContain
import io.whozoss.agentos.exception.BadRequestException

class WorkflowValidatorUnitSpec :
    StringSpec({
        fun workflow(vararg steps: WorkflowStep) = Workflow(name = "wf", steps = steps.toList())

        val search = WorkflowStep(id = "search", tool = "WHOZ__SearchTalents")
        val start = WorkflowStep(id = "start", tool = "CASE__BatchCaseStarter")

        "the default user-group workflow is valid" {
            WorkflowValidator.problems(DefaultWorkflowSeeder.defaultWorkflow()).shouldBeEmpty()
        }

        "a search step then a prefixed or bare BatchCaseStarter is valid" {
            WorkflowValidator.problems(workflow(search, start)).shouldBeEmpty()
            WorkflowValidator.problems(workflow(search, start.copy(tool = "BatchCaseStarter"))).shouldBeEmpty()
        }

        "anything but exactly two steps is refused" {
            WorkflowValidator.problems(workflow()).single() shouldContain "exactly 2 steps"
            WorkflowValidator.problems(workflow(search)).single() shouldContain "exactly 2 steps"
            WorkflowValidator.problems(workflow(search, start, start.copy(id = "again"))).single() shouldContain "exactly 2 steps"
        }

        "step 2 must be BatchCaseStarter and step 1 must not" {
            WorkflowValidator.problems(workflow(search, search.copy(id = "other"))).single() shouldContain "step 2 must be"
            WorkflowValidator.problems(workflow(start.copy(id = "first"), start)).single() shouldContain "step 1 must be a target search"
        }

        "blank or duplicate ids and blank tools are refused" {
            WorkflowValidator.problems(workflow(search.copy(id = " "), start)).single() shouldContain "id must not be blank"
            WorkflowValidator.problems(workflow(search.copy(id = "x"), start.copy(id = "x"))).single() shouldContain "distinct"
            WorkflowValidator.problems(workflow(search.copy(tool = ""), start)) shouldContainProblem "tool must not be blank"
        }

        "an unsupported version or a blank name is refused" {
            WorkflowValidator.problems(workflow(search, start).copy(version = 2)).single() shouldContain "version"
            WorkflowValidator.problems(workflow(search, start).copy(name = " ")).single() shouldContain "name"
        }

        "requireValid throws a 400 listing the problems" {
            shouldThrow<BadRequestException> { WorkflowValidator.requireValid(workflow(search)) }.message shouldContain "exactly 2 steps"
        }
    })

private infix fun List<String>.shouldContainProblem(fragment: String) {
    if (none { fragment in it }) throw AssertionError("Expected a problem containing '$fragment', got $this")
}
