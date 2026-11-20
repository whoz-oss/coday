package io.whozoss.agentos.agent

import io.whozoss.agentos.chat.LlmExchange
import io.whozoss.agentos.chat.LlmExchangeRecorder
import io.whozoss.agentos.chat.LlmRequestOptions
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import java.util.UUID

/**
 * Connects one run of an [AgentSimple] to the [LastLlmRequestRegistry]: [recorder] goes to its chat client
 * ([io.whozoss.agentos.chat.ChatClientProvider.getChatClient] `exchangeRecorder`), [lastExchange] and
 * [onFinalRequest] to the agent. Only for a tracked chat model: an untracked one records no exchange, and without
 * the exact request nothing is registered.
 */
class LastLlmRequestCapture(
    private val registry: LastLlmRequestRegistry,
    private val namespaceId: UUID,
    private val userId: UUID?,
    private val model: AiModel,
    private val provider: AiProvider,
    private val agentName: String,
    private val agentConfigId: UUID?,
) {
    val recorder = LlmExchangeRecorder()

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
            ),
        )
    }
}
