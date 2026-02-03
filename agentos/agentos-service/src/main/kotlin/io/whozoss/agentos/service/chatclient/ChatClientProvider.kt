package io.whozoss.agentos.service.chatclient

import io.whozoss.agentos.sdk.aiprovider.AiProvider
import io.whozoss.agentos.service.plugins.AiProviderDiscoveryService
import io.whozoss.agentos.service.provider.ModelConfig
import jakarta.annotation.PostConstruct
import mu.KLogging
import org.springframework.ai.chat.client.ChatClient
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

@Service
class ChatClientProvider(
    private val aiProviderDiscoveryService: AiProviderDiscoveryService,
    private val chatModelFactory: ChatModelFactory,
) {
    private val providers = ConcurrentHashMap<String, AiProvider>()

    @PostConstruct
    fun refreshProviders() {
        logger.info("Refreshing AI Providers...")
        providers.clear()
        val discovered = aiProviderDiscoveryService.discoverAiProviders()
        discovered.forEach {
            providers[it.id] = it
        }
        logger.info("Loaded ${providers.size} AI Providers available for use.")
    }

    /**
     * The main entry point. Creates a lightweight ChatClient on demand.
     */
    fun getChatClient(modelConfig: ModelConfig): ChatClient {
        val provider =
            providers[modelConfig.providerId]
                ?: throw IllegalArgumentException("Provider '${modelConfig.providerId}' not found.")

        val chatModel =
            chatModelFactory.createChatModel(
                provider = provider,
                runtimeApiKey = modelConfig.apiKey,
                runtimeModel = modelConfig.model,
            )

        return ChatClient
            .builder(chatModel)
            .build()
    }

    fun getProviderMetadata(id: String): AiProvider? = providers[id]

    fun getAllProviders(): List<AiProvider> = providers.values.toList()

    companion object : KLogging()
}
