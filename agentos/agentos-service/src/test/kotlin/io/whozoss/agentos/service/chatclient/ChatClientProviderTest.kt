package io.whozoss.agentos.service.chatclient

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.sdk.aiprovider.AiProvider
import io.whozoss.agentos.service.plugins.AiProviderDiscoveryService
import io.whozoss.agentos.service.provider.ModelConfig
import org.springframework.ai.chat.model.ChatModel

class ChatClientProviderTest : DescribeSpec({

    describe("ChatClientProvider") {

        lateinit var discoveryService: AiProviderDiscoveryService
        lateinit var factory: ChatModelFactory
        lateinit var provider: ChatClientProvider

        beforeEach {
            discoveryService = mockk()
            factory = mockk()
            provider = ChatClientProvider(discoveryService, factory)
        }

        describe("refreshProviders") {

            it("should refresh providers on initialization") {
                val aiProvider = mockk<AiProvider>()
                every { aiProvider.id } returns "test-provider"
                every { discoveryService.discoverAiProviders() } returns listOf(aiProvider)

                provider.refreshProviders()

                val result = provider.getProviderMetadata("test-provider")
                result shouldBe aiProvider
                provider.getAllProviders() shouldHaveSize 1
            }

            it("should replace existing providers on refresh") {
                val p1 = mockk<AiProvider>()
                every { p1.id } returns "p1"

                val p2 = mockk<AiProvider>()
                every { p2.id } returns "p2"

                // First load
                every { discoveryService.discoverAiProviders() } returns listOf(p1)
                provider.refreshProviders()
                provider.getAllProviders() shouldHaveSize 1
                provider.getProviderMetadata("p1").shouldNotBeNull()

                // Second load (replace p1 with p2)
                every { discoveryService.discoverAiProviders() } returns listOf(p2)
                provider.refreshProviders()

                provider.getAllProviders() shouldHaveSize 1
                provider.getProviderMetadata("p2").shouldNotBeNull()
                provider.getProviderMetadata("p1").shouldBeNull()
            }
        }

        describe("getChatClient") {

            it("should create chat client for existing provider") {
                val providerId = "openai-gpt4"
                val apiKey = "sk-runtime"
                val modelName = "gpt-4-turbo"

                val aiProvider = mockk<AiProvider>()
                every { aiProvider.id } returns providerId
                every { discoveryService.discoverAiProviders() } returns listOf(aiProvider)

                val mockChatModel = mockk<ChatModel>(relaxed = true)
                every { factory.createChatModel(aiProvider, apiKey, modelName) } returns mockChatModel

                provider.refreshProviders()

                val config = ModelConfig(providerId, apiKey, modelName)
                val client = provider.getChatClient(config)

                client.shouldNotBeNull()
                verify { factory.createChatModel(aiProvider, apiKey, modelName) }
            }

            it("should throw exception for unknown provider") {
                every { discoveryService.discoverAiProviders() } returns emptyList()
                provider.refreshProviders()

                val config = ModelConfig("unknown-provider", null, null)

                val exception = shouldThrow<IllegalArgumentException> {
                    provider.getChatClient(config)
                }
                exception.message shouldContain "not found"
            }
        }
    }
})
