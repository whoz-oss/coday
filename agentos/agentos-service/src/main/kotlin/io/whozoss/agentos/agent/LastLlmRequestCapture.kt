package io.whozoss.agentos.agent

import io.whozoss.agentos.chat.LlmExchange
import io.whozoss.agentos.chat.LlmExchangeRecorder
import io.whozoss.agentos.chat.LlmRequestOptions
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.sdk.api.user.PromptCacheTtl
import java.util.UUID

/**
 * Connects one run of an [AgentSimple] to the [LastLlmRequestRegistry]: [recorder] goes to its chat client
 * ([io.whozoss.agentos.chat.ChatClientProvider.getChatClient] `exchangeRecorder`), [lastExchange] and
 * [onFinalRequest] to the agent. Only for a tracked chat model: an untracked one records no exchange, and without
 * the exact request nothing is registered.
 *
 * Made when the run starts: the generation of the case it reads then must still be current when the turn ends, or
 * the capture is dropped (the case was killed, deleted, or another run started meanwhile).
 */
class LastLlmRequestCapture(
    private val registry: LastLlmRequestRegistry,
    private val namespaceId: UUID,
    private val userId: UUID?,
    private val model: AiModel,
    private val provider: AiProvider,
    private val agentName: String,
    private val agentConfigId: UUID?,
    /** The generation of the case when the run started ([LastLlmRequestRegistry.generation]). */
    private val generation: Long,
    /** The prompt cache duration the run's requests are sent with. */
    private val promptCacheTtl: PromptCacheTtl = PromptCacheTtl.STANDARD,
) {
    /** Dates its requests with the clock of the registry. */
    val recorder = LlmExchangeRecorder(registry::now)

    fun lastExchange(): LlmExchange? = recorder.lastExchange

    fun onFinalRequest(request: FinalLlmRequest) {
        registry.put(
            LastLlmRequest(
                caseId = request.caseId,
                namespaceId = namespaceId,
                userId = userId,
                messages = request.messages,
                toolDefinitions = request.toolDefinitions,
                modelId = model.id,
                providerId = model.aiProviderId,
                requestOptions = LlmRequestOptions.of(model, provider),
                finalText = request.finalText,
                agentName = agentName,
                agentConfigId = agentConfigId,
                coveredEventCount = request.coveredEventCount,
                lastCoveredEventId = request.lastCoveredEventId,
                promptCacheTtl = promptCacheTtl,
                requestSentAt = request.sentAt,
            ),
            generation,
        )
    }
}
