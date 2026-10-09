package io.whozoss.agentos.workflow

import java.util.UUID

/** Result of a [LoopWorkflowRunnerService.run]. */
sealed interface LoopRunOutcome {
    /** The run could not proceed at all; [reason] is shown to the user. */
    data class Aborted(
        val reason: String,
    ) : LoopRunOutcome

    /**
     * The SEARCH phase succeeded and the ACT phase went through the entities.
     *
     * @param returned        Number of entities on the processed search page.
     * @param launchedCaseIds Ids of the standalone cases launched, for traceability.
     * @param overLimit    Entities ignored because [maxItems] was reached.
     * @param interrupted  True when a kill/interrupt stopped the ACT phase early.
     */
    data class Completed(
        val searchTool: String,
        val returned: Int,
        val totalCount: Long?,
        val hasMorePages: Boolean,
        val launchedCaseIds: List<UUID>,
        val unknownUser: Int,
        val noAgentAccess: Int,
        val failed: Int,
        val overLimit: Int,
        val maxItems: Int,
        val targetAgent: String,
        val interrupted: Boolean,
    ) : LoopRunOutcome {
        val launched: Int get() = launchedCaseIds.size

        fun summary(): String =
            buildString {
                append("Launched $launched case(s) with '$targetAgent' out of $returned entity/entities ")
                append("returned by '$searchTool'")
                totalCount?.let { append(" (totalCount=$it)") }
                append('.')
                if (unknownUser > 0) append("\n- Skipped, no matching user: $unknownUser")
                if (noAgentAccess > 0) append("\n- Refused, user cannot access '$targetAgent': $noAgentAccess")
                if (failed > 0) append("\n- Failed to launch: $failed")
                if (overLimit > 0) append("\n- Not processed, over the limit of $maxItems per run: $overLimit")
                if (hasMorePages) append("\n- More results exist: only the first page was processed.")
                if (interrupted) append("\n- Interrupted before all entities were processed.")
                if (launchedCaseIds.isNotEmpty()) {
                    append("\n\nLaunched cases (first 10):")
                    launchedCaseIds.take(10).forEach { append("\n- $it") }
                    if (launchedCaseIds.size > 10) append("\n- ... and ${launchedCaseIds.size - 10} more")
                }
            }
    }
}
