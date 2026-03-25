package io.whozoss.agentos.chat

import io.whozoss.agentos.aiModel.AiModelRegistry
import io.whozoss.agentos.aiProvider.AiProviderRegistry
import io.whozoss.agentos.sdk.aiProvider.AiModel
import org.springframework.ai.chat.model.ChatModel
import org.springframework.stereotype.Service

/**
 * Creates [ChatModel] instances on demand by combining an [AiModel] with its [AiProvider].
 *
 * We expose [ChatModel] rather than [org.springframework.ai.chat.client.ChatClient] because
 * [io.whozoss.agentos.agent.AgentSimple] calls [ChatModel.stream] directly to own its
 * tool-calling loop without any Spring AI interception.
 */
@Service
class ChatClientProvider(
    private val aiModelRegistry: AiModelRegistry,
    private val aiProviderRegistry: AiProviderRegistry,
    private val chatModelFactory: ChatModelFactory,
) {
    fun getChatModel(modelName: String): ChatModel {
        val model =
            aiModelRegistry.findByName(modelName)
                ?: throw IllegalArgumentException("AI model '$modelName' not found.")
        return getChatModel(model)
    }

    fun getChatModel(model: AiModel): ChatModel {
        val provider =
            aiProviderRegistry.getProviderByName(model.providerName)
                ?: throw IllegalArgumentException("AI provider '${model.providerName}' not found for model '${model.name}'.")

        return chatModelFactory.createChatModel(
            provider = provider,
            runtimeModel = model.modelName,
            runtimeTemperature = model.temperature,
            runtimeMaxTokens = model.maxTokens,
        )
    }
}
