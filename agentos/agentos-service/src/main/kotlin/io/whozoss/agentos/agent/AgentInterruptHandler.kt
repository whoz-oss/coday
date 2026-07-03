package io.whozoss.agentos.agent

import io.whozoss.agentos.sdk.agent.Agent
import io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent
import io.whozoss.agentos.sdk.caseEvent.AgentSelectedEvent
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.ErrorEvent
import kotlinx.coroutines.flow.FlowCollector
import mu.KLogger
import org.springframework.ai.retry.NonTransientAiException
import java.util.UUID

/**
 * Emits an [ErrorEvent] and [AgentFinishedEvent] when the LLM provider rejects a request
 * with a non-transient error (4xx). Retrying with the same payload would produce the
 * same result, so the run is terminated immediately rather than looping.
 */
suspend fun FlowCollector<CaseEvent>.emitProviderErrorAndFinishEvents(
    agent: Agent,
    e: NonTransientAiException,
    namespaceId: UUID,
    caseId: UUID,
    logger: KLogger,
) {
    logger.error(e) { "LLM provider rejected request for case $caseId" }
    emit(
        ErrorEvent(
            namespaceId = namespaceId,
            caseId = caseId,
            message = "The AI provider rejected the request and the agent cannot continue: ${e.message}",
        ),
    )
    emit(
        AgentFinishedEvent(
            namespaceId = namespaceId,
            caseId = caseId,
            agentId = agent.id,
            agentName = agent.name,
            llmProvider = agent.llmProvider,
            llmModel = agent.llmModel,
        ),
    )
}

/**
 * Emits [AgentFinishedEvent] to close the current agent's turn, then emits
 * the interrupt-specific follow-up events.
 *
 * The [when] is exhaustive over the [AgentInterrupt] sealed hierarchy: adding a new
 * subtype without handling it here is a compile error.
 */
suspend fun FlowCollector<CaseEvent>.emitInterruptAndFinishEvents(
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
            llmProvider = agent.llmProvider,
            llmModel = agent.llmModel,
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
