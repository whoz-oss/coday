package io.whozoss.agentos.chat

import io.whozoss.agentos.sdk.aiProvider.AiApiType
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
 *   [AiModel.temperature], [AiModel.maxTokens])
 *
 * Resolution of which model/provider pair to use is the responsibility of the caller
 * (currently [io.whozoss.agentos.agent.AgentServiceImpl]).
 *
 * ## Usage tracking
 *
 * When a [UsageAccumulator] is supplied, the returned [ChatClient] is wrapped in a
 * [UsageTrackingChatClient] that intercepts every completed call/stream response and
 * records the token usage into the accumulator. The accumulator is then read by
 * [io.whozoss.agentos.agent.AgentServiceImpl] at the end of each agent run to attach
 * a [io.whozoss.agentos.sdk.usage.LlmUsage] snapshot to the
 * [io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent].
 *
 * When [accumulator] is null (e.g. in tests that do not supply one), the raw
 * [ChatClient] is returned without any tracking wrapper.
 */
@Service
class ChatClientProvider(
    private val chatModelFactory: ChatModelFactory,
) {
    fun getChatClient(
        modelConfig: AiModel,
        providerConfig: AiProvider,
        caseId: String? = null,
        accumulator: UsageAccumulator? = null,
    ): ChatClient {
        val chatModel =
            chatModelFactory.createChatModel(
                apiType = providerConfig.apiType,
                baseUrl = providerConfig.baseUrl,
                apiKey = providerConfig.apiKey,
                modelName = modelConfig.apiModelName,
                temperature = modelConfig.temperature,
                maxTokens = modelConfig.maxTokens,
                headers = providerConfig.headers + (caseId?.let { mapOf(X_SESSION_ID to it) } ?: emptyMap()),
            )
        val baseClient = ChatClient.builder(chatModel).build()
        return if (accumulator != null) {
            UsageTrackingChatClient(
                delegate = baseClient,
                accumulator = accumulator,
                apiType = providerConfig.apiType,
                modelConfig = modelConfig,
            )
        } else {
            baseClient
        }
    }

    companion object {
        const val X_SESSION_ID = "X-Session-Id"
    }
}
