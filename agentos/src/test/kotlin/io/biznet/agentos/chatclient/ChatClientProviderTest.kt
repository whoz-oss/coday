package io.biznet.agentos.chatclient

import io.biznet.agentos.api.aiprovider.AiProvider
import io.biznet.agentos.plugins.AiProviderDiscoveryService
import io.biznet.agentos.provider.ModelConfig
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.ai.chat.model.ChatModel

class ChatClientProviderTest {

    private lateinit var discoveryService: AiProviderDiscoveryService
    private lateinit var factory: ChatModelFactory
    private lateinit var provider: ChatClientProvider

    @BeforeEach
    fun setup() {
        discoveryService = mockk()
        factory = mockk()
        provider = ChatClientProvider(discoveryService, factory)
    }

    @Test
    fun `should refresh providers on initialization`() {
        // Given
        val aiProvider = mockk<AiProvider>()
        every { aiProvider.id } returns "test-provider"
        every { discoveryService.discoverAiProviders() } returns listOf(aiProvider)

        // When
        provider.refreshProviders()

        // Then
        val result = provider.getProviderMetadata("test-provider")
        assertEquals(aiProvider, result)
        assertEquals(1, provider.getAllProviders().size)
    }

    @Test
    fun `should create chat client for existing provider`() {
        // Given
        val providerId = "openai-gpt4"
        val apiKey = "sk-runtime"
        val modelName = "gpt-4-turbo"

        // Mock Provider Data
        val aiProvider = mockk<AiProvider>()
        every { aiProvider.id } returns providerId
        every { discoveryService.discoverAiProviders() } returns listOf(aiProvider)

        // Mock Factory Logic
        // Use relaxed = true to handle calls like getDefaultOptions() made by ChatClient.builder
        val mockChatModel = mockk<ChatModel>(relaxed = true)
        every { factory.createChatModel(aiProvider, apiKey, modelName) } returns mockChatModel

        // Load providers
        provider.refreshProviders()

        val config = ModelConfig(providerId, apiKey, modelName)

        // When
        val client = provider.getChatClient(config)

        // Then
        assertNotNull(client)
        verify { factory.createChatModel(aiProvider, apiKey, modelName) }
    }

    @Test
    fun `should throw exception for unknown provider`() {
        // Given
        every { discoveryService.discoverAiProviders() } returns emptyList()
        provider.refreshProviders()

        val config = ModelConfig("unknown-provider", null, null)

        // When/Then
        val exception = assertThrows<IllegalArgumentException> {
            provider.getChatClient(config)
        }
        assertTrue(exception.message!!.contains("not found"))
    }

    @Test
    fun `should replace existing providers on refresh`() {
        // Given
        val p1 = mockk<AiProvider>()
        every { p1.id } returns "p1"

        val p2 = mockk<AiProvider>()
        every { p2.id } returns "p2"

        // First load
        every { discoveryService.discoverAiProviders() } returns listOf(p1)
        provider.refreshProviders()
        assertEquals(1, provider.getAllProviders().size)
        assertNotNull(provider.getProviderMetadata("p1"))

        // Second load (replace p1 with p2)
        every { discoveryService.discoverAiProviders() } returns listOf(p2)
        provider.refreshProviders()

        // Then
        assertEquals(1, provider.getAllProviders().size)
        assertNotNull(provider.getProviderMetadata("p2"))
        assertEquals(null, provider.getProviderMetadata("p1"))
    }
}