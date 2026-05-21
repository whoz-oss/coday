package io.whozoss.agentos.agent

import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import java.util.UUID

/**
 * Execution context for an agent run.
 *
 * Carries the identifiers that define *where* and *for whom* an agent is running.
 * Passed at agent instantiation time so the agent is built with full awareness of
 * its execution environment — namespace context injected into system instructions,
 * tool resolution scoped to the namespace and user, etc.
 *
 * @param caseEventsProvider Returns the live event list of the current case at the moment
 *   of invocation. Evaluated lazily so tool calls during a single agent run see events
 *   produced by prior tool calls in the same turn (e.g. a read before a write).
 */
data class AgentExecutionContext(
    val namespaceId: UUID,
    val caseId: UUID,
    val userId: UUID? = null,
    val caseEventsProvider: () -> List<CaseEvent> = { emptyList() },
    val authorizedAgentNames: Set<String> = emptySet(),
)
