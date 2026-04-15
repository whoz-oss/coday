package io.whozoss.agentos.agent

import java.util.UUID

/**
 * Execution context for an agent run.
 *
 * Carries the identifiers that define *where* and *for whom* an agent is running.
 * Passed at agent instantiation time so the agent is built with full awareness of
 * its execution environment — namespace context injected into system instructions,
 * tool resolution scoped to the namespace and user, etc.
 */
data class AgentExecutionContext(
    val namespaceId: UUID,
    val caseId: UUID,
    val userId: UUID? = null,
    val callerDisplayName: String? = null,
)
