package io.whozoss.agentos.workflow

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.whozoss.agentos.sdk.entity.Entity
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID

/**
 * A reusable definition of what the loop agent runs for a scheduled prompt: **which tools, in
 * which order**. It carries no parameters: those are runtime values supplied by the scheduled
 * prompt ([io.whozoss.agentos.scheduledPrompt.ScheduledPrompt.workflowInput], agent, prompt) and
 * by the run. See `docs/study/HANDOVER-wz-35443.md` (D2, D3).
 *
 * A workflow has exactly two steps (enforced by [WorkflowValidator]):
 * 1. **target search**: a tool whose structured output follows the [WorkflowTargets] contract,
 *    fed with the scheduled prompt's `workflowInput`. `listAgentUsers` for the platform user-group
 *    workflow, Whoz search tools for the workflows Whoz creates.
 * 2. **case start**: [BATCH_CASE_STARTER_TOOL], fed with the scheduled prompt and the targets.
 *
 * How runtime values reach each step is decided in one place:
 * [io.whozoss.agentos.scheduledPrompt.ScheduledWorkflowWiring].
 *
 * Scope: `namespaceId == null` is a platform workflow, otherwise a namespace one. Names are unique
 * per scope; a namespace workflow may reuse a platform name, and then wins when referenced by name
 * from that namespace ([WorkflowService.findByName]).
 */
data class Workflow(
    override val metadata: EntityMetadata = EntityMetadata(),
    val namespaceId: UUID? = null,
    val name: String,
    val description: String? = null,
    val version: Int = CURRENT_VERSION,
    val steps: List<WorkflowStep>,
) : Entity {
    companion object {
        const val CURRENT_VERSION = 1

        /**
         * Stable name of the platform user-group workflow seeded by [DefaultWorkflowSeeder], and the
         * default of a scheduled prompt that names no workflow. A **contract** with the front, which
         * references predefined workflows by name (handover D5): never rename it.
         */
        const val USER_GROUP_WORKFLOW_NAME = "user-group"

        /** Bare name of the step-2 tool. A step may name it bare or prefixed (`CASE__BatchCaseStarter`). */
        const val BATCH_CASE_STARTER_TOOL = "BatchCaseStarter"
    }
}

/**
 * One step of a [Workflow]: a tool, named as it is granted to the loop agent, bare (`listAgentUsers`)
 * or prefixed by its integration config (`USER_GROUP__listAgentUsers`). Names are case-sensitive.
 *
 * Unknown properties are ignored so that definitions persisted before parameters were removed
 * from steps still load.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class WorkflowStep(
    val id: String,
    val tool: String,
)
