package io.whozoss.agentos.service.chatclient

import io.whozoss.agentos.plugins.AiProviderDiscoveryService
import io.whozoss.agentos.sdk.model.AiProvider
import io.whozoss.agentos.service.provider.ModelConfig
import jakarta.annotation.PostConstruct
import mu.KLogging
import org.springframework.ai.chat.client.ChatClient
import org.springframework.stereotype.Service
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Service
class ChatClientProvider(
    private val aiProviderDiscoveryService: AiProviderDiscoveryService,
    private val chatModelFactory: ChatModelFactory,
) {
    private val providersById = ConcurrentHashMap<UUID, AiProvider>()

    @PostConstruct
    fun refreshProviders() {
        logger.info("Refreshing AI Providers...")
        providersById.clear()
        val discovered = aiProviderDiscoveryService.discoverAiProviders()
        discovered.forEach {
            providersById[it.id] = it
        }
        logger.info("Loaded ${providersById.size} AI Providers available for use.")
    }

    /**
     * The main entry point. Creates a lightweight ChatClient on demand.
     */
    fun getChatClient(modelConfig: ModelConfig): ChatClient {
        val provider =
            providersById[modelConfig.providerId]
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

    fun getProviderMetadata(id: UUID): AiProvider? = providersById[id]

    fun getAllProviders(): List<AiProvider> = providersById.values.toList()

    companion object : KLogging()
}
