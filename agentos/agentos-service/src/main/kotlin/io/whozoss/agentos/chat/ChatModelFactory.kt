package io.whozoss.agentos.chat

import com.google.genai.Client
import io.micrometer.observation.ObservationRegistry
import io.whozoss.agentos.config.UsageConfigProperties
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.ReasoningEffort
import org.springframework.ai.anthropic.AnthropicCacheOptions
import org.springframework.ai.anthropic.AnthropicCacheStrategy
import org.springframework.ai.anthropic.AnthropicChatModel
import org.springframework.ai.anthropic.AnthropicChatOptions
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.google.genai.GoogleGenAiChatModel
import org.springframework.ai.google.genai.GoogleGenAiChatOptions
import org.springframework.ai.model.tool.DefaultToolCallingManager
import org.springframework.ai.ollama.OllamaChatModel
import org.springframework.ai.ollama.api.OllamaApi
import org.springframework.ai.ollama.api.OllamaChatOptions
import org.springframework.ai.ollama.management.ModelManagementOptions
import org.springframework.ai.ollama.management.PullModelStrategy
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.retry.RetryUtils
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue
import org.springframework.stereotype.Component

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
    private val usageConfig: UsageConfigProperties = UsageConfigProperties(),
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
        /** Null keeps the provider default; ignored for Anthropic and Gemini, where it is rejected at save time. */
        reasoningEffort: ReasoningEffort? = null,
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
                    reasoningEffort = reasoningEffort,
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
                    reasoningEffort = reasoningEffort,
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
                    reasoningEffort = reasoningEffort,
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
        reasoningEffort: ReasoningEffort?,
    ): ChatModel {
        val optionsBuilder =
            OpenAiChatOptions
                .builder()
                .baseUrl(toOpenAiSdkBaseUrl(baseUrl))
                .apiKey(apiKey)
                .temperature(temp)
                .model(model)
        if (usageConfig.enabled) {
            optionsBuilder.streamUsage(true)
        }
        if (maxCompletionTokens != null) {
            optionsBuilder.maxCompletionTokens(maxCompletionTokens)
        }
        val options = optionsBuilder.applyReasoningEffort(reasoningEffort).build()

        return OpenAiChatModel
            .builder()
            .options(options)
            .observationRegistry(observationRegistry)
            .build()
    }

    private fun createVllmModel(
        baseUrl: String,
        apiKey: String,
        model: String,
        temp: Double,
        maxCompletionTokens: Int?,
        headers: Map<String, String>,
        reasoningEffort: ReasoningEffort?,
    ): ChatModel {
        val optionsBuilder =
            OpenAiChatOptions
                .builder()
                .baseUrl(toOpenAiSdkBaseUrl(baseUrl))
                .apiKey(apiKey)
                .temperature(temp)
                .model(model)
        if (headers.isNotEmpty()) {
            optionsBuilder.customHeaders(headers)
        }
        if (usageConfig.enabled) {
            optionsBuilder.streamUsage(true)
        }
        if (maxCompletionTokens != null) {
            optionsBuilder.maxTokens(maxCompletionTokens)
        }
        val options = optionsBuilder.applyVllmReasoningEffort(reasoningEffort).build()

        return OpenAiChatModel
            .builder()
            .options(options)
            .observationRegistry(observationRegistry)
            .build()
    }

    private fun createAnthropicModel(
        baseUrl: String,
        apiKey: String,
        model: String,
        temp: Double,
        maxCompletionTokens: Int?,
    ): ChatModel {
        val options =
            AnthropicChatOptions
                .builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
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

        return AnthropicChatModel
            .builder()
            .options(options.build())
            .observationRegistry(observationRegistry)
            .build()
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
        reasoningEffort: ReasoningEffort?,
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
        val options = optionsBuilder.applyReasoningEffort(reasoningEffort).build()

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
        /**
         * Spring AI 1.x appended `/v1/chat/completions` to the configured base URL; the official
         * OpenAI SDK used by Spring AI 2.x expects the base URL to already end with `/v1`.
         * Normalizing here keeps existing provider configurations (`https://api.openai.com`,
         * `http://vllm-host:8000`) working unchanged.
         */
        internal fun toOpenAiSdkBaseUrl(baseUrl: String): String {
            val trimmed = baseUrl.trimEnd('/')
            return if (trimmed.endsWith("/v1")) trimmed else "$trimmed/v1"
        }

        private const val DEFAULT_TEMPERATURE = 1.0
        private const val OPENAI_DEFAULT_BASE_URL = "https://api.openai.com"
        private const val ANTHROPIC_DEFAULT_BASE_URL = "https://api.anthropic.com"
        private const val OLLAMA_DEFAULT_BASE_URL = "http://localhost:11434"
    }
}
