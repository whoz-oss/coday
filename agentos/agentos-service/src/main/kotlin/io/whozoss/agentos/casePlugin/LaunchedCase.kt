package io.whozoss.agentos.casePlugin

import java.util.UUID

/**
 * What [CaseLaunchService.start] returns: the case it started. It exists and its first turn has been handed to
 * the runtime, so it runs in the background.
 *
 * @param userId The user the case runs for: the caller, or the user the caller named.
 * @param agentName The canonical name of the agent that works on it, as deployed, not as the caller typed it.
 */
data class LaunchedCase(
    val caseId: UUID,
    val userId: UUID,
    val agentName: String,
)
