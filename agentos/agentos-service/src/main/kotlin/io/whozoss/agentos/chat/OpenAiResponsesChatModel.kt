package io.whozoss.agentos.chat

import com.openai.client.OpenAIClient
import com.openai.errors.OpenAIIoException
import com.openai.errors.OpenAIServiceException
import io.micrometer.observation.ObservationRegistry
import mu.KLogging
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.observation.ChatModelObservationContext
import org.springframework.ai.chat.observation.ChatModelObservationDocumentation
import org.springframework.ai.chat.observation.DefaultChatModelObservationConvention
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.model.ModelOptionsUtils
import org.springframework.ai.model.tool.DefaultToolCallingManager
import org.springframework.ai.model.tool.DefaultToolExecutionEligibilityPredicate
import org.springframework.ai.model.tool.ToolCallingChatOptions
import org.springframework.ai.model.tool.ToolCallingManager
import org.springframework.ai.model.tool.ToolExecutionEligibilityPredicate
import org.springframework.ai.model.tool.ToolExecutionResult
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.retry.NonTransientAiException
import org.springframework.ai.retry.TransientAiException
import org.springframework.http.HttpStatus
import reactor.core.publisher.Flux
import reactor.core.scheduler.Schedulers
import java.util.function.Supplier

/**
 * Spring AI [ChatModel] over the OpenAI Responses API, built on the official OpenAI Java SDK.
 * Spring AI 1.1.x only speaks Chat Completions, which refuses function tools on models such as
 * GPT-6 while they reason.
 *
 * Behaves like [org.springframework.ai.openai.OpenAiChatModel] for its callers: [OpenAiChatOptions]
 * as options, Spring AI tool calling (executed here when internal tool execution is enabled, left to
 * the caller otherwise, e.g. [UsageTrackingChatModel]) and the same chat model observation.
 *
 * Provider HTTP errors become [NonTransientAiException] (4xx) or [TransientAiException] (5xx, I/O).
 * Their message names the status and the endpoint only; the error body goes to DEBUG logs because
 * it may echo request content. The SDK exception is not chained for the same reason: its message
 * embeds the body.
 */
class OpenAiResponsesChatModel(
    private val client: OpenAIClient,
    private val defaultOptions: OpenAiChatOptions,
    /** `<base url>/responses`, used in error messages. */
    private val endpoint: String,
    private val toolCallingManager: ToolCallingManager = DefaultToolCallingManager.builder().build(),
    private val toolExecutionEligibilityPredicate: ToolExecutionEligibilityPredicate = DefaultToolExecutionEligibilityPredicate(),
    private val observationRegistry: ObservationRegistry = ObservationRegistry.NOOP,
) : ChatModel {
    override fun getDefaultOptions(): ChatOptions = defaultOptions.copy()

    override fun call(prompt: Prompt): ChatResponse = internalCall(buildRequestPrompt(prompt))

    /** Streaming arrives with the next change; until then the full response is emitted as one chunk. */
    override fun stream(prompt: Prompt): Flux<ChatResponse> =
        Flux.defer { Flux.just(call(prompt)) }.subscribeOn(Schedulers.boundedElastic())

    private fun internalCall(prompt: Prompt): ChatResponse {
        val options = prompt.options as OpenAiChatOptions
        val params = ResponsesRequestMapper.toParams(prompt.instructions, options, toolCallingManager.resolveToolDefinitions(options))
        val context = ChatModelObservationContext.builder().prompt(prompt).provider(PROVIDER).build()
        val response =
            ChatModelObservationDocumentation.CHAT_MODEL_OPERATION
                .observation(null, OBSERVATION_CONVENTION, { context }, observationRegistry)
                .observe(
                    Supplier {
                        ResponsesResponseMapper
                            .toChatResponse(send { client.responses().create(params) })
                            .also { context.setResponse(it) }
                    },
                )!!

        if (!toolExecutionEligibilityPredicate.isToolExecutionRequired(options, response)) return response
        val result = toolCallingManager.executeToolCalls(prompt, response)
        return if (result.returnDirect()) {
            ChatResponse.builder().from(response).generations(ToolExecutionResult.buildGenerations(result)).build()
        } else {
            internalCall(Prompt(result.conversationHistory(), options))
        }
    }

    /**
     * Merges the runtime options over [defaultOptions], as `OpenAiChatModel.buildRequestPrompt` does,
     * including the tool fields that the bean copy skips.
     */
    private fun buildRequestPrompt(prompt: Prompt): Prompt {
        val runtime: OpenAiChatOptions? =
            prompt.options?.let {
                if (it is ToolCallingChatOptions) {
                    ModelOptionsUtils.copyToTarget(it, ToolCallingChatOptions::class.java, OpenAiChatOptions::class.java)
                } else {
                    ModelOptionsUtils.copyToTarget(it, ChatOptions::class.java, OpenAiChatOptions::class.java)
                }
            }
        val merged =
            runtime?.let { ModelOptionsUtils.merge(it, defaultOptions, OpenAiChatOptions::class.java) } ?: defaultOptions.copy()
        merged.internalToolExecutionEnabled = runtime?.internalToolExecutionEnabled ?: defaultOptions.internalToolExecutionEnabled
        merged.toolNames = ToolCallingChatOptions.mergeToolNames(runtime?.toolNames ?: emptySet(), defaultOptions.toolNames)
        merged.toolCallbacks =
            ToolCallingChatOptions.mergeToolCallbacks(runtime?.toolCallbacks ?: emptyList(), defaultOptions.toolCallbacks)
        merged.toolContext = ToolCallingChatOptions.mergeToolContext(runtime?.toolContext ?: emptyMap(), defaultOptions.toolContext)
        ToolCallingChatOptions.validateToolCallbacks(merged.toolCallbacks)
        return Prompt(prompt.instructions, merged)
    }

    private fun <T> send(request: () -> T): T =
        try {
            request()
        } catch (e: OpenAIServiceException) {
            throw providerException(e)
        } catch (e: OpenAIIoException) {
            logger.debug(e) { "I/O error calling $endpoint" }
            throw TransientAiException("I/O error from POST $endpoint")
        }

    private fun providerException(e: OpenAIServiceException): RuntimeException {
        logger.debug {
            val body = e.body().toString()
            val bounded = if (body.length > MAX_BODY_CHARS) body.take(MAX_BODY_CHARS) + TRUNCATION_MARKER else body
            "Provider error response body for ${e.statusCode()} from POST $endpoint: $bounded"
        }
        val status = HttpStatus.resolve(e.statusCode())?.toString() ?: e.statusCode().toString()
        val message = "$status from POST $endpoint"
        return if (e.statusCode() in 400..499) NonTransientAiException(message) else TransientAiException(message)
    }

    companion object : KLogging() {
        private const val PROVIDER = "openai"
        private const val MAX_BODY_CHARS = 4_000
        private const val TRUNCATION_MARKER = "…[truncated]"
        private val OBSERVATION_CONVENTION = DefaultChatModelObservationConvention()
    }
}
