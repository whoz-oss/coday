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

class ChatModelFactoryTest : StringSpec({

    val factory = ChatModelFactory()

    // -------------------------------------------------------------------------
    // Provider type dispatch
    // -------------------------------------------------------------------------

    "createChatModel creates an OpenAiChatModel for AiApiType.OpenAI" {
        val model = factory.createChatModel(
            apiType = AiApiType.OpenAI,
            baseUrl = "https://api.openai.com",
            apiKey = "sk-test",
            modelName = "gpt-4o",
            temperature = 0.7,
        )
        model.shouldNotBeNull()
        model.shouldBeInstanceOf<OpenAiChatModel>()
    }

    "createChatModel creates an AnthropicChatModel for AiApiType.Anthropic" {
        val model = factory.createChatModel(
            apiType = AiApiType.Anthropic,
            baseUrl = "https://api.anthropic.com",
            apiKey = "sk-ant-test",
            modelName = "claude-sonnet-4-5",
            temperature = 0.5,
            maxTokens = 4000,
        )
        model.shouldNotBeNull()
        model.shouldBeInstanceOf<AnthropicChatModel>()
    }

    "createChatModel creates a GoogleGenAiChatModel for AiApiType.Gemini" {
        val model = factory.createChatModel(
            apiType = AiApiType.Gemini,
            baseUrl = null,
            apiKey = "google-key",
            modelName = "gemini-pro",
            temperature = 0.5,
        )
        model.shouldNotBeNull()
        model.shouldBeInstanceOf<GoogleGenAiChatModel>()
    }

    // -------------------------------------------------------------------------
    // Defaults
    // -------------------------------------------------------------------------

    "createChatModel uses default baseUrl when baseUrl is null for OpenAI" {
        // Should not throw even with a null baseUrl—falls back to the hardcoded default.
        val model = factory.createChatModel(
            apiType = AiApiType.OpenAI,
            baseUrl = null,
            apiKey = "sk-test",
            modelName = "gpt-4o",
        )
        model.shouldBeInstanceOf<OpenAiChatModel>()
    }

    "createChatModel uses default baseUrl when baseUrl is null for Anthropic" {
        val model = factory.createChatModel(
            apiType = AiApiType.Anthropic,
            baseUrl = null,
            apiKey = "sk-ant-test",
            modelName = "claude-sonnet-4-5",
        )
        model.shouldBeInstanceOf<AnthropicChatModel>()
    }

    // -------------------------------------------------------------------------
    // Validation
    // -------------------------------------------------------------------------

    "createChatModel throws when apiKey is null" {
        val ex = shouldThrow<IllegalArgumentException> {
            factory.createChatModel(
                apiType = AiApiType.OpenAI,
                baseUrl = "https://api.openai.com",
                apiKey = null,
                modelName = "gpt-4o",
            )
        }
        ex.message shouldContain "No API key"
    }

    "createChatModel throws when apiKey is blank" {
        val ex = shouldThrow<IllegalArgumentException> {
            factory.createChatModel(
                apiType = AiApiType.OpenAI,
                baseUrl = "https://api.openai.com",
                apiKey = "   ",
                modelName = "gpt-4o",
            )
        }
        ex.message shouldContain "No API key"
    }
})
