package io.whozoss.agentos.aiProvider

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.aiModel.AiModelRegistry
import io.whozoss.agentos.chat.ChatClientProvider
import io.whozoss.agentos.chat.ChatModelFactory
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.ai.chat.model.ChatModel
import java.util.UUID

class ChatClientProviderUnitSpec : StringSpec({

    fun setup(): Triple<AiModelRegistry, AiProviderRegistry, ChatModelFactory> {
        return Triple(mockk(), mockk(), mockk())
    }

    "getChatClient(modelName) should resolve model by name and create chat client" {
        val (aiModelRegistry, aiProviderRegistry, chatModelFactory) = setup()
        val chatClientProvider = ChatClientProvider(aiModelRegistry, aiProviderRegistry, chatModelFactory)
        val model = AiModel(
            metadata = EntityMetadata(id = UUID.randomUUID()),
            name = "gpt-4o",
            description = "OpenAI GPT-4o",
            modelName = "gpt-4o",
            providerName = "openai",
            temperature = 0.7,
        )
        val provider = mockk<AiProvider>()
        val chatModel = mockk<ChatModel>(relaxed = true)
        every { aiModelRegistry.findByName("gpt-4o") } returns model
        every { aiProviderRegistry.getProviderByName("openai") } returns provider
        every { chatModelFactory.createChatModel(provider, "gpt-4o", null, 0.7, null) } returns chatModel

        val client = chatClientProvider.getChatClient("gpt-4o")

        client.shouldNotBeNull()
        verify { chatModelFactory.createChatModel(provider, "gpt-4o", null, 0.7, null) }
    }

    "getChatClient(modelName) should throw when model name is not registered" {
        val (aiModelRegistry, aiProviderRegistry, chatModelFactory) = setup()
        val chatClientProvider = ChatClientProvider(aiModelRegistry, aiProviderRegistry, chatModelFactory)
        every { aiModelRegistry.findByName("unknown-model") } returns null

        val exception = shouldThrow<IllegalArgumentException> {
            chatClientProvider.getChatClient("unknown-model")
        }
        exception.message shouldContain "not found"
    }

    "getChatClient(modelName) should throw when referenced provider is not registered" {
        val (aiModelRegistry, aiProviderRegistry, chatModelFactory) = setup()
        val chatClientProvider = ChatClientProvider(aiModelRegistry, aiProviderRegistry, chatModelFactory)
        val model = AiModel(
            metadata = EntityMetadata(id = UUID.randomUUID()),
            name = "my-model",
            description = "test",
            modelName = "some-model",
            providerName = "missing-provider",
        )
        every { aiModelRegistry.findByName("my-model") } returns model
        every { aiProviderRegistry.getProviderByName("missing-provider") } returns null

        val exception = shouldThrow<IllegalArgumentException> {
            chatClientProvider.getChatClient("my-model")
        }
        exception.message shouldContain "missing-provider"
    }

    "getChatClient(AiModel) should forward model overrides to ChatModelFactory" {
        val (aiModelRegistry, aiProviderRegistry, chatModelFactory) = setup()
        val chatClientProvider = ChatClientProvider(aiModelRegistry, aiProviderRegistry, chatModelFactory)
        val model = AiModel(
            metadata = EntityMetadata(id = UUID.randomUUID()),
            name = "claude-sonnet",
            description = "Anthropic Claude",
            modelName = "claude-sonnet-4-5",
            providerName = "anthropic",
            temperature = 0.3,
            maxTokens = 8192,
        )
        val provider = mockk<AiProvider>()
        val chatModel = mockk<ChatModel>(relaxed = true)
        every { aiProviderRegistry.getProviderByName("anthropic") } returns provider
        every { chatModelFactory.createChatModel(provider, "claude-sonnet-4-5", null, 0.3, 8192) } returns chatModel

        val client = chatClientProvider.getChatClient(model)

        client.shouldNotBeNull()
        verify { chatModelFactory.createChatModel(provider, "claude-sonnet-4-5", null, 0.3, 8192) }
    }

    "getChatClient(AiModel) should pass null overrides when model does not specify them" {
        val (aiModelRegistry, aiProviderRegistry, chatModelFactory) = setup()
        val chatClientProvider = ChatClientProvider(aiModelRegistry, aiProviderRegistry, chatModelFactory)
        val model = AiModel(
            metadata = EntityMetadata(id = UUID.randomUUID()),
            name = "basic-model",
            description = "No overrides",
            modelName = "gpt-3.5-turbo",
            providerName = "openai",
        )
        val provider = mockk<AiProvider>()
        val chatModel = mockk<ChatModel>(relaxed = true)
        every { aiProviderRegistry.getProviderByName("openai") } returns provider
        every { chatModelFactory.createChatModel(provider, "gpt-3.5-turbo", null, null, null) } returns chatModel

        chatClientProvider.getChatClient(model).shouldNotBeNull()
        verify { chatModelFactory.createChatModel(provider, "gpt-3.5-turbo", null, null, null) }
    }
})
