package io.whozoss.agentos.chat

import com.google.genai.Client
import io.micrometer.observation.ObservationRegistry
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import org.springframework.ai.anthropic.AnthropicChatModel
import org.springframework.ai.anthropic.AnthropicChatOptions
import org.springframework.ai.anthropic.api.AnthropicApi
import org.springframework.ai.anthropic.api.AnthropicCacheOptions
import org.springframework.ai.anthropic.api.AnthropicCacheStrategy
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
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap

/**
 * Configuration for Anthropic-specific features.
 *
 * [promptCachingEnabled] activates the `CONVERSATION_HISTORY` caching strategy on every
 * Anthropic chat model instance created by [ChatModelFactory]. When enabled, Anthropic
 * caches the system prompt, tool definitions, and the growing conversation history across
 * API calls. This is the optimal strategy for agent runs: each turn reuses the cached
 * prefix built by previous turns, yielding significant cost and latency savings on
 * multi-turn interactions.
 *
 * Default: `true`. Disable via `agentos.anthropic.prompt-caching-enabled=false` or
 * `AGENTOS_ANTHROPIC_PROMPT_CACHING_ENABLED=false`.
 */
@ConfigurationProperties(prefix = "agentos.anthropic")
data class AnthropicProperties(
    @DefaultValue("true")
    val promptCachingEnabled: Boolean,
)

@Component
class ChatModelFactory(
    private val observationRegistry: ObservationRegistry,
    private val anthropicProperties: AnthropicProperties,
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

        if (anthropicProperties.promptCachingEnabled) {
            options.cacheOptions(
                AnthropicCacheOptions
                    .builder()
                    .strategy(AnthropicCacheStrategy.CONVERSATION_HISTORY)
                    .build(),
            )
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
