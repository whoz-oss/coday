package io.whozoss.agentos.chat

import io.whozoss.agentos.llmConfig.LlmConfig
import io.whozoss.agentos.llmModelConfig.LlmModelConfig
import org.springframework.ai.chat.client.ChatClient
import org.springframework.stereotype.Service

/**
 * Creates [ChatClient] instances on demand from a resolved [LlmModelConfig] + [LlmConfig] pair.
 *
 * The two entities carry everything [ChatModelFactory] needs:
 * - provider connectivity ([LlmConfig.apiType], [LlmConfig.baseUrl], [LlmConfig.apiKey])
 * - model identity and inference parameters ([LlmModelConfig.apiName],
 *   [LlmModelConfig.temperature], [LlmModelConfig.maxTokens])
 *
 * Resolution of which model/provider pair to use is the responsibility of the caller
 * (currently [io.whozoss.agentos.agent.AgentServiceImpl]).
 */
@Service
class ChatClientProvider(
    private val chatModelFactory: ChatModelFactory,
) {
    fun getChatClient(
        modelConfig: LlmModelConfig,
        providerConfig: LlmConfig,
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
