package io.whozoss.agentos.agent

import io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent
import io.whozoss.agentos.sdk.caseEvent.AgentSelectedEvent
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.ErrorEvent
import kotlinx.coroutines.flow.FlowCollector
import mu.KLogger
import org.springframework.ai.retry.NonTransientAiException
import java.util.UUID

/**
 * Emits an [ErrorEvent] and the given [AgentFinishedEvent] when the LLM provider
 * rejects a request with a non-transient error (4xx).
 *
 * @param finishedEvent Pre-built event closing the agent's turn.
 * @param e The non-transient provider exception.
 * @param logger Logger of the calling agent.
 */
suspend fun FlowCollector<CaseEvent>.emitProviderErrorEvents(
    finishedEvent: AgentFinishedEvent,
    e: NonTransientAiException,
    logger: KLogger,
) {
    logger.error(e) { "LLM provider rejected request for case ${finishedEvent.caseId}" }
    emit(
        ErrorEvent(
            namespaceId = finishedEvent.namespaceId,
            caseId = finishedEvent.caseId,
            message = "The AI provider rejected the request and the agent cannot continue: ${e.message}",
        ),
    )
    emit(finishedEvent)
}

/**
 * Emits the given [AgentFinishedEvent] then the interrupt-specific follow-up events.
 *
 * The [when] is exhaustive over the [AgentInterrupt] sealed hierarchy: adding a new
 * subtype without handling it here is a compile error.
 *
 * @param finishedEvent Pre-built event closing the agent's turn.
 * @param e The interrupt signal thrown by a tool.
 * @param logger Logger of the calling agent, used to trace the redirect.
 */
suspend fun FlowCollector<CaseEvent>.emitInterruptEvents(
    finishedEvent: AgentFinishedEvent,
    e: AgentInterrupt,
    logger: KLogger,
) {
    emit(finishedEvent)
    when (e) {
        is AgentInterrupt.Redirect -> {
            logger.info { "[${finishedEvent.agentName}] redirecting to '${e.targetAgentName}'" }
            emit(
                AgentSelectedEvent(
                    namespaceId = finishedEvent.namespaceId,
                    caseId = finishedEvent.caseId,
                    agentId = UUID.nameUUIDFromBytes(e.targetAgentName.toByteArray()),
                    agentName = e.targetAgentName,
                ),
            )
        }
    }
}
