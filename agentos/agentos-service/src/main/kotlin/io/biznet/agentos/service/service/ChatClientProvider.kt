package io.biznet.agentos.service.service

import io.biznet.agentos.plugins.AiProviderDiscoveryService
import io.biznet.agentos.sdk.model.AiProvider
import io.biznet.agentos.sdk.model.ModelConfig
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

@Service
class ChatClientProvider(
    private val aiProviderDiscoveryService: AiProviderDiscoveryService,
    private val chatModelFactory: ChatModelFactory,
) {
    private val logger = LoggerFactory.getLogger(ChatClientProvider::class.java)

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
}
