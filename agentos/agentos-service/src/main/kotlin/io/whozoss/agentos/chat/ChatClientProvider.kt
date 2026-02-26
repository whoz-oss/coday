package io.whozoss.agentos.chat

import io.whozoss.agentos.aiModel.AiModelRegistry
import io.whozoss.agentos.aiProvider.AiProviderRegistry
import io.whozoss.agentos.sdk.aiProvider.AiModel
import org.springframework.ai.chat.client.ChatClient
import org.springframework.stereotype.Service

/**
 * Creates ChatClient instances on demand by combining an AiModel with its referenced AiProvider.
 * Model-level settings (temperature, maxTokens) override provider defaults.
 */
@Service
class ChatClientProvider(
    private val aiModelRegistry: AiModelRegistry,
    private val aiProviderRegistry: AiProviderRegistry,
    private val chatModelFactory: ChatModelFactory,
) {
    fun getChatClient(modelName: String): ChatClient {
        val model =
            aiModelRegistry.findByName(modelName)
                ?: throw IllegalArgumentException("AI model '$modelName' not found.")
        return getChatClient(model)
    }

    fun getChatClient(model: AiModel): ChatClient {
        val provider =
            aiProviderRegistry.getProviderByName(model.providerName)
                ?: throw IllegalArgumentException("AI provider '${model.providerName}' not found for model '${model.name}'.")

        val chatModel =
            chatModelFactory.createChatModel(
                provider = provider,
                runtimeModel = model.modelName,
                runtimeTemperature = model.temperature,
                runtimeMaxTokens = model.maxTokens,
            )

        return ChatClient.builder(chatModel).build()
    }
}
