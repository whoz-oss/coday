package io.whozoss.agentos.workflow

import io.whozoss.agentos.exception.BadRequestException

/**
 * Write-time validation of a [Workflow] definition. Each rule is a reason for a 400, so a broken
 * definition never reaches a run.
 *
 * The shape is fixed for now (handover D3): exactly two steps, a target search then
 * [Workflow.BATCH_CASE_STARTER_TOOL]. The search tool name is not checked against a catalog: Whoz
 * search tools are integrations whose names only the deployment knows (D7). A wrong name aborts
 * the run with a readable reason.
 */
object WorkflowValidator {
    /** The problems found in [workflow], empty when it is valid. */
    fun problems(workflow: Workflow): List<String> =
        buildList {
            if (workflow.name.isBlank()) add("name must not be blank")
            if (workflow.version != Workflow.CURRENT_VERSION) {
                add("version must be ${Workflow.CURRENT_VERSION}, got ${workflow.version}")
            }
            if (workflow.steps.size != 2) {
                add(
                    "a workflow must have exactly 2 steps (target search, then ${Workflow.BATCH_CASE_STARTER_TOOL}), " +
                        "got ${workflow.steps.size}",
                )
                return@buildList
            }
            workflow.steps.forEachIndexed { index, step ->
                if (step.id.isBlank()) add("step ${index + 1}: id must not be blank")
                if (step.tool.isBlank()) add("step ${index + 1}: tool must not be blank")
            }
            val (search, start) = workflow.steps
            if (search.id == start.id) add("step ids must be distinct")
            if (isBatchCaseStarter(search.tool)) add("step 1 must be a target search, not ${Workflow.BATCH_CASE_STARTER_TOOL}")
            if (!isBatchCaseStarter(start.tool)) add("step 2 must be ${Workflow.BATCH_CASE_STARTER_TOOL}, got '${start.tool}'")
        }

    /** @throws BadRequestException listing every problem when [workflow] is invalid. */
    fun requireValid(workflow: Workflow) {
        val problems = problems(workflow)
        if (problems.isNotEmpty()) throw BadRequestException("Invalid workflow: " + problems.joinToString("; "))
    }

    private fun isBatchCaseStarter(tool: String): Boolean =
        tool == Workflow.BATCH_CASE_STARTER_TOOL || tool.endsWith("__${Workflow.BATCH_CASE_STARTER_TOOL}")
}
