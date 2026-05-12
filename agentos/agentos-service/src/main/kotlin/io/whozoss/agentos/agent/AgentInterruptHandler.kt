package io.whozoss.agentos.agent

import io.whozoss.agentos.sdk.agent.Agent
import io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent
import io.whozoss.agentos.sdk.caseEvent.AgentSelectedEvent
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.PendingConfirmationEvent
import io.whozoss.agentos.sdk.caseEvent.QuestionEvent
import io.whozoss.agentos.sdk.entity.EntityMetadata
import kotlinx.coroutines.flow.FlowCollector
import mu.KLogger
import java.util.UUID

/**
 * Emits the orchestration events for a structured agent interruption.
 *
 * The order of events differs per interrupt type:
 * - [AgentInterrupt.Redirect] : [AgentFinishedEvent] then [AgentSelectedEvent] (close current
 *   agent, signal next).
 * - [AgentInterrupt.AwaitConfirmation] : [PendingConfirmationEvent] then [QuestionEvent]
 *   (user-facing, out-of-LLM-channel) then [AgentFinishedEvent] (close current agent only
 *   after the question is visible, so the UI doesn't briefly show the case as IDLE before
 *   the question lands).
 *
 * The [when] is exhaustive over the sealed hierarchy: adding a new [AgentInterrupt] subtype
 * without handling it here is a compile error.
 *
 * @param agent The agent whose turn is ending.
 * @param e The interrupt signal thrown by a tool.
 * @param namespaceId Namespace of the current case.
 * @param caseId Current case identifier.
 * @param logger Logger of the calling agent, used to trace the redirect/await.
 */
suspend fun FlowCollector<CaseEvent>.emitInterruptEvents(
    agent: Agent,
    e: AgentInterrupt,
    namespaceId: UUID,
    caseId: UUID,
    logger: KLogger,
) {
    when (e) {
        is AgentInterrupt.Redirect -> {
            logger.info { "[${agent.name}] redirecting to '${e.targetAgentName}'" }
            emit(
                AgentFinishedEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    agentId = agent.id,
                    agentName = agent.name,
                ),
            )
            emit(
                AgentSelectedEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    agentId = UUID.nameUUIDFromBytes(e.targetAgentName.toByteArray()),
                    agentName = e.targetAgentName,
                ),
            )
        }
        is AgentInterrupt.AwaitConfirmation -> {
            logger.info { "[${agent.name}] awaiting confirmation for tool '${e.toolName}' (questionId=${e.questionId})" }
            emit(
                PendingConfirmationEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    toolRequestId = e.toolRequestId,
                    toolName = e.toolName,
                    pendingPayloadJson = e.pendingPayloadJson,
                    confirmationLabel = e.confirmationLabel,
                    analysisInstructions = e.analysisInstructions,
                    questionId = e.questionId,
                ),
            )
            emit(
                QuestionEvent(
                    metadata = EntityMetadata(id = e.questionId),
                    namespaceId = namespaceId,
                    caseId = caseId,
                    agentId = agent.id,
                    agentName = agent.name,
                    question = e.confirmationLabel,
                    options = CONFIRMATION_OPTIONS,
                ),
            )
            emit(
                AgentFinishedEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    agentId = agent.id,
                    agentName = agent.name,
                ),
            )
        }
    }
}

/** Fixed two-button options surfaced to the UI for a confirmation [QuestionEvent]. */
val CONFIRMATION_OPTIONS: List<String> = listOf(CONFIRMATION_ANSWER_CONFIRM, CONFIRMATION_ANSWER_REJECT)

const val CONFIRMATION_ANSWER_CONFIRM: String = "Confirmer"
const val CONFIRMATION_ANSWER_REJECT: String = "Annuler"
