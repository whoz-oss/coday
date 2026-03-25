package io.whozoss.agentos.chat

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.google.genai.Client
import io.micrometer.observation.ObservationRegistry
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import org.springframework.ai.anthropic.AnthropicChatModel
import org.springframework.ai.anthropic.AnthropicChatOptions
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.google.genai.GoogleGenAiChatModel
import org.springframework.ai.google.genai.GoogleGenAiChatOptions
import org.springframework.ai.model.tool.ToolExecutionEligibilityPredicate
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.openai.api.OpenAiApi
import org.springframework.ai.retry.RetryUtils
import org.springframework.stereotype.Component

/**
 * Creates [ChatModel] instances for each AI provider.
 *
 * ## Why noOpEligibilityPredicate
 *
 * [io.whozoss.agentos.agent.AgentSimple] owns its own tool-calling loop. It passes
 * [org.springframework.ai.tool.ToolCallback] stubs to advertise tool schemas to the LLM
 * (so the provider includes them in the API request), but must never have Spring AI execute
 * them automatically.
 *
 * In Spring AI 2.x, [org.springframework.ai.model.tool.ToolExecutionEligibilityPredicate]
 * is the single gate that controls whether a [org.springframework.ai.model.tool.ToolCallingManager]
 * enters its execution path. Supplying a predicate that always returns `false` means the manager
 * never executes any tool — the raw [org.springframework.ai.chat.model.ChatResponse] with
 * `toolCalls` intact is returned to AgentSimple for it to handle.
 */
@Component
class ChatModelFactory {
    /**
     * Predicate that always returns false — tools are never executed by Spring AI.
     * AgentSimple handles all tool execution in its own loop.
     */
    private val noOpEligibilityPredicate: ToolExecutionEligibilityPredicate =
        ToolExecutionEligibilityPredicate { _, _ -> false }

    fun createChatModel(
        provider: AiProvider,
        runtimeModel: String?,
        runtimeApiKey: String? = null,
        runtimeTemperature: Double? = null,
        runtimeMaxTokens: Int? = null,
    ): ChatModel {
        val apiKey =
            runtimeApiKey.takeIf { !it.isNullOrBlank() }
                ?: provider.defaultApiKey
                ?: throw IllegalArgumentException("No API key provided for provider '${provider.id}' and no default key configured.")

        val modelName =
            runtimeModel.takeIf { !it.isNullOrBlank() } ?: provider.baseModel
                ?: throw IllegalArgumentException("No model name provided for provider '${provider.id}'.")

        val temperature = runtimeTemperature ?: provider.temperature
        val maxTokens = runtimeMaxTokens ?: provider.maxTokens

        return when (provider.apiType) {
            AiApiType.OpenAI -> createOpenAiModel(provider.baseUrl, apiKey, modelName, temperature)
            AiApiType.Anthropic -> createAnthropicModel(provider.baseUrl, apiKey, modelName, temperature, maxTokens)
            AiApiType.Gemini -> createGeminiModel(apiKey, modelName, temperature)
        }
    }

    private fun createOpenAiModel(
        baseUrl: String,
        apiKey: String,
        model: String,
        temp: Double,
    ): ChatModel {
        val api =
            OpenAiApi
                .Builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .build()

        val options =
            OpenAiChatOptions
                .builder()
                .temperature(temp)
                .model(model)
                .build()

        return OpenAiChatModel
            .builder()
            .openAiApi(api)
            .defaultOptions(options)
            .toolExecutionEligibilityPredicate(noOpEligibilityPredicate)
            .retryTemplate(RetryUtils.DEFAULT_RETRY_TEMPLATE)
            .observationRegistry(ObservationRegistry.NOOP)
            .build()
    }

    private fun createAnthropicModel(
        baseUrl: String,
        apiKey: String,
        model: String,
        temp: Double,
        maxTokens: Int?,
    ): ChatModel {
        val client =
            AnthropicOkHttpClient
                .builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .build()

        val optionsBuilder =
            AnthropicChatOptions
                .builder()
                .temperature(temp)
                .model(model)

        if (maxTokens != null) optionsBuilder.maxTokens(maxTokens)

        return AnthropicChatModel
            .builder()
            .anthropicClient(client)
            .options(optionsBuilder.build())
            .toolExecutionEligibilityPredicate(noOpEligibilityPredicate)
            .observationRegistry(ObservationRegistry.NOOP)
            .build()
    }

    private fun createGeminiModel(
        apiKey: String,
        model: String,
        temp: Double,
    ): ChatModel {
        val client = Client.builder().apiKey(apiKey).build()

        val options =
            GoogleGenAiChatOptions
                .builder()
                .model(model)
                .temperature(temp)
                .build()

        return GoogleGenAiChatModel
            .builder()
            .genAiClient(client)
            .defaultOptions(options)
            .toolExecutionEligibilityPredicate(noOpEligibilityPredicate)
            .observationRegistry(ObservationRegistry.NOOP)
            .build()
    }
}
