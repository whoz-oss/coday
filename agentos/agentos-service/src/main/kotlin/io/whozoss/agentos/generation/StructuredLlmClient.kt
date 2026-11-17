package io.whozoss.agentos.generation

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.chat.ChatClientProvider
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import mu.KLogging
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Outcome of a [StructuredLlmClient.call].
 *
 * @param json The JSON object the LLM answered with, or null when its answer holds none.
 * @param rawText The raw text of the answer, or null when the LLM returned nothing (null or blank).
 * @param usedCodeFence True when [json] was only found by the markdown code-fence fallback, i.e. the LLM wrapped
 *   its answer in a fence although it was asked not to.
 */
data class StructuredLlmResult(
    val json: JsonNode?,
    val rawText: String?,
    val usedCodeFence: Boolean = false,
) {
    /** True when the LLM answered with no content at all. */
    val returnedNothing: Boolean get() = rawText == null
}

/**
 * Shared plumbing of the generation features: one non-streaming LLM call with a system prompt and a user message.
 * [call] asks for a small JSON object and parses the answer tolerantly (loop-config generation, scheduled-prompt
 * generation); [callText] returns the answer as plain text, for outputs that are prose, such as a Markdown summary,
 * which do not survive being wrapped in a JSON string (models flatten or escape their line breaks).
 *
 * ## Threading and timeout
 *
 * `ChatClient.prompt().call().content()` is blocking. [call] is `suspend` and runs it on the bounded generation
 * dispatcher ([GenerationConfiguration]), so a caller in a coroutine (the `suspend` controller endpoints) never
 * blocks its own thread, and generation can never occupy more than a fixed number of IO threads.
 *
 * The call is bounded by [timeoutSeconds] (`agentos.generation.timeout-seconds`): past it, the request fails with
 * [GenerationTimeoutException] (HTTP 504). The underlying HTTP call is not interrupted (Spring AI's blocking call
 * ignores coroutine cancellation): it runs to completion on its generation thread, which is why that pool is bounded.
 *
 * The client is created with no case id and no usage accumulator: these calls belong to no case.
 *
 * ## Parsing
 *
 * 1. The whole answer is parsed as a JSON **object**.
 * 2. Failing that, the content of the first ```` ``` ```` / ```` ```json ```` fence is parsed, and
 *    [StructuredLlmResult.usedCodeFence] is set so the caller can warn about it.
 *
 * An answer that holds no object leaves [StructuredLlmResult.json] null and keeps the raw text, for the caller to
 * report. Exceptions of the LLM call itself are not caught.
 *
 * ## Logging
 *
 * The content of the answer is never logged, only its length: it can echo agent descriptions, tool schemas and
 * user-written descriptions, which do not belong in centrally shipped logs.
 */
@Component
class StructuredLlmClient(
    private val chatClientProvider: ChatClientProvider,
    private val objectMapper: ObjectMapper,
    @Qualifier(GenerationConfiguration.GENERATION_DISPATCHER) private val dispatcher: CoroutineDispatcher,
    @Value("\${agentos.generation.timeout-seconds:${GenerationConfiguration.DEFAULT_TIMEOUT_SECONDS}}")
    private val timeoutSeconds: Long = GenerationConfiguration.DEFAULT_TIMEOUT_SECONDS,
) {
    suspend fun call(
        model: AiModel,
        provider: AiProvider,
        systemPrompt: String,
        userMessage: String,
    ): StructuredLlmResult {
        val content = complete(model, provider, systemPrompt, userMessage)
        val result =
            when {
                content.isNullOrBlank() -> StructuredLlmResult(json = null, rawText = null)
                else -> parse(content)
            }
        logger.debug {
            "[StructuredLlm] model=${model.apiModelName}, answer length=${content?.length ?: 0}, " +
                "json=${result.json != null}, codeFence=${result.usedCodeFence}"
        }
        return result
    }

    /** The answer as plain text, trimmed; null when the LLM returned nothing. Same threading and timeout as [call]. */
    suspend fun callText(
        model: AiModel,
        provider: AiProvider,
        systemPrompt: String,
        userMessage: String,
    ): String? {
        val content = complete(model, provider, systemPrompt, userMessage)?.trim()?.takeIf { it.isNotEmpty() }
        logger.debug { "[StructuredLlm] model=${model.apiModelName}, text answer length=${content?.length ?: 0}" }
        return content
    }

    /** The blocking LLM call, on the generation dispatcher and bounded by [timeoutSeconds]. */
    private suspend fun complete(
        model: AiModel,
        provider: AiProvider,
        systemPrompt: String,
        userMessage: String,
    ): String? {
        val answer =
            withTimeoutOrNull(timeoutSeconds * 1_000) {
                withContext(dispatcher) {
                    Answer(
                        chatClientProvider
                            .getChatClient(modelConfig = model, providerConfig = provider, caseId = null, accumulator = null)
                            .prompt(Prompt(listOf(SystemMessage(systemPrompt), UserMessage(userMessage))))
                            .call()
                            .content(),
                    )
                }
            } ?: throw GenerationTimeoutException("The LLM (${model.apiModelName}) did not answer within ${timeoutSeconds}s.")
        return answer.content
    }

    /** Wraps the LLM content, which may itself be null, so that null from [withTimeoutOrNull] only means a timeout. */
    private class Answer(
        val content: String?,
    )

    internal fun parse(text: String): StructuredLlmResult {
        val direct = tryParseObject(text)
        val fenced = if (direct == null) extractFromCodeFence(text)?.let { tryParseObject(it) } else null
        return StructuredLlmResult(json = direct ?: fenced, rawText = text, usedCodeFence = fenced != null)
    }

    /** Parses [text] as a JSON object, returning null on any parse error or when it is another kind of JSON. */
    private fun tryParseObject(text: String): JsonNode? =
        runCatching { objectMapper.readTree(text.trim()) }.getOrNull()?.takeIf { it.isObject }

    /**
     * Extracts the content inside the first triple-backtick code fence, if present.
     * Handles both ```` ```json ```` and bare ```` ``` ```` fences. Null when there is no complete fence.
     */
    private fun extractFromCodeFence(text: String): String? {
        val contentStart = CODE_FENCE_REGEX.find(text)?.let { it.range.last + 1 }
        val fenceEnd = contentStart?.let { text.indexOf("```", it) }?.takeIf { it != -1 }
        return when {
            contentStart == null || fenceEnd == null -> null
            else -> text.substring(contentStart, fenceEnd).trim()
        }
    }

    companion object : KLogging() {
        private val CODE_FENCE_REGEX = Regex("```(?:json)?\\s*\\n?")
    }
}
