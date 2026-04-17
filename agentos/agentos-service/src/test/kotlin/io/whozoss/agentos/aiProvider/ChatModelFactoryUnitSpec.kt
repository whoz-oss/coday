package io.whozoss.agentos.aiProvider

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.whozoss.agentos.chat.ChatModelFactory
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import org.springframework.ai.anthropic.AnthropicChatModel
import org.springframework.ai.google.genai.GoogleGenAiChatModel
import org.springframework.ai.openai.OpenAiChatModel

class ChatModelFactoryUnitSpec : StringSpec({

    val factory = ChatModelFactory()

    "createChatModel should create OpenAI chat model" {
        val model = factory.createChatModel(
            apiType = AiApiType.OpenAI,
            baseUrl = "https://api.openai.com",
            apiKey = "sk-test",
            modelName = "gpt-4",
            temperature = 0.7,
            maxTokens = null,
        )

        model.shouldNotBeNull()
        model.shouldBeInstanceOf<OpenAiChatModel>()
    }

    "createChatModel should create Anthropic chat model" {
        val model = factory.createChatModel(
            apiType = AiApiType.Anthropic,
            baseUrl = "https://api.anthropic.com",
            apiKey = "sk-ant-test",
            modelName = "claude-3",
            temperature = 0.5,
            maxTokens = 4000,
        )

        model.shouldNotBeNull()
        model.shouldBeInstanceOf<AnthropicChatModel>()
    }

    "createChatModel should create Gemini chat model" {
        val model = factory.createChatModel(
            apiType = AiApiType.Gemini,
            baseUrl = null,
            apiKey = "google-key",
            modelName = "gemini-pro",
            temperature = 0.5,
            maxTokens = null,
        )

        model.shouldNotBeNull()
        model.shouldBeInstanceOf<GoogleGenAiChatModel>()
    }

    "createChatModel should throw exception when API key is blank" {
        val exception = shouldThrow<IllegalArgumentException> {
            factory.createChatModel(
                apiType = AiApiType.OpenAI,
                baseUrl = null,
                apiKey = null,
                modelName = "gpt-4",
            )
        }
        exception.message shouldContain "No API key"
    }

    "createChatModel should use null temperature and fall back to default" {
        val model = factory.createChatModel(
            apiType = AiApiType.OpenAI,
            baseUrl = "https://api.openai.com",
            apiKey = "key",
            modelName = "gpt-4o",
            temperature = null,
            maxTokens = null,
        )

        model.shouldNotBeNull()
        model.shouldBeInstanceOf<OpenAiChatModel>()
    }
})
