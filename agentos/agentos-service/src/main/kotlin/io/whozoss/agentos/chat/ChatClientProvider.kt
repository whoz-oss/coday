package io.whozoss.agentos.chat

import io.whozoss.agentos.aiModel.AiModelConfig
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import org.springframework.ai.chat.client.ChatClient
import org.springframework.stereotype.Service

/**
 * Creates [ChatClient] instances on demand from a resolved [AiModelConfig] + [AiProvider] pair.
 *
 * The two entities carry everything [ChatModelFactory] needs:
 * - provider connectivity ([AiProvider.apiType], [AiProvider.baseUrl], [AiProvider.apiKey])
 * - model identity and inference parameters ([AiModelConfig.apiName],
 *   [AiModelConfig.temperature], [AiModelConfig.maxTokens])
 *
 * Resolution of which model/provider pair to use is the responsibility of the caller
 * (currently [io.whozoss.agentos.agent.AgentServiceImpl]).
 */
@Service
class ChatClientProvider(
    private val chatModelFactory: ChatModelFactory,
) {
    fun getChatClient(
        modelConfig: AiModelConfig,
        providerConfig: AiProvider,
    ): ChatClient {
        val chatModel =
            chatModelFactory.createChatModel(
                apiType = providerConfig.apiType,
                baseUrl = providerConfig.baseUrl,
                apiKey = providerConfig.apiKey,
                modelName = modelConfig.apiName,
                temperature = modelConfig.temperature,
                maxTokens = modelConfig.maxTokens,
            )
        return ChatClient.builder(chatModel).build()
    }
}
