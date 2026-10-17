package io.whozoss.agentos.casePlugin

import java.util.UUID

/** What [CaseLaunchService.launch] returns: a launched case, or a business refusal. A failure is thrown. */
sealed interface CaseLaunchOutcome {
    /** The case exists and its first turn has been handed to the runtime; it runs in the background. */
    data class Launched(
        val caseId: UUID,
        val userId: UUID,
        val agentName: String,
    ) : CaseLaunchOutcome

    /**
     * A business refusal, decided before anything is written: nothing was created. [message] is safe to
     * show to the LLM.
     */
    data class Refused(
        val reason: CaseLaunchRefusal,
        val message: String,
    ) : CaseLaunchOutcome
}
