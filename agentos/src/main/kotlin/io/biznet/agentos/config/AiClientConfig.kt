package io.biznet.agentos.config

import io.micrometer.observation.ObservationRegistry
import org.springframework.ai.anthropic.AnthropicChatModel
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.model.tool.DefaultToolCallingManager
import org.springframework.ai.model.tool.DefaultToolExecutionEligibilityPredicate
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.openai.api.OpenAiApi
import org.springframework.ai.retry.RetryUtils
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class AiClientConfig {
    @Value("\${spring.ai.vllm.base-url}")
    private val vllmBaseUrl: String? = null

    @Value("\${spring.ai.vllm.api-key}")
    private val vllmApiKey: String? = null

    @Value("\${spring.ai.vllm.chat.options.model}")
    private val vllmModelName: String? = null

    @Bean
    @Qualifier("openAiChatClient")
    fun openAiChatClient(openAiChatModel: OpenAiChatModel): ChatClient = ChatClient.create(openAiChatModel)

    @Bean
    @Qualifier("vllmChatClient")
    fun vllmChatClient(): ChatClient =
        ChatClient.create(
            OpenAiChatModel(
                OpenAiApi
                    .Builder()
                    .baseUrl(vllmBaseUrl)
                    .apiKey(vllmApiKey)
                    .build(),
                OpenAiChatOptions
                    .builder()
                    .model(vllmModelName)
                    .temperature(0.0)
                    .build(),
                DefaultToolCallingManager.builder().build(),
                RetryUtils.DEFAULT_RETRY_TEMPLATE,
                ObservationRegistry.NOOP,
                DefaultToolExecutionEligibilityPredicate(),
            ),
        )

    @Bean
    @Qualifier("anthropicChatClient")
    fun anthropicChatClient(anthropicChatModel: AnthropicChatModel): ChatClient = ChatClient.create(anthropicChatModel)
}
