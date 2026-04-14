package io.whozoss.agentos.aiProvider

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.aiModel.AiModelConfig
import io.whozoss.agentos.chat.ChatClientProvider
import io.whozoss.agentos.chat.ChatModelFactory
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.ai.chat.model.ChatModel
import java.util.UUID

class ChatClientProviderTest :
    StringSpec({

        val chatModelFactory = mockk<ChatModelFactory>()
        val chatClientProvider = ChatClientProvider(chatModelFactory)

        val aiProviderId = UUID.randomUUID()
        val namespaceId = UUID.randomUUID()

        fun providerConfig(
            apiType: AiApiType = AiApiType.Anthropic,
            baseUrl: String? = "https://api.anthropic.com",
            apiKey: String? = "sk-ant-test",
        ) = AiProvider(
            metadata = EntityMetadata(id = aiProviderId),
            namespaceId = namespaceId,
            name = "anthropic-prod",
            apiType = apiType,
            baseUrl = baseUrl,
            apiKey = apiKey,
        )

        fun modelConfig(
            apiName: String = "claude-sonnet-4-5",
            alias: String? = "sonnet",
            temperature: Double? = 0.7,
            maxTokens: Int? = 8192,
        ) = AiModelConfig(
            metadata = EntityMetadata(id = UUID.randomUUID()),
            aiProviderId = aiProviderId,
            namespaceId = namespaceId,
            apiName = apiName,
            alias = alias,
            temperature = temperature,
            maxTokens = maxTokens,
        )

        "getChatClient builds a ChatClient from modelConfig and providerConfig" {
            val chatModel = mockk<ChatModel>(relaxed = true)
            every {
                chatModelFactory.createChatModel(
                    apiType = AiApiType.Anthropic,
                    baseUrl = "https://api.anthropic.com",
                    apiKey = "sk-ant-test",
                    modelName = "claude-sonnet-4-5",
                    temperature = 0.7,
                    maxTokens = 8192,
                )
            } returns chatModel

            val client = chatClientProvider.getChatClient(modelConfig(), providerConfig())

            client.shouldNotBeNull()
            verify(exactly = 1) {
                chatModelFactory.createChatModel(
                    apiType = AiApiType.Anthropic,
                    baseUrl = "https://api.anthropic.com",
                    apiKey = "sk-ant-test",
                    modelName = "claude-sonnet-4-5",
                    temperature = 0.7,
                    maxTokens = 8192,
                )
            }
        }

        "getChatClient passes null temperature and maxTokens when not set on modelConfig" {
            val chatModel = mockk<ChatModel>(relaxed = true)
            every {
                chatModelFactory.createChatModel(
                    apiType = AiApiType.OpenAI,
                    baseUrl = "https://api.openai.com",
                    apiKey = "sk-openai",
                    modelName = "gpt-4o",
                    temperature = null,
                    maxTokens = null,
                )
            } returns chatModel

            val provider = providerConfig(apiType = AiApiType.OpenAI, baseUrl = "https://api.openai.com", apiKey = "sk-openai")
            val model = modelConfig(apiName = "gpt-4o", alias = null, temperature = null, maxTokens = null)

            chatClientProvider.getChatClient(model, provider).shouldNotBeNull()
        }

        "getChatClient propagates exception from ChatModelFactory when apiKey is missing" {
            every {
                chatModelFactory.createChatModel(
                    apiType = any(),
                    baseUrl = any(),
                    apiKey = null,
                    modelName = any(),
                    temperature = any(),
                    maxTokens = any(),
                )
            } throws IllegalArgumentException("No API key configured")

            shouldThrow<IllegalArgumentException> {
                chatClientProvider.getChatClient(modelConfig(), providerConfig(apiKey = null))
            }
        }
    })
