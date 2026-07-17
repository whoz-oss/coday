package io.whozoss.agentos.chat

import com.google.genai.Client
import io.micrometer.observation.ObservationRegistry
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import org.springframework.ai.anthropic.AnthropicChatModel
import org.springframework.ai.anthropic.AnthropicChatOptions
import org.springframework.ai.anthropic.api.AnthropicApi
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.google.genai.GoogleGenAiChatModel
import org.springframework.ai.google.genai.GoogleGenAiChatOptions
import org.springframework.ai.model.tool.DefaultToolCallingManager
import org.springframework.ai.model.tool.DefaultToolExecutionEligibilityPredicate
import org.springframework.ai.ollama.OllamaChatModel
import org.springframework.ai.ollama.api.OllamaApi
import org.springframework.ai.ollama.api.OllamaChatOptions
import org.springframework.ai.ollama.management.ModelManagementOptions
import org.springframework.ai.ollama.management.PullModelStrategy
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.openai.api.OpenAiApi
import org.springframework.ai.retry.RetryUtils
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap

@Component
class ChatModelFactory(
    private val observationRegistry: ObservationRegistry,
) {
    fun createChatModel(
        apiType: AiApiType,
        baseUrl: String?,
        apiKey: String?,
        modelName: String,
        temperature: Double? = null,
        maxTokens: Int? = null,
        headers: Map<String, String> = emptyMap(),
    ): ChatModel {
        val resolvedApiKey = apiKey ?: ""
        return when (apiType) {
            AiApiType.OpenAI -> {
                createOpenAiModel(
                    baseUrl = baseUrl ?: OPENAI_DEFAULT_BASE_URL,
                    apiKey = resolvedApiKey,
                    model = modelName,
                    temp = temperature ?: DEFAULT_TEMPERATURE,
                    maxTokens = maxTokens,
                )
            }

            AiApiType.vLLM -> {
                createVllmModel(
                    baseUrl = baseUrl!!,
                    apiKey = resolvedApiKey,
                    model = modelName,
                    temp = temperature ?: DEFAULT_TEMPERATURE,
                    maxTokens = maxTokens,
                    headers = headers,
                )
            }

            AiApiType.Anthropic -> {
                createAnthropicModel(
                    baseUrl = baseUrl ?: ANTHROPIC_DEFAULT_BASE_URL,
                    apiKey = resolvedApiKey,
                    model = modelName,
                    temp = temperature ?: DEFAULT_TEMPERATURE,
                    maxTokens = maxTokens,
                )
            }

            AiApiType.Gemini -> {
                createGeminiModel(
                    apiKey = resolvedApiKey,
                    model = modelName,
                    temp = temperature ?: DEFAULT_TEMPERATURE,
                    maxTokens = maxTokens,
                )
            }

            AiApiType.Ollama -> {
                createOllamaModel(
                    baseUrl = baseUrl ?: OLLAMA_DEFAULT_BASE_URL,
                    model = modelName,
                    temp = temperature ?: DEFAULT_TEMPERATURE,
                    maxTokens = maxTokens,
                )
            }
        }
    }

    private fun createOpenAiModel(
        baseUrl: String,
        apiKey: String,
        model: String,
        temp: Double,
        maxTokens: Int?,
    ): ChatModel {
        val api =
            OpenAiApi
                .Builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .build()

        val optionsBuilder =
            OpenAiChatOptions
                .builder()
                .temperature(temp)
                .model(model)
        if (maxTokens != null) {
            optionsBuilder.maxCompletionTokens(maxTokens)
        }
        val options = optionsBuilder.build()

        return OpenAiChatModel(
            api,
            options,
            DefaultToolCallingManager.builder().build(),
            RetryUtils.DEFAULT_RETRY_TEMPLATE,
            observationRegistry,
            DefaultToolExecutionEligibilityPredicate(),
        )
    }

    private fun createVllmModel(
        baseUrl: String,
        apiKey: String,
        model: String,
        temp: Double,
        maxTokens: Int?,
        headers: Map<String, String>,
    ): ChatModel {
        var builder = OpenAiApi.Builder().baseUrl(baseUrl).apiKey(apiKey)
        if (headers.isNotEmpty()) {
            val multiValueHeaders =
                LinkedMultiValueMap<String, String>(
                    headers.mapValues { (_, value) -> listOf(value) },
                )
            builder = builder.headers(multiValueHeaders)
        }
        val api = builder.build()

        val optionsBuilder =
            OpenAiChatOptions
                .builder()
                .temperature(temp)
                .model(model)
        if (maxTokens != null) {
            optionsBuilder.maxTokens(maxTokens)
        }
        val options = optionsBuilder.extraBody(mapOf("chat_template_kwargs" to mapOf("enable_thinking" to false))).build()

        return OpenAiChatModel(
            api,
            options,
            DefaultToolCallingManager.builder().build(),
            RetryUtils.DEFAULT_RETRY_TEMPLATE,
            observationRegistry,
            DefaultToolExecutionEligibilityPredicate(),
        )
    }

    private fun createAnthropicModel(
        baseUrl: String,
        apiKey: String,
        model: String,
        temp: Double,
        maxTokens: Int?,
    ): ChatModel {
        val builder = AnthropicApi.Builder().baseUrl(baseUrl).apiKey(apiKey)
        val api = builder.build()

        // ANNOTATION MODE — temperature omitted: some Anthropic models (e.g. claude-opus-4-8)
        // reject the temperature parameter. Not committed, local only.
        val options =
            AnthropicChatOptions
                .builder()
                .model(model)

        if (maxTokens != null) {
            options.maxTokens(maxTokens)
        }

        return AnthropicChatModel(
            api,
            options.build(),
            DefaultToolCallingManager.builder().build(),
            RetryUtils.DEFAULT_RETRY_TEMPLATE,
            observationRegistry,
        )
    }

    private fun createGeminiModel(
        apiKey: String,
        model: String,
        temp: Double,
        maxTokens: Int?,
    ): ChatModel {
        val api = Client.builder().apiKey(apiKey).build()

        val optionsBuilder =
            GoogleGenAiChatOptions
                .builder()
                .model(model)
                .temperature(temp)
        if (maxTokens != null) {
            optionsBuilder.maxOutputTokens(maxTokens)
        }
        val options = optionsBuilder.build()

        return GoogleGenAiChatModel(
            api,
            options,
            DefaultToolCallingManager.builder().build(),
            RetryUtils.DEFAULT_RETRY_TEMPLATE,
            observationRegistry,
        )
    }

    private fun createOllamaModel(
        baseUrl: String,
        model: String,
        temp: Double,
        maxTokens: Int?,
    ): ChatModel {
        val api = OllamaApi.builder().baseUrl(baseUrl).build()

        val optionsBuilder =
            OllamaChatOptions
                .builder()
                .model(model)
                .temperature(temp)
        if (maxTokens != null) {
            optionsBuilder.numPredict(maxTokens)
        }
        optionsBuilder.disableThinking()
        val options = optionsBuilder.build()

        return OllamaChatModel(
            api,
            options,
            DefaultToolCallingManager.builder().build(),
            observationRegistry,
            ModelManagementOptions
                .builder()
                .pullModelStrategy(PullModelStrategy.NEVER)
                .build(),
        )
    }

    companion object {
        private const val DEFAULT_TEMPERATURE = 1.0
        private const val OPENAI_DEFAULT_BASE_URL = "https://api.openai.com"
        private const val ANTHROPIC_DEFAULT_BASE_URL = "https://api.anthropic.com"
        private const val OLLAMA_DEFAULT_BASE_URL = "http://localhost:11434"
    }
}
