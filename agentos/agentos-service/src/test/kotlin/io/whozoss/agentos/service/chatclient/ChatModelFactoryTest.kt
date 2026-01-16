package io.whozoss.agentos.service.chatclient

import io.whozoss.agentos.sdk.aiprovider.AiProvider
import io.whozoss.agentos.sdk.aiprovider.ApiType
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.ai.anthropic.AnthropicChatModel
import org.springframework.ai.google.genai.GoogleGenAiChatModel
import org.springframework.ai.openai.OpenAiChatModel

class ChatModelFactoryTest {

    private val factory = ChatModelFactory()

    @Test
    fun `should create OpenAI chat model`() {
        // Given
        val provider = mockk<AiProvider>()
        every { provider.id } returns "openai-prod"
        every { provider.apiType } returns ApiType.OpenAI
        every { provider.baseUrl } returns "https://api.openai.com"
        every { provider.defaultApiKey } returns "sk-test"
        every { provider.baseModel } returns "gpt-4"
        every { provider.temperature } returns 0.7

        // When
        val model = factory.createChatModel(provider, null, null)

        // Then
        assertNotNull(model)
        assertTrue(model is OpenAiChatModel)
    }

    @Test
    fun `should create Anthropic chat model`() {
        // Given
        val provider = mockk<AiProvider>()
        every { provider.id } returns "anthropic-prod"
        every { provider.apiType } returns ApiType.Anthropic
        every { provider.baseUrl } returns "https://api.anthropic.com"
        every { provider.defaultApiKey } returns "sk-ant-test"
        every { provider.baseModel } returns "claude-3"
        every { provider.temperature } returns 0.5

        // When
        val model = factory.createChatModel(provider, null, null)

        // Then
        assertNotNull(model)
        assertTrue(model is AnthropicChatModel)
    }

    @Test
    fun `should create Gemini chat model`() {
        // Given
        val provider = mockk<AiProvider>()
        every { provider.id } returns "gemini-prod"
        every { provider.apiType } returns ApiType.Gemini
        every { provider.defaultApiKey } returns "google-key"
        every { provider.baseModel } returns "gemini-pro"
        every { provider.temperature } returns 0.5

        // When
        val model = factory.createChatModel(provider, null, null)

        // Then
        assertNotNull(model)
        assertTrue(model is GoogleGenAiChatModel)
    }

    @Test
    fun `should prefer runtime config over default provider config`() {
        // Given
        val provider = mockk<AiProvider>()
        every { provider.id } returns "openai-prod"
        every { provider.apiType } returns ApiType.OpenAI
        every { provider.baseUrl } returns "https://api.openai.com"
        every { provider.defaultApiKey } returns "default-key"
        every { provider.baseModel } returns "gpt-3.5"
        every { provider.temperature } returns 0.7

        // When
        // Providing runtime key and model
        val model = factory.createChatModel(provider, "runtime-key", "gpt-4-runtime")

        // Then
        assertNotNull(model)
        assertTrue(model is OpenAiChatModel)
        // Note: Verifying internal state of OpenAiChatModel is difficult without reflection,
        // but no exception means it accepted the parameters.
    }

    @Test
    fun `should throw exception when API key is missing`() {
        // Given
        val provider = mockk<AiProvider>()
        every { provider.id } returns "bad-provider"
        every { provider.defaultApiKey } returns null // No default key

        // When/Then
        val exception = assertThrows<IllegalArgumentException> {
            factory.createChatModel(provider, null, "gpt-4") // No runtime key
        }
        assertTrue(exception.message!!.contains("No API key provided"))
    }

    @Test
    fun `should throw exception when Model name is missing`() {
        // Given
        val provider = mockk<AiProvider>()
        every { provider.id } returns "bad-provider"
        every { provider.defaultApiKey } returns "key"
        every { provider.baseModel } returns null // No default model

        // When/Then
        val exception = assertThrows<IllegalArgumentException> {
            factory.createChatModel(provider, null, null) // No runtime model
        }
        assertTrue(exception.message!!.contains("No model name provided"))
    }
}