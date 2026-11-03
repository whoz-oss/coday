package io.whozoss.agentos.util

import kotlinx.coroutines.CancellationException
import mu.KotlinLogging
import org.springframework.ai.retry.NonTransientAiException
import org.springframework.ai.retry.TransientAiException
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.core.Exceptions

private val logger = KotlinLogging.logger {}

/** Maximum number of response-body characters retained in diagnostic DEBUG logs. */
private const val MAX_BODY_CHARS = 4_000

/** Marker appended when the response body is truncated. */
private const val TRUNCATION_MARKER = "…[truncated]"

/** Upper bound on the text inspected to recognise a context overflow, to keep the matching cheap. */
private const val MAX_CLASSIFIED_CHARS = 100_000

/**
 * What providers say when a prompt does not fit: Anthropic `prompt is too long`, OpenAI and vLLM
 * `context_length_exceeded` / `maximum context length`, Gemini `exceeds the maximum number of tokens`
 * / `input token count`.
 */
private val CONTEXT_OVERFLOW_PATTERN =
    Regex(
        "prompt is too long|context_length_exceeded|maximum context length|" +
            "exceeds the maximum number of tokens|input token count",
        RegexOption.IGNORE_CASE,
    )

/** Anthropic: `prompt is too long: 213462 tokens > 200000 maximum` (prompt, then limit). */
private val ANTHROPIC_TOKENS_PATTERN = Regex("""(\d+)\s+tokens\s*>\s*(\d+)\s+maximum""", RegexOption.IGNORE_CASE)

