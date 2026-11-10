package io.whozoss.agentos.scheduledPrompt

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.workflow.JsonSchemaSubset
import io.whozoss.agentos.workflow.Workflow
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Validates a scheduled prompt's `workflowInput` against the input schema of its workflow's step-1
 * tool, at save time (handover D8), so a bad search criterion is a 400 instead of a failed run.
 */
fun interface WorkflowInputValidator {
    /**
     * @throws BadRequestException when [workflowInput] is not a JSON object, when the step-1 tool of
     *   [workflow] is not granted to the loop agent in [namespaceId], or when [workflowInput] does not
     *   match that tool's input schema.
     */
    fun requireValid(
        workflow: Workflow,
        workflowInput: JsonNode?,
        namespaceId: UUID?,
    )
}

/**
 * [WorkflowInputValidator] checking against the step-1 tool found by [LoopAgentToolCatalog], with
 * [JsonSchemaSubset]. Keys [ScheduledWorkflowWiring] supplies itself (e.g. `agentConfigId` for
 * `listAgentUsers`) are not required from the scheduled prompt.
 */
@Service
class ToolSchemaWorkflowInputValidator(
    private val loopAgentToolCatalog: LoopAgentToolCatalog,
) : WorkflowInputValidator {
    override fun requireValid(
        workflow: Workflow,
        workflowInput: JsonNode?,
        namespaceId: UUID?,
    ) {
        if (workflowInput != null && !workflowInput.isObject) throw BadRequestException("workflowInput must be a JSON object")
        val searchTool = workflow.steps.first().tool
        val tool =
            loopAgentToolCatalog.findTool(namespaceId, searchTool)
                ?: throw BadRequestException(
                    "Workflow '${workflow.name}': its first step tool '$searchTool' is not available to the scheduler " +
                        "loop agent in this namespace. Check the loop agent's integrations.",
                )
        val problems =
            JsonSchemaSubset.problems(
                value = workflowInput ?: mapper.createObjectNode(),
                schema = mapper.readTree(tool.inputSchema),
                ignoredRequired = ScheduledWorkflowWiring.injectedSearchKeys(searchTool),
            )
        if (problems.isNotEmpty()) {
            throw BadRequestException("Invalid workflowInput for '$searchTool': " + problems.joinToString("; "))
        }
    }

    private companion object {
        val mapper = jacksonObjectMapper()
    }
}
