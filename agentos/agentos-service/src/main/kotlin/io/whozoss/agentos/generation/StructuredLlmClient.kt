package io.whozoss.agentos.generation

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.chat.ChatClientProvider
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import mu.KLogging
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt
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
 * Shared plumbing of the generation features that ask an LLM for a small JSON object (loop-config generation,
 * scheduled-prompt generation): one non-streaming call with a system prompt and a user message, and a tolerant
 * parse of the answer.
 *
 * ## Why the LLM is invoked directly (not via ChatClient.stream)
 *
 * The call is short, synchronous and non-streaming, and its result is a small JSON object:
 * `ChatClient.prompt().call().content()` is the idiomatic Spring AI path for that shape. The client is created with
 * no case id and no usage accumulator: these calls belong to no case.
 *
 * ## Parsing
 *
 * 1. The whole answer is parsed as a JSON **object**.
 * 2. Failing that, the content of the first ```` ``` ```` / ```` ```json ```` fence is parsed, and
 *    [StructuredLlmResult.usedCodeFence] is set so the caller can warn about it.
 *
 * An answer that holds no object leaves [StructuredLlmResult.json] null and keeps the raw text, for the caller to
 * report. Exceptions of the LLM call itself are not caught.
 */
@Component
class StructuredLlmClient(
    private val chatClientProvider: ChatClientProvider,
    private val objectMapper: ObjectMapper,
) {
    fun call(
        model: AiModel,
        provider: AiProvider,
        systemPrompt: String,
        userMessage: String,
    ): StructuredLlmResult {
        val chatClient =
            chatClientProvider.getChatClient(
                modelConfig = model,
                providerConfig = provider,
                caseId = null,
                accumulator = null,
            )
        val content =
            chatClient
                .prompt(Prompt(listOf(SystemMessage(systemPrompt), UserMessage(userMessage))))
                .call()
                .content()

        if (content.isNullOrBlank()) {
            logger.warn { "[StructuredLlm] The LLM returned no content (model=${model.apiModelName})" }
            return StructuredLlmResult(json = null, rawText = null)
        }
        logger.debug { "[StructuredLlm] LLM raw response (${content.length} chars): $content" }
        return parse(content)
    }

    internal fun parse(text: String): StructuredLlmResult {
        tryParseObject(text)?.let { return StructuredLlmResult(json = it, rawText = text) }

        extractFromCodeFence(text)?.let { fenced ->
            tryParseObject(fenced)?.let {
                logger.info { "[StructuredLlm] Parsed the answer via the code-fence fallback" }
                return StructuredLlmResult(json = it, rawText = text, usedCodeFence = true)
            }
        }

        logger.warn { "[StructuredLlm] The answer holds no JSON object. Raw: $text" }
        return StructuredLlmResult(json = null, rawText = text)
    }

    /** Parses [text] as a JSON object, returning null on any parse error or when it is another kind of JSON. */
    private fun tryParseObject(text: String): JsonNode? =
        runCatching { objectMapper.readTree(text.trim()) }.getOrNull()?.takeIf { it.isObject }

    /**
     * Extracts the content inside the first triple-backtick code fence, if present.
     * Handles both ```` ```json ```` and bare ```` ``` ```` fences. Null when there is no complete fence.
     */
    private fun extractFromCodeFence(text: String): String? {
        val fenceStart = CODE_FENCE_REGEX.find(text) ?: return null
        val contentStart = fenceStart.range.last + 1
        val fenceEnd = text.indexOf("```", contentStart)
        if (fenceEnd == -1) return null
        return text.substring(contentStart, fenceEnd).trim()
    }

    companion object : KLogging() {
        private val CODE_FENCE_REGEX = Regex("```(?:json)?\\s*\\n?")
    }
}