/** OpenAI: `maximum context length is 8192 tokens. However, your messages resulted in 9000 tokens` (limit, then prompt). */
private val OPENAI_TOKENS_PATTERN =
    Regex("""maximum context length is (\d+) tokens.*?(\d+) tokens""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

/** Gemini: `input token count (1196265) exceeds the maximum number of tokens allowed (1048575)` (prompt, then limit). */
private val GEMINI_TOKENS_PATTERN =
    Regex("""input token count \((\d+)\) exceeds the maximum number of tokens allowed \((\d+)\)""", RegexOption.IGNORE_CASE)

/**
 * Unwraps the throwable chain (Reactor envelope + cause chain) looking for a
 * [WebClientResponseException] that signals a provider-side HTTP error on a
 * streaming call.
 *
 * Returns:
 * - [NonTransientAiException] for 4xx responses (the request is faulty — retrying is useless).
 * - [TransientAiException] for 5xx responses (provider-side failure — may be retried).
 * - `null` for anything else, including [CancellationException] (which must never be swallowed).
 *
 * Reactor frequently wraps exceptions via [Exceptions.propagate] or composite exceptions.
 * [Exceptions.unwrap] strips the Reactor envelope (one level). If the root cause is still
 * not a [WebClientResponseException], the function walks the [Throwable.cause] chain up to
 * [MAX_CAUSE_DEPTH] levels deep, covering nested wrapping patterns.
 *
 * [CancellationException] is never converted: Kotlin coroutine cancellation must propagate
 * unchanged so the cooperative cancellation protocol is respected.
 */
fun Throwable.unwrapToProviderAiException(): Exception? {
    // Coroutine cancellation must never be swallowed or converted.
    if (this is CancellationException) return null

    // Walk the cause chain, starting with the Reactor-unwrapped root.
    val unwrapped = Exceptions.unwrap(this)
    return findWebClientException(unwrapped)
        ?: findWebClientException(this)
}

/** Maximum depth when walking [Throwable.cause] chains. */
private const val MAX_CAUSE_DEPTH = 10

/**
 * Recursively walks the cause chain of [root] up to [MAX_CAUSE_DEPTH] levels,
 * returning the first [WebClientResponseException] found as a Spring AI exception,
 * or `null` when none is found.
 */
private fun findWebClientException(root: Throwable): Exception? {
    var current: Throwable? = root
    var depth = 0
    while (current != null && depth < MAX_CAUSE_DEPTH) {
        if (current is WebClientResponseException) {
            return current.toProviderAiException()
        }
        current = current.cause
        depth++
    }
    return null
}

/**
 * Converts a [WebClientResponseException] to the appropriate Spring AI exception type.
 *
 * The provider response body may contain sensitive data, so it is deliberately excluded
 * from the propagated exception message and retained only in a bounded DEBUG log.
 */
private fun WebClientResponseException.toProviderAiException(): Exception {
    logger.debug {
        val body = responseBodyAsString
            .take(MAX_BODY_CHARS + TRUNCATION_MARKER.length)
            .let { raw ->
                if (raw.length > MAX_BODY_CHARS) raw.take(MAX_BODY_CHARS) + TRUNCATION_MARKER else raw
            }
            .ifBlank { "<empty body>" }
        "Provider error response body for $statusCode from ${request?.method} ${request?.uri}: $body"
    }
    val message = "$statusCode from ${request?.method} ${request?.uri}"
    return when {
        statusCode.is4xxClientError -> contextOverflowOrNull() ?: NonTransientAiException(message, this)
        else -> TransientAiException(message, this)
    }
}

/** The overflow carried by this 4xx response, recognised from its body; the body is not copied into the message. */
private fun WebClientResponseException.contextOverflowOrNull(): ContextWindowExceededException? =
    classifyContextOverflow(
        text = responseBodyAsString,
        origin = "$statusCode from ${request?.method} ${request?.uri}",
        cause = this,
    )

/**
 * Recognises a context-window overflow in [text] (a provider response body or error message) and returns
 * it as a [ContextWindowExceededException], or `null` when [text] says something else. Token counts are
 * parsed on a best-effort basis. [origin] is the only text copied into the exception message.
 */
private fun classifyContextOverflow(
    text: String,
    origin: String,
    cause: Throwable?,
): ContextWindowExceededException? {
    val inspected = text.take(MAX_CLASSIFIED_CHARS)
    if (!CONTEXT_OVERFLOW_PATTERN.containsMatchIn(inspected)) return null
    val (promptTokens, maxTokens) = parseTokenCounts(inspected)
    val counts = if (promptTokens != null && maxTokens != null) " ($promptTokens > $maxTokens tokens)" else ""
    return ContextWindowExceededException("Context window exceeded$counts: $origin", cause, promptTokens, maxTokens)
}

/** Prompt and limit token counts stated in [text], each `null` when absent. */
private fun parseTokenCounts(text: String): Pair<Long?, Long?> =
    ANTHROPIC_TOKENS_PATTERN.find(text)?.let { it.groupValues[1].toLongOrNull() to it.groupValues[2].toLongOrNull() }
        ?: OPENAI_TOKENS_PATTERN.find(text)?.let { it.groupValues[2].toLongOrNull() to it.groupValues[1].toLongOrNull() }
        ?: GEMINI_TOKENS_PATTERN.find(text)?.let { it.groupValues[1].toLongOrNull() to it.groupValues[2].toLongOrNull() }
        ?: (null to null)

/**
 * Returns the [ContextWindowExceededException] this throwable stands for, or `null` when it is not a
 * context-window overflow. Covers both ways a provider 4xx reaches an agent:
 * - streaming: a [WebClientResponseException] buried under Reactor envelopes, classified from its body;
 * - blocking `call()`: Spring AI's own error handler throws a [NonTransientAiException] whose *message*
 *   holds the body. It is recognised from that message, and a clean exception is built: the original
 *   message is never copied, and the original exception is not kept as cause, so the body cannot leak
 *   through the new exception or the logs that print it.
 *
 * An already classified exception is returned as is. [CancellationException] is never converted.
 */
fun Throwable.asContextWindowExceeded(): ContextWindowExceededException? {
    if (this is CancellationException) return null

    var current: Throwable? = Exceptions.unwrap(this)
    var depth = 0
    while (current != null && depth < MAX_CAUSE_DEPTH) {
        val node: Throwable = current
        val overflow =
            when (node) {
                is CancellationException -> return null
                is ContextWindowExceededException -> node
                is WebClientResponseException -> if (node.statusCode.is4xxClientError) node.contextOverflowOrNull() else null
                is NonTransientAiException ->
                    classifyContextOverflow(text = node.message.orEmpty(), origin = "rejected by the AI provider", cause = null)
                else -> null
            }
        if (overflow != null) return overflow
        current = node.cause
        depth++
    }
    return null
}
