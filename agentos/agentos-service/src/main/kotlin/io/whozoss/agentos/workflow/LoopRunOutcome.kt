package io.whozoss.agentos.workflow

/** Result of a [LoopWorkflowRunnerService.run]. */
sealed interface LoopRunOutcome {
    /** The run could not proceed at all; [reason] is shown to the user. */
    data class Aborted(
        val reason: String,
    ) : LoopRunOutcome

    /**
     * All steps completed successfully.
     *
     * @param lastStepOutput The string output of the last step, if any.
     */
    data class Completed(
        val lastStepOutput: String?,
    ) : LoopRunOutcome

    /** The run was interrupted by [shouldContinue] returning false mid-loop. */
    data object Interrupted : LoopRunOutcome
}
