package io.whozoss.agentos.agent

import io.whozoss.agentos.chat.AiProviderErrorProperties
import io.whozoss.agentos.sdk.agent.Agent
import io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent
import io.whozoss.agentos.sdk.caseEvent.AgentSelectedEvent
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.ErrorEvent
import io.whozoss.agentos.sdk.caseEvent.QuestionEvent
import io.whozoss.agentos.sdk.caseEvent.WarnEvent
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.util.unwrapToProviderAiException
import kotlinx.coroutines.flow.FlowCollector
import mu.KLogger
import org.springframework.ai.retry.NonTransientAiException
import org.springframework.ai.retry.TransientAiException
import java.util.UUID

/**
 * Formats the message of a provider [ErrorEvent]: [headline], the exception message ([detail]: status line, endpoint,
 * and the provider response body when it was exposed) and the compact request summary. The single point of
 * formatting of these events.
 */
private fun providerErrorMessage(
    headline: String,
    detail: String?,
    summary: ProviderRequestSummary,
): String = "$headline: $detail — request: ${summary.render()}"

/**
 * Emits an [ErrorEvent] and [AgentFinishedEvent] when the LLM provider rejects a request
 * with a non-transient error (4xx). Retrying with the same payload would produce the
 * same result, so the run is terminated immediately rather than looping.
 *
 * The event message is the exception message followed by the [requestSummary] (by default the agent's provider and
 * model only). The provider response body is part of it only when the exception already carries it: built by
 * [handleGenericAgentException] on the streaming path according to its `exposeResponseBody` flag, or by Spring AI
 * itself on the blocking path, which AgentOS cannot switch off (see [io.whozoss.agentos.chat.AiProviderErrorProperties]).
 * Nothing is appended here, so the body is never shown twice.
 */
suspend fun FlowCollector<CaseEvent>.emitProviderErrorAndFinishEvents(
    agent: Agent,
    e: NonTransientAiException,
    namespaceId: UUID,
    caseId: UUID,
    logger: KLogger,
    requestSummary: ProviderRequestSummary = ProviderRequestSummary(agent.llmProvider, agent.llmModel),
) {
    logger.error(e) { "LLM provider rejected request for case $caseId" }
    emit(
        ErrorEvent(
            namespaceId = namespaceId,
            caseId = caseId,
            message =
                providerErrorMessage(
                    "The AI provider rejected the request and the agent cannot continue",
                    e.message,
                    requestSummary,
                ),
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
 * Emits the warning both execution modes share when a run stops at the iteration limit
 * (`agentos.limits.agent-max-iterations`), before its final answer.
 */
suspend fun FlowCollector<CaseEvent>.emitMaxIterationsWarning(
    namespaceId: UUID,
    caseId: UUID,
    maxIterations: Int,
) {
    emit(
        WarnEvent(
            namespaceId = namespaceId,
            caseId = caseId,
            message = "Agent reached maximum iterations ($maxIterations) without completing",
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
 * [TransientAiException] for 5xx). When [exposeResponseBody] is true
 * (`agentos.ai-provider-errors.expose-response-body`), the provider response body is part of the
 * propagated message and event, trimmed, bounded and with secrets masked; when false it is available
 * only in diagnostic DEBUG logs. The event always ends with the [requestSummary] (by default the
 * agent's provider and model only), never with the raw request.
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
    requestSummary: ProviderRequestSummary = ProviderRequestSummary(agent.llmProvider, agent.llmModel),
    exposeResponseBody: Boolean = AiProviderErrorProperties().exposeResponseBody,
) {
    if (generateSequence<Throwable>(e) { it.cause }.any { it is io.whozoss.agentos.usage.CostRunStopped }) {
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
        return
    }
    when (val providerException = e.unwrapToProviderAiException(exposeResponseBody)) {
        is NonTransientAiException ->
            emitProviderErrorAndFinishEvents(agent, providerException, namespaceId, caseId, logger, requestSummary)
        is TransientAiException -> {
            // 5xx — provider-side failure, potentially transient.
            // Emit ErrorEvent (same lifecycle termination) but log at WARN since a retry may succeed.
            logger.warn(e) { "LLM provider returned a transient error for case $caseId" }
            emit(
                ErrorEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    message =
                        providerErrorMessage(
                            "The AI provider returned a transient error and the agent cannot continue",
                            providerException.message,
                            requestSummary,
                        ),
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
                    metadata = EntityMetadata(id = e.questionId),
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
