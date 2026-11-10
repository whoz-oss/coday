package io.whozoss.agentos.scheduledPrompt

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.userGroup.ListAgentUsersTool
import io.whozoss.agentos.workflow.LoopConfig
import io.whozoss.agentos.workflow.LoopStep
import io.whozoss.agentos.workflow.ToolReference
import io.whozoss.agentos.workflow.Workflow
import io.whozoss.agentos.workflow.WorkflowTargets
import java.util.UUID

/**
 * The single place deciding how the runtime values of a scheduled run reach the two steps of its
 * [Workflow] (handover D3: mapping hardcoded this sprint, no expression language).
 *
 * | step | tool | input |
 * |---|---|---|
 * | 1 target search | the workflow's step-1 tool | the scheduled prompt's `workflowInput` (+ `agentConfigId` for `listAgentUsers`) |
 * | 2 case start | `BatchCaseStarter` | agent, prompt, title, scheduled prompt, run, plus `targets` from step 1 |
 *
 * `agentConfigId` is given to step 1 **only when it is `listAgentUsers`** (the user-group workflow,
 * whose audience is the agent's deployment audience, D6). It is never added to other search tools:
 * they validate their own input strictly and would reject an unknown key. See [injectedSearchKeys].
 *
 * Pure function of its inputs: no repository, no Spring. The resolver gathers the values.
 */
object ScheduledWorkflowWiring {
    private val mapper = jacksonObjectMapper()

    /**
     * The step-1 input keys this wiring supplies itself for [searchTool], so a scheduled prompt does not
     * have to: `agentConfigId` for `listAgentUsers`, nothing otherwise. Used by
     * [ScheduledWorkflowInputValidator] to not require them in `workflowInput`.
     */
    fun injectedSearchKeys(searchTool: String): Set<String> =
        if (isListAgentUsers(searchTool)) setOf(AGENT_CONFIG_ID) else emptySet()

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
                    LoopStep(id = search.id, tool = search.tool, input = searchInput(search.tool, values), feedsNext = WorkflowTargets.FIELD),
                    LoopStep(id = start.id, tool = start.tool, input = startInput(values)),
                ),
        )
    }

    /** `workflowInput`, plus the keys of [injectedSearchKeys]; a `workflowInput` key of the same name wins. */
    private fun searchInput(
        searchTool: String,
        values: RunValues,
    ): JsonNode =
        mapper.createObjectNode().apply {
            if (AGENT_CONFIG_ID in injectedSearchKeys(searchTool)) put(AGENT_CONFIG_ID, values.agentConfigId.toString())
            (values.workflowInput as? ObjectNode)?.let { setAll<JsonNode>(it) }
        }

    private fun isListAgentUsers(tool: String): Boolean = ToolReference.refersTo(tool, ListAgentUsersTool.TOOL_NAME)

    private const val AGENT_CONFIG_ID = "agentConfigId"

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
