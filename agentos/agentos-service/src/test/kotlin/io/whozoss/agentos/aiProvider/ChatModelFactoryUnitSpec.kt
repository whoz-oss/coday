package io.whozoss.agentos.aiProvider

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.micrometer.observation.ObservationRegistry
import io.whozoss.agentos.chat.AnthropicProperties
import io.whozoss.agentos.chat.ChatModelFactory
import io.whozoss.agentos.config.UsageConfigProperties
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.ReasoningEffort
import org.springframework.ai.anthropic.AnthropicChatModel
import org.springframework.ai.anthropic.AnthropicChatOptions
import org.springframework.ai.google.genai.GoogleGenAiChatModel
import org.springframework.ai.google.genai.GoogleGenAiChatOptions
import org.springframework.ai.ollama.api.OllamaChatOptions
import org.springframework.ai.ollama.api.ThinkOption
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions

class ChatModelFactoryUnitSpec : StringSpec({

    val factory = ChatModelFactory(ObservationRegistry.NOOP, AnthropicProperties(promptCachingEnabled = true))

    "createChatModel should create OpenAI chat model" {
        val model = factory.createChatModel(
            apiType = AiApiType.OpenAI,
            baseUrl = "https://api.openai.com",
            apiKey = "sk-test",
            modelName = "gpt-4",
            temperature = 0.7,
            maxCompletionTokens = null,
        )

        model.shouldNotBeNull()
        model.shouldBeInstanceOf<OpenAiChatModel>()
    }

    "createChatModel should create OpenAI chat model with null apiKey" {
        val model = factory.createChatModel(
            apiType = AiApiType.OpenAI,
            baseUrl = null,
            apiKey = null,
            modelName = "gpt-4",
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
            maxCompletionTokens = 4000,
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
            maxCompletionTokens = null,
        )

        model.shouldNotBeNull()
        model.shouldBeInstanceOf<GoogleGenAiChatModel>()
    }

    "createChatModel vLLM with apiKey works normally" {
        val model = factory.createChatModel(
            apiType = AiApiType.vLLM,
            baseUrl = "http://localhost:8000",
            apiKey = "sk-vllm-key",
            modelName = "meta-llama/Llama-3-8b-instruct",
        )

        model.shouldNotBeNull()
        model.shouldBeInstanceOf<OpenAiChatModel>()
    }

    "createChatModel vLLM without apiKey does not throw" {
        val model = factory.createChatModel(
            apiType = AiApiType.vLLM,
            baseUrl = "http://localhost:8000",
            apiKey = null,
            modelName = "meta-llama/Llama-3-8b-instruct",
        )

        model.shouldNotBeNull()
        model.shouldBeInstanceOf<OpenAiChatModel>()
    }

    "createChatModel should use null temperature and fall back to default" {
        val model = factory.createChatModel(
            apiType = AiApiType.OpenAI,
            baseUrl = "https://api.openai.com",
            apiKey = "key",
            modelName = "gpt-4o",
            temperature = null,
            maxCompletionTokens = null,
        )

        model.shouldNotBeNull()
        model.shouldBeInstanceOf<OpenAiChatModel>()
    }
    listOf(AiApiType.OpenAI, AiApiType.vLLM).forEach { apiType ->
        "${apiType} requests stream usage only after explicit opt in" {
            val defaultModel = factory.createChatModel(apiType, "http://localhost:8000", "test", "test")
            val nativeOptions = OpenAiChatOptions.builder().build()
            (defaultModel.defaultOptions as OpenAiChatOptions).streamOptions shouldBe nativeOptions.streamOptions
            (defaultModel.defaultOptions as OpenAiChatOptions).streamUsage shouldBe nativeOptions.streamUsage

            val enabledFactory = ChatModelFactory(
                ObservationRegistry.NOOP,
                AnthropicProperties(promptCachingEnabled = true),
                UsageConfigProperties(enabled = true),
            )
            val trackedModel = enabledFactory.createChatModel(apiType, "http://localhost:8000", "test", "test")
            (trackedModel.defaultOptions as OpenAiChatOptions).streamUsage shouldBe true
        }
    }

    // -------------------------------------------------------------------------
    // Reasoning effort
    // -------------------------------------------------------------------------

    fun optionsFor(
        apiType: AiApiType,
        effort: ReasoningEffort?,
    ) = factory.createChatModel(
        apiType = apiType,
        baseUrl = "http://localhost:8000",
        apiKey = "key",
        modelName = "test-model",
        reasoningEffort = effort,
    ).defaultOptions

    "OpenAI sends the reasoning effort verbatim in lowercase and nothing by default" {
        (optionsFor(AiApiType.OpenAI, ReasoningEffort.XHIGH) as OpenAiChatOptions).reasoningEffort shouldBe "xhigh"
        (optionsFor(AiApiType.OpenAI, ReasoningEffort.NONE) as OpenAiChatOptions).reasoningEffort shouldBe "none"
        (optionsFor(AiApiType.OpenAI, null) as OpenAiChatOptions).reasoningEffort.shouldBeNull()
    }

    "vLLM keeps thinking off by default and for NONE, and turns it on with the level otherwise" {
        listOf(null, ReasoningEffort.NONE).forEach { effort ->
            val off = optionsFor(AiApiType.vLLM, effort) as OpenAiChatOptions
            off.reasoningEffort.shouldBeNull()
            off.extraBody shouldBe mapOf("chat_template_kwargs" to mapOf("enable_thinking" to false))
        }

        val on = optionsFor(AiApiType.vLLM, ReasoningEffort.MEDIUM) as OpenAiChatOptions
        on.reasoningEffort shouldBe "medium"
        on.extraBody shouldBe mapOf("chat_template_kwargs" to mapOf("enable_thinking" to true))
    }

    "vLLM sends XHIGH and MAX as high, the highest level its request schema accepts" {
        listOf(ReasoningEffort.HIGH, ReasoningEffort.XHIGH, ReasoningEffort.MAX).forEach { effort ->
            (optionsFor(AiApiType.vLLM, effort) as OpenAiChatOptions).reasoningEffort shouldBe "high"
        }
    }

    "Ollama keeps thinking off by default and for NONE, and maps the other levels to its three levels" {
        mapOf(
            null to ThinkOption.ThinkBoolean.DISABLED,
            ReasoningEffort.NONE to ThinkOption.ThinkBoolean.DISABLED,
            ReasoningEffort.LOW to ThinkOption.ThinkLevel.LOW,
            ReasoningEffort.MEDIUM to ThinkOption.ThinkLevel.MEDIUM,
            ReasoningEffort.HIGH to ThinkOption.ThinkLevel.HIGH,
            ReasoningEffort.XHIGH to ThinkOption.ThinkLevel.HIGH,
            ReasoningEffort.MAX to ThinkOption.ThinkLevel.HIGH,
        ).forEach { (effort, expected) ->
            (optionsFor(AiApiType.Ollama, effort) as OllamaChatOptions).thinkOption shouldBe expected
        }
    }

    "Gemini ignores the reasoning effort and keeps its thinking settings unset" {
        val options = optionsFor(AiApiType.Gemini, ReasoningEffort.HIGH) as GoogleGenAiChatOptions
        options.thinkingLevel.shouldBeNull()
        options.thinkingBudget.shouldBeNull()
    }

    "Anthropic ignores the reasoning effort and keeps thinking unset" {
        (optionsFor(AiApiType.Anthropic, ReasoningEffort.HIGH) as AnthropicChatOptions).thinking.shouldBeNull()
    }

})
