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
        /** Maximum tokens to generate in the completion. Does not affect the input context window. */
        maxCompletionTokens: Int? = null,
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
                    maxCompletionTokens = maxCompletionTokens,
                )
            }

            AiApiType.vLLM -> {
                createVllmModel(
                    baseUrl = baseUrl!!,
                    apiKey = resolvedApiKey,
                    model = modelName,
                    temp = temperature ?: DEFAULT_TEMPERATURE,
                    maxCompletionTokens = maxCompletionTokens,
                    headers = headers,
                )
            }

            AiApiType.Anthropic -> {
                createAnthropicModel(
                    baseUrl = baseUrl ?: ANTHROPIC_DEFAULT_BASE_URL,
                    apiKey = resolvedApiKey,
                    model = modelName,
                    temp = temperature ?: DEFAULT_TEMPERATURE,
                    maxCompletionTokens = maxCompletionTokens,
                )
            }

            AiApiType.Gemini -> {
                createGeminiModel(
                    apiKey = resolvedApiKey,
                    model = modelName,
                    temp = temperature ?: DEFAULT_TEMPERATURE,
                    maxCompletionTokens = maxCompletionTokens,
                )
            }

            AiApiType.Ollama -> {
                createOllamaModel(
                    baseUrl = baseUrl ?: OLLAMA_DEFAULT_BASE_URL,
                    model = modelName,
                    temp = temperature ?: DEFAULT_TEMPERATURE,
                    maxCompletionTokens = maxCompletionTokens,
                )
            }
        }
    }

    private fun createOpenAiModel(
        baseUrl: String,
        apiKey: String,
        model: String,
        temp: Double,
        maxCompletionTokens: Int?,
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
        if (maxCompletionTokens != null) {
            optionsBuilder.maxCompletionTokens(maxCompletionTokens)
        }
        val options = optionsBuilder.build()
        // Required for usage tracking during streaming: the OpenAI streaming protocol
        // does not include usage by default. With this option the last chunk carries
        // the aggregated usage, which UsageTrackingChatClient reads via doOnComplete.
        // setStreamOptions is on OpenAiChatOptions directly, not on its builder.
        options.streamOptions = OpenAiApi.ChatCompletionRequest.StreamOptions.INCLUDE_USAGE

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
        maxCompletionTokens: Int?,
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
        if (maxCompletionTokens != null) {
            optionsBuilder.maxTokens(maxCompletionTokens)
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
        maxCompletionTokens: Int?,
    ): ChatModel {
        val builder = AnthropicApi.Builder().baseUrl(baseUrl).apiKey(apiKey)
        val api = builder.build()

        val options =
            AnthropicChatOptions
                .builder()
                .temperature(temp)
                .model(model)

        if (maxCompletionTokens != null) {
            options.maxTokens(maxCompletionTokens)
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
        maxCompletionTokens: Int?,
    ): ChatModel {
        val api = Client.builder().apiKey(apiKey).build()

        val optionsBuilder =
            GoogleGenAiChatOptions
                .builder()
                .model(model)
                .temperature(temp)
        if (maxCompletionTokens != null) {
            optionsBuilder.maxOutputTokens(maxCompletionTokens)
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
        maxCompletionTokens: Int?,
    ): ChatModel {
        val api = OllamaApi.builder().baseUrl(baseUrl).build()

        val optionsBuilder =
            OllamaChatOptions
                .builder()
                .model(model)
                .temperature(temp)
        if (maxCompletionTokens != null) {
            optionsBuilder.numPredict(maxCompletionTokens)
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
