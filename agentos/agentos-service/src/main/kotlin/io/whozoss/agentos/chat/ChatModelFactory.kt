package io.whozoss.agentos.chat

import com.google.genai.Client
import io.micrometer.observation.ObservationRegistry
import io.whozoss.agentos.config.UsageConfigProperties
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.ReasoningEffort
import io.whozoss.agentos.sdk.api.user.PromptCacheTtl
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
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.reactive.function.client.WebClient

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

/**
 * Builds the provider-specific [ChatModel] for an [AiApiType].
 *
 * [requestyPromptCache] tells which models get the one-hour prompt cache markers through Requesty.
 * [isRequestyBaseUrl] tells whether a base URL is the Requesty router. Spring always uses the public constructor,
 * which matches on the host ([RequestyPromptCache.isRequesty]); the internal one lets the wire tests stand a
 * loopback stub in for Requesty.
 */
@Component
class ChatModelFactory internal constructor(
    private val observationRegistry: ObservationRegistry,
    private val anthropicProperties: AnthropicProperties,
    private val usageConfig: UsageConfigProperties,
    private val requestyPromptCache: RequestyPromptCacheProperties = RequestyPromptCacheProperties(),
    private val isRequestyBaseUrl: (String) -> Boolean,
) {
    @Autowired
    constructor(
        observationRegistry: ObservationRegistry,
        anthropicProperties: AnthropicProperties,
        usageConfig: UsageConfigProperties = UsageConfigProperties(),
        requestyPromptCache: RequestyPromptCacheProperties = RequestyPromptCacheProperties(),
    ) : this(observationRegistry, anthropicProperties, usageConfig, requestyPromptCache, RequestyPromptCache::isRequesty)

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
        /** The calling user's choice; only changes the request for Claude models via Requesty (OpenAI API type). */
        promptCacheTtl: PromptCacheTtl = PromptCacheTtl.FIVE_MINUTES,
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
                    promptCacheTtl = promptCacheTtl,
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

    /**
     * Whether a request built by [createChatModel] for these parameters gets a one-hour prompt cache: the user chose
     * [PromptCacheTtl.ONE_HOUR], and the model is a Claude one ([RequestyPromptCacheProperties]) reached through
     * Requesty with the OpenAI API type. Any other request keeps the provider's default 5-minute cache.
     */
    fun appliesOneHourCache(
        apiType: AiApiType,
        baseUrl: String?,
        modelName: String,
        promptCacheTtl: PromptCacheTtl,
    ): Boolean =
        promptCacheTtl == PromptCacheTtl.ONE_HOUR &&
            apiType == AiApiType.OpenAI &&
            isRequestyBaseUrl(baseUrl ?: OPENAI_DEFAULT_BASE_URL) &&
            requestyPromptCache.matchesModel(modelName)

    private fun createOpenAiModel(
        baseUrl: String,
        apiKey: String,
        model: String,
        temp: Double,
        maxCompletionTokens: Int?,
        reasoningEffort: ReasoningEffort?,
        promptCacheTtl: PromptCacheTtl,
    ): ChatModel {
        // Same defaults as OpenAiApi.Builder; request/response hooks are added only when needed.
        val restClientBuilder = RestClient.builder()
        val webClientBuilder = WebClient.builder()
        if (appliesOneHourCache(AiApiType.OpenAI, baseUrl, model, promptCacheTtl)) {
            restClientBuilder.requestInterceptor(RequestyPromptCache.interceptor())
            webClientBuilder.filter(RequestyPromptCache.filter())
        }
        val api =
            OpenAiApi
                .Builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .restClientBuilder(restClientBuilder)
                .webClientBuilder(webClientBuilder)
                .build()

        val optionsBuilder =
            OpenAiChatOptions
                .builder()
                .temperature(temp)
                .model(model)
        if (usageConfig.enabled) {
            optionsBuilder.streamUsage(true)
        }
        if (maxCompletionTokens != null) {
            optionsBuilder.maxCompletionTokens(maxCompletionTokens)
        }
        val options = optionsBuilder.applyReasoningEffort(reasoningEffort).build()

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
        reasoningEffort: ReasoningEffort?,
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
        if (usageConfig.enabled) {
            optionsBuilder.streamUsage(true)
        }
        if (maxCompletionTokens != null) {
            optionsBuilder.maxTokens(maxCompletionTokens)
        }
        val options = optionsBuilder.applyVllmReasoningEffort(reasoningEffort).build()

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
        private const val DEFAULT_TEMPERATURE = 1.0
        private const val OPENAI_DEFAULT_BASE_URL = "https://api.openai.com"
        private const val ANTHROPIC_DEFAULT_BASE_URL = "https://api.anthropic.com"
        private const val OLLAMA_DEFAULT_BASE_URL = "http://localhost:11434"
    }
}
