package io.whozoss.agentos.workflow

import io.whozoss.agentos.sdk.entity.Entity
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID

/**
 * A reusable, strictly linear sequence of tool steps executed by the loop agent.
 *
 * A [io.whozoss.agentos.scheduledPrompt.ScheduledPrompt] references one Workflow: the scheduled
 * prompt says *when* and *what* (its Prompt), the Workflow says *how* and *to whom*.
 *
 * Scope:
 * - `namespaceId == null` → platform workflow (visible everywhere, super-admin write only).
 *   The seeded default workflow is a platform workflow.
 * - `namespaceId != null` → namespace workflow, configured by the namespace's administrators.
 *
 * [steps] reuse the [LoopStep] model executed by [LoopWorkflowRunnerService]: there is a single
 * step model, persisted here and run there. Step params may contain run bindings
 * (`{{run.agentConfigId}}` …), substituted when a run resolves its effective [LoopConfig].
 */
data class Workflow(
    override val metadata: EntityMetadata = EntityMetadata(),
    val namespaceId: UUID? = null,
    val name: String,
    val description: String? = null,
    val version: Int = CURRENT_VERSION,
    val steps: List<LoopStep>,
) : Entity {
    /** The definition as the runner consumes it, before run bindings are substituted. */
    fun toLoopConfig(): LoopConfig = LoopConfig(version = version, steps = steps)

    companion object {
        const val CURRENT_VERSION = 1
    }
}
