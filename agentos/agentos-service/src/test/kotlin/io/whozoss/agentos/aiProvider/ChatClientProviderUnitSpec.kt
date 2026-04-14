package io.whozoss.agentos.aiProvider

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.chat.ChatClientProvider
import io.whozoss.agentos.chat.ChatModelFactory
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.ai.chat.model.ChatModel
import java.util.UUID

class ChatClientProviderUnitSpec : StringSpec({

    val chatModelFactory = mockk<ChatModelFactory>()
    val chatClientProvider = ChatClientProvider(chatModelFactory)

    val aiProviderId = UUID.randomUUID()

    fun provider(apiKey: String? = "sk-test") =
        AiProvider(
            metadata = EntityMetadata(id = aiProviderId),
            namespaceId = UUID.randomUUID(),
            name = "anthropic-prod",
            apiType = AiApiType.Anthropic,
            baseUrl = "https://api.anthropic.com",
            apiKey = apiKey,
        )

    fun model(
        apiName: String = "claude-sonnet-4-5",
        temperature: Double? = 0.7,
        maxTokens: Int? = null,
    ) = AiModel(
        metadata = EntityMetadata(id = UUID.randomUUID()),
        aiProviderId = aiProviderId,
        apiName = apiName,
        temperature = temperature,
        maxTokens = maxTokens,
    )

    "getChatClient forwards model and provider fields to ChatModelFactory" {
        val m = model(apiName = "claude-sonnet-4-5", temperature = 0.3, maxTokens = 8192)
        val p = provider()
        val chatModel = mockk<ChatModel>(relaxed = true)

        every {
            chatModelFactory.createChatModel(
                apiType = AiApiType.Anthropic,
                baseUrl = "https://api.anthropic.com",
                apiKey = "sk-test",
                modelName = "claude-sonnet-4-5",
                temperature = 0.3,
                maxTokens = 8192,
            )
        } returns chatModel

        val client = chatClientProvider.getChatClient(m, p)

        client.shouldNotBeNull()
        verify(exactly = 1) {
            chatModelFactory.createChatModel(
                apiType = AiApiType.Anthropic,
                baseUrl = "https://api.anthropic.com",
                apiKey = "sk-test",
                modelName = "claude-sonnet-4-5",
                temperature = 0.3,
                maxTokens = 8192,
            )
        }
    }

    "getChatClient passes null temperature and maxTokens when model does not specify them" {
        val m = model(apiName = "gpt-4o", temperature = null, maxTokens = null)
        val p = provider()
        val chatModel = mockk<ChatModel>(relaxed = true)

        every {
            chatModelFactory.createChatModel(
                apiType = AiApiType.Anthropic,
                baseUrl = "https://api.anthropic.com",
                apiKey = "sk-test",
                modelName = "gpt-4o",
                temperature = null,
                maxTokens = null,
            )
        } returns chatModel

        chatClientProvider.getChatClient(m, p).shouldNotBeNull()
    }
})
