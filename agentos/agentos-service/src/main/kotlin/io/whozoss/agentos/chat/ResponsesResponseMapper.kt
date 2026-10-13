package io.whozoss.agentos.chat

import com.openai.models.responses.Response
import com.openai.models.responses.ResponseError
import com.openai.models.responses.ResponseStatus
import com.openai.models.responses.ResponseStreamEvent
import mu.KLogging
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.metadata.ChatGenerationMetadata
import org.springframework.ai.chat.metadata.ChatResponseMetadata
import org.springframework.ai.chat.metadata.DefaultUsage
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.retry.NonTransientAiException
import org.springframework.ai.retry.TransientAiException

/**
 * Maps OpenAI Responses API results back to Spring AI [ChatResponse]s.
 *
 * One [Generation] per response, carrying the output text, every `function_call` item as a tool
 * call, and a Chat Completions style finish reason (`stop`, `tool_calls`, `length`,
 * `content_filter`). Usage keeps the native [com.openai.models.responses.ResponseUsage] so
 * [CostCalculator] can read cached input tokens.
 *
 * When streaming, text arrives as `output_text.delta` chunks; the terminal event then yields a
 * chunk without text that carries the tool calls, the usage and the finish reason, so
 * [org.springframework.ai.chat.model.MessageAggregator] sees each of them exactly once.
 */
internal object ResponsesResponseMapper : KLogging() {
    fun toChatResponse(response: Response): ChatResponse = toChatResponse(response, includeText = true)

    /** The chunk to emit for a stream event, or null for events that carry nothing to forward. */
    fun toChunk(event: ResponseStreamEvent): ChatResponse? =
        when {
            event.isOutputTextDelta() -> {
                ChatResponse(listOf(Generation(AssistantMessage(event.asOutputTextDelta().delta()))))
            }

            event.isCompleted() -> {
                toChatResponse(event.asCompleted().response(), includeText = false)
            }

            event.isIncomplete() -> {
                toChatResponse(event.asIncomplete().response(), includeText = false)
            }

            event.isFailed() -> {
                throw failure(event.asFailed().response())
            }

            event.isError() -> {
                val error = event.asError()
                logger.debug { "OpenAI Responses stream error event: ${error.message()}" }
                throw streamError(error.code().orElse("unknown"))
            }

            else -> {
                null
            }
        }

    /** Error for a stream that reported a failure with [code]; transient for server-side failures. */
    fun streamError(code: String): RuntimeException = providerError("OpenAI Responses stream error", code)

    private fun toChatResponse(
        response: Response,
        includeText: Boolean,
    ): ChatResponse {
        if (response.status().orElse(null) in UNANSWERED_STATUSES) throw failure(response)

        val contents = response.output().mapNotNull { it.message().orElse(null) }.flatMap { it.content() }
        val text = contents.mapNotNull { it.outputText().orElse(null)?.text() }.joinToString("")
        val refusal = contents.firstNotNullOfOrNull { it.refusal().orElse(null)?.refusal() }
        val toolCalls =
            response
                .output()
                .mapNotNull { it.functionCall().orElse(null) }
                .map { AssistantMessage.ToolCall(it.callId(), FUNCTION_CALL_TYPE, it.name(), it.arguments()) }

        val message =
            AssistantMessage
                .builder()
                .content(if (includeText) text.ifEmpty { refusal.orEmpty() } else "")
                .toolCalls(toolCalls)
                .build()
        val generationMetadata =
            ChatGenerationMetadata
                .builder()
                .finishReason(finishReasonOf(response, toolCalls.isNotEmpty()))
                .apply { refusal?.let { metadata("refusal", it) } }
                .build()
        val usage =
            response.usage().orElse(null)?.let {
                DefaultUsage(it.inputTokens().toInt(), it.outputTokens().toInt(), it.totalTokens().toInt(), it)
            }
        val metadata =
            ChatResponseMetadata
                .builder()
                .id(response.id())
                .model(modelNameOf(response))
                .apply { usage?.let { usage(it) } }
                .build()
        return ChatResponse(listOf(Generation(message, generationMetadata)), metadata)
    }

    private fun finishReasonOf(
        response: Response,
        hasToolCalls: Boolean,
    ): String =
        when (response.status().orElse(null)) {
            ResponseStatus.INCOMPLETE -> {
                val reason = response.incompleteDetails().orElse(null)?.reason()?.orElse(null)?.toString()
                when (reason) {
                    "max_output_tokens" -> "length"
                    "content_filter" -> "content_filter"
                    else -> reason ?: "incomplete"
                }
            }

            else -> {
                if (hasToolCalls) "tool_calls" else "stop"
            }
        }

    private fun modelNameOf(response: Response): String {
        val model = response.model()
        return model.string().orElse(null)
            ?: model.chat().orElse(null)?.toString()
            ?: model.only().orElse(null)?.toString()
            ?: model.toString()
    }

    /**
     * Error raised for a response that failed or was cancelled. The provider message stays in DEBUG
     * logs only, as for the Chat Completions path, because it may echo request content.
     */
    private fun failure(response: Response): RuntimeException {
        val error: ResponseError? = response.error().orElse(null)
        val code = error?.code()?.toString() ?: response.status().orElse(null)?.toString() ?: "unknown"
        logger.debug { "OpenAI Responses call ${response.id()} ended with $code: ${error?.message()}" }
        return providerError("OpenAI Responses call ended with", code)
    }

    private fun providerError(
        prefix: String,
        code: String,
    ): RuntimeException {
        val message = "$prefix $code"
        return if (code in TRANSIENT_ERROR_CODES) TransientAiException(message) else NonTransientAiException(message)
    }

    private const val FUNCTION_CALL_TYPE = "function"

    private val UNANSWERED_STATUSES = setOf(ResponseStatus.FAILED, ResponseStatus.CANCELLED)

    private val TRANSIENT_ERROR_CODES = setOf("server_error", "rate_limit_exceeded")
}
