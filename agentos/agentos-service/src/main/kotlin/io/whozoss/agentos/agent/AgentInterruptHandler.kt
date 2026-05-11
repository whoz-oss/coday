package io.whozoss.agentos.agent

import io.whozoss.agentos.sdk.agent.Agent
import io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent
import io.whozoss.agentos.sdk.caseEvent.AgentSelectedEvent
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import kotlinx.coroutines.flow.FlowCollector
import mu.KLogger
import java.util.UUID

/**
 * Emits the orchestration events for a structured agent interruption.
 *
 * Always emits [AgentFinishedEvent] to close the current agent's turn, then emits
 * the interrupt-specific follow-up event(s) — e.g. [AgentSelectedEvent] for a
 * [AgentInterrupt.Redirect]. The [when] is exhaustive over the sealed hierarchy:
 * adding a new [AgentInterrupt] subtype without handling it here is a compile error.
 *
 * @param agent The agent whose turn is ending.
 * @param e The interrupt signal thrown by a tool.
 * @param namespaceId Namespace of the current case.
 * @param caseId Current case identifier.
 * @param logger Logger of the calling agent, used to trace the redirect.
 */
suspend fun FlowCollector<CaseEvent>.emitInterruptEvents(
    agent: Agent,
    e: AgentInterrupt,
    namespaceId: UUID,
    caseId: UUID,
    logger: KLogger,
) {
    emit(
        AgentFinishedEvent(
            namespaceId = namespaceId,
            caseId = caseId,
            agentId = agent.id,
            agentName = agent.name,
        ),
    )
    when (e) {
        is AgentInterrupt.Redirect -> {
            logger.info { "[${agent.name}] redirecting to '${e.targetAgentName}'" }
            emit(
                AgentSelectedEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    agentId = UUID.nameUUIDFromBytes(e.targetAgentName.toByteArray()),
                    agentName = e.targetAgentName,
                ),
            )
        }
    }
}
