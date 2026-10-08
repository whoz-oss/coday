package io.whozoss.agentos.chat

import io.whozoss.agentos.config.LimitsConfigProperties
import io.whozoss.agentos.config.UsageConfigProperties
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import org.springframework.ai.chat.client.ChatClient
import org.springframework.stereotype.Service

/**
 * Creates [ChatClient] instances on demand from a resolved [AiModel] + [AiProvider] pair.
 *
 * The two entities carry everything [ChatModelFactory] needs:
 * - provider connectivity ([AiProvider.apiType], [AiProvider.baseUrl], [AiProvider.apiKey])
 * - model identity and inference parameters ([AiModel.apiModelName],
 *   [AiModel.temperature], [AiModel.maxCompletionTokens])
 *
 * Resolution of which model/provider pair to use is the responsibility of the caller
 * (currently [io.whozoss.agentos.agent.AgentServiceImpl]).
 *
 * When usage is enabled, tracking is applied to the model so every call/stream terminal and internal tool
 * round goes through accounting and the cost gate. A cost reported by the provider (e.g. Requesty
 * `usage.cost`) replaces the estimate computed from [AiModel.pricing] for that request. Clients outside agent execution
 * (no accumulator) retain their existing behaviour.
 */
@Service
class ChatClientProvider(
    private val chatModelFactory: ChatModelFactory,
    private val limits: LimitsConfigProperties = LimitsConfigProperties(),
    private val usageConfig: UsageConfigProperties = UsageConfigProperties(),
) {
    fun getChatClient(
        modelConfig: AiModel,
        providerConfig: AiProvider,
        caseId: String? = null,
        accumulator: UsageAccumulator? = null,
    ): ChatClient {
        val tracked = usageConfig.enabled && accumulator != null
        val providerCost = if (tracked) ProviderReportedCost() else null
        val chatModel =
            chatModelFactory.createChatModel(
                apiType = providerConfig.apiType,
                baseUrl = providerConfig.baseUrl,
                apiKey = providerConfig.apiKey,
                modelName = modelConfig.apiModelName,
                temperature = modelConfig.temperature,
                maxCompletionTokens = modelConfig.maxCompletionTokens,
                headers = providerConfig.headers + (caseId?.let { mapOf(X_SESSION_ID to it) } ?: emptyMap()),
                providerCost = providerCost,
            )
        val trackedModel =
            if (tracked) {
                UsageTrackingChatModel(
                    chatModel,
                    accumulator!!,
                    providerConfig.apiType,
                    modelConfig,
                    maxToolRounds = limits.agentMaxIterations,
                    providerCost = providerCost,
                )
            } else {
                chatModel
            }
        return ChatClient.builder(trackedModel).build()
    }

    companion object {
        const val X_SESSION_ID = "X-Session-Id"
    }
}
