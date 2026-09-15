package io.whozoss.agentos.agent

import io.whozoss.agentos.sdk.agent.Agent
import io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent
import io.whozoss.agentos.sdk.caseEvent.AgentSelectedEvent
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.ErrorEvent
import io.whozoss.agentos.sdk.caseEvent.QuestionEvent
import io.whozoss.agentos.sdk.caseEvent.WarnEvent
import io.whozoss.agentos.util.unwrapToProviderAiException
import kotlinx.coroutines.flow.FlowCollector
import mu.KLogger
import org.springframework.ai.retry.NonTransientAiException
import org.springframework.ai.retry.TransientAiException
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
 * Handles any [Exception] that escapes the agent's main loop by first checking whether
 * it wraps a provider HTTP error on the streaming path (a [WebClientResponseException]
 * buried under Reactor envelopes).
 *
 * If [e] or any cause in its chain is a [WebClientResponseException], it is translated
 * to the appropriate Spring AI exception ([NonTransientAiException] for 4xx,
 * [TransientAiException] for 5xx) so the response body surfaces in logs and in the
 * [ErrorEvent] — exactly as it does on the blocking path.
 *
 * If [e] is not a provider HTTP error, the function falls back to the original generic
 * behaviour: log at ERROR level and emit a [WarnEvent].
 *
 * This function is the single point of streaming-path exception enrichment. Every agent
 * that follows the `catch (e: NonTransientAiException) { … } catch (e: Exception) { … }`
 * pattern must delegate the generic catch to this function so future agents automatically
 * benefit without needing to know about [WebClientResponseException].
 */
suspend fun FlowCollector<CaseEvent>.handleGenericAgentException(
    agent: Agent,
    e: Exception,
    namespaceId: UUID,
    caseId: UUID,
    logger: KLogger,
) {
    when (val providerException = e.unwrapToProviderAiException()) {
        is NonTransientAiException -> emitProviderErrorAndFinishEvents(agent, providerException, namespaceId, caseId, logger)
        is TransientAiException -> {
            // 5xx — provider-side failure, potentially transient.
            // Emit ErrorEvent (same lifecycle termination) but log at WARN since a retry may succeed.
            logger.warn(e) { "LLM provider returned a transient error for case $caseId" }
            emit(
                ErrorEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    message = "The AI provider returned a transient error and the agent cannot continue: ${providerException.message}",
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
        else -> {
            // Not a provider HTTP error — preserve the original generic behaviour.
            logger.error(e) { "Error during agent execution" }
            emit(
                WarnEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    message = "Error during agent execution: ${e.message}",
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
    }
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

        is AgentInterrupt.AwaitAnswer -> {
            logger.info { "[${agent.name}] awaiting user answer: ${e.question}" }
            emit(
                QuestionEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    agentId = agent.id,
                    agentName = agent.name,
                    question = e.question,
                    options = e.options,
                    questionType = e.questionType,
                    // userId is the user for whom the agent is running — the one whose answer
                    // is awaited. Null means the question is addressed to any user of the case.
                    // The value comes from AgentInterrupt.AwaitAnswer, which receives it from
                    // ToolContext.userId (set by AgentSimple/AgentAdvanced from their own userId
                    // constructor parameter). No fallback: null in → null out.
                    userId = e.userId,
                ),
            )
        }
    }
}
