package io.whozoss.agentos.aiProvider

import com.google.genai.Client
import io.micrometer.observation.ObservationRegistry
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import org.springframework.ai.anthropic.AnthropicChatModel
import org.springframework.ai.anthropic.AnthropicChatOptions
import org.springframework.ai.anthropic.api.AnthropicApi
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.google.genai.GoogleGenAiChatModel
import org.springframework.ai.google.genai.GoogleGenAiChatOptions
import org.springframework.ai.model.tool.DefaultToolCallingManager
import org.springframework.ai.model.tool.DefaultToolExecutionEligibilityPredicate
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.openai.api.OpenAiApi
import org.springframework.ai.retry.RetryUtils
import org.springframework.stereotype.Component

@Component
class ChatModelFactory {
    fun createChatModel(
        provider: AiProvider,
        runtimeApiKey: String?,
        runtimeModel: String?,
    ): ChatModel {
        val apiKey =
            runtimeApiKey.takeIf { !it.isNullOrBlank() }
                ?: provider.defaultApiKey
                ?: throw IllegalArgumentException("No API key provided for provider '${provider.id}' and no default key configured.")

        val modelName =
            runtimeModel.takeIf { !it.isNullOrBlank() } ?: provider.baseModel
                ?: throw IllegalArgumentException("No model name provided for provider '${provider.id}'.")

        return when (provider.apiType) {
            AiApiType.OpenAI -> {
                createOpenAiModel(
                    baseUrl = provider.baseUrl,
                    apiKey = apiKey,
                    model = modelName,
                    temp = provider.temperature,
                )
            }

            AiApiType.Anthropic -> {
                createAnthropicModel(
                    baseUrl = provider.baseUrl,
                    apiKey = apiKey,
                    model = modelName,
                    temp = provider.temperature,
                    maxTokens = provider.maxTokens,
                )
            }

            AiApiType.Gemini -> {
                createGeminiModel(apiKey = apiKey, model = modelName, temp = provider.temperature)
            }
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

        return OpenAiChatModel(
            api,
            options,
            DefaultToolCallingManager.builder().build(),
            RetryUtils.DEFAULT_RETRY_TEMPLATE,
            ObservationRegistry.NOOP,
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
        val api =
            AnthropicApi
                .Builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .build()

        val options =
            AnthropicChatOptions
                .builder()
                .temperature(temp)
                .model(model)

        if (maxTokens != null) {
            options.maxTokens(maxTokens)
        }

        return AnthropicChatModel(
            api,
            options.build(),
            DefaultToolCallingManager.builder().build(),
            RetryUtils.DEFAULT_RETRY_TEMPLATE,
            ObservationRegistry.NOOP,
        )
    }

    private fun createGeminiModel(
        apiKey: String,
        model: String,
        temp: Double,
    ): ChatModel {
        val api = Client.builder().apiKey(apiKey).build()

        val options =
            GoogleGenAiChatOptions
                .builder()
                .model(model)
                .temperature(temp)
                .build()

        return GoogleGenAiChatModel(
            api,
            options,
            DefaultToolCallingManager.builder().build(),
            RetryUtils.DEFAULT_RETRY_TEMPLATE,
            ObservationRegistry.NOOP,
        )
    }
}
