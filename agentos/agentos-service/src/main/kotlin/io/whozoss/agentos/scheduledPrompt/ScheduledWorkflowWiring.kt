package io.whozoss.agentos.scheduledPrompt

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.workflow.LoopConfig
import io.whozoss.agentos.workflow.LoopStep
import io.whozoss.agentos.workflow.Workflow
import io.whozoss.agentos.workflow.WorkflowTargets
import java.util.UUID

/**
 * The single place deciding how the runtime values of a scheduled run reach the two steps of its
 * [Workflow] (handover D3: mapping hardcoded this sprint, no expression language).
 *
 * | step | tool | input |
 * |---|---|---|
 * | 1 target search | the workflow's step-1 tool | the scheduled prompt's `workflowInput`, plus `agentConfigId` |
 * | 2 case start | `BatchCaseStarter` | agent, prompt, title, scheduled prompt, run, plus `targets` from step 1 |
 *
 * `agentConfigId` is always given to step 1: the user-group workflow (`listAgentUsers`) needs it to
 * find the agent's deployment audience (D6); a search tool that does not declare it ignores it.
 * A `workflowInput` key of the same name wins.
 *
 * Pure function of its inputs: no repository, no Spring. The resolver gathers the values.
 */
object ScheduledWorkflowWiring {
    private val mapper = jacksonObjectMapper()

    /** Runtime values of one scheduled run, gathered by [ScheduledPromptLoopConfigResolver]. */
    data class RunValues(
        val agentConfigId: UUID,
        val agentName: String,
        val promptId: UUID,
        val title: String,
        val scheduledPromptId: UUID,
        val runId: UUID,
        val workflowInput: JsonNode?,
    )

    /**
     * The [LoopConfig] the loop agent runs for [workflow] with [values].
     *
     * @throws IllegalArgumentException when the workflow does not have its two steps (it is validated
     *   at write time, so this only guards against a definition persisted before validation).
     */
    fun toLoopConfig(
        workflow: Workflow,
        values: RunValues,
    ): LoopConfig {
        require(workflow.steps.size == 2) { "Workflow '${workflow.name}' must have exactly 2 steps, has ${workflow.steps.size}" }
        val (search, start) = workflow.steps
        return LoopConfig(
            steps =
                listOf(
                    LoopStep(id = search.id, tool = search.tool, input = searchInput(values), feedsNext = WorkflowTargets.FIELD),
                    LoopStep(id = start.id, tool = start.tool, input = startInput(values)),
                ),
        )
    }

    private fun searchInput(values: RunValues): JsonNode =
        mapper.createObjectNode().apply {
            put("agentConfigId", values.agentConfigId.toString())
            (values.workflowInput as? ObjectNode)?.let { setAll<JsonNode>(it) }
        }

    private fun startInput(values: RunValues): JsonNode =
        mapper.valueToTree(
            mapOf(
                "agentName" to values.agentName,
                "promptId" to values.promptId.toString(),
                "title" to values.title,
                "scheduledPromptId" to values.scheduledPromptId.toString(),
                "runId" to values.runId.toString(),
            ),
        )
}
