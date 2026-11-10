package io.whozoss.agentos.workflow

import io.whozoss.agentos.exception.BadRequestException

/**
 * Write-time validation of a [Workflow] definition: everything that can be checked without
 * running it. Each rule is a reason for a 400, so a broken definition never reaches a run.
 */
object WorkflowValidator {
    /**
     * Tools a workflow step may name: the tools granted to the scheduler loop agent.
     *
     * TODO(wz-35443): derive from the seeded scheduler-loop AgentConfig integrations once it is seeded.
     */
    val ALLOWED_TOOLS: Set<String> = setOf("ListAgentUsers", "BatchCaseStarter")

    /** The problems found in [workflow], empty when it is valid. */
    fun problems(workflow: Workflow): List<String> =
        buildList {
            if (workflow.name.isBlank()) add("name must not be blank")
            if (workflow.version != Workflow.CURRENT_VERSION) {
                add("version must be ${Workflow.CURRENT_VERSION}, got ${workflow.version}")
            }
            if (workflow.steps.isEmpty()) add("a workflow must declare at least one step")

            val seen = mutableSetOf<String>()
            workflow.steps.forEachIndexed { index, step ->
                val label = "step ${index + 1} ('${step.id}')"
                if (step.id.isBlank()) add("step ${index + 1}: id must not be blank")
                if (step.id in seen) add("$label: duplicate step id")
                if (step.tool !in ALLOWED_TOOLS) add("$label: unknown tool '${step.tool}' (allowed: ${ALLOWED_TOOLS.joinToString()})")
                step.input.from?.let { from ->
                    if (from !in seen) add("$label: 'from' must reference an earlier step, got '$from'")
                }
                step.input.params?.let { params ->
                    if (!params.isObject) add("$label: params must be a JSON object")
                }
                WorkflowRunBindings.problemsIn(step.input.params).forEach { add("$label: $it") }
                seen += step.id
            }
        }

    /** @throws BadRequestException listing every problem when [workflow] is invalid. */
    fun requireValid(workflow: Workflow) {
        val problems = problems(workflow)
        if (problems.isNotEmpty()) throw BadRequestException("Invalid workflow: " + problems.joinToString("; "))
    }
}
