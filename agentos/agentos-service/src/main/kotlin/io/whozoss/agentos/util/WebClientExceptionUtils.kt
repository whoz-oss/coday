package io.whozoss.agentos.util

import kotlinx.coroutines.CancellationException
import mu.KotlinLogging
import org.springframework.ai.retry.NonTransientAiException
import org.springframework.ai.retry.TransientAiException
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.core.Exceptions

private val logger = KotlinLogging.logger {}

/** Maximum number of response-body characters retained in diagnostic logs and in the user-facing message. */
private const val MAX_BODY_CHARS = 4_000

/** Marker appended when the response body is truncated. */
private const val TRUNCATION_MARKER = "…[truncated]"

/** Replacement of a masked secret. */
private const val MASK = "***"

/** Separates the status line from the provider body in the propagated message. */
private const val BODY_PREFIX = " — provider response: "

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
 * The message is `"<status> from <method> <uri>"`. When [exposeBody] is true and the provider answered with a
 * body, ` — provider response: <body>` follows, the body being trimmed, whitespace-collapsed, secret-masked and
 * bounded (see [sanitizeProviderBody]). When [exposeBody] is false (the default) the body is never part of the
 * message, and is retained only in a bounded DEBUG log.
 *
 * Reactor frequently wraps exceptions via [Exceptions.propagate] or composite exceptions.
 * [Exceptions.unwrap] strips the Reactor envelope (one level). If the root cause is still
 * not a [WebClientResponseException], the function walks the [Throwable.cause] chain up to
 * [MAX_CAUSE_DEPTH] levels deep, covering nested wrapping patterns.
 *
 * [CancellationException] is never converted: Kotlin coroutine cancellation must propagate
 * unchanged so the cooperative cancellation protocol is respected.
 */
fun Throwable.unwrapToProviderAiException(exposeBody: Boolean = false): Exception? {
    // Coroutine cancellation must never be swallowed or converted.
    if (this is CancellationException) return null

    // Walk the cause chain, starting with the Reactor-unwrapped root.
    val unwrapped = Exceptions.unwrap(this)
    return findWebClientException(unwrapped, exposeBody)
        ?: findWebClientException(this, exposeBody)
}

/** Maximum depth when walking [Throwable.cause] chains. */
private const val MAX_CAUSE_DEPTH = 10

/**
 * Recursively walks the cause chain of [root] up to [MAX_CAUSE_DEPTH] levels,
 * returning the first [WebClientResponseException] found as a Spring AI exception,
 * or `null` when none is found.
 */
private fun findWebClientException(
    root: Throwable,
    exposeBody: Boolean,
): Exception? {
    var current: Throwable? = root
    var depth = 0
    while (current != null && depth < MAX_CAUSE_DEPTH) {
        if (current is WebClientResponseException) {
            return current.toProviderAiException(exposeBody)
        }
        current = current.cause
        depth++
    }
    return null
}

/**
 * Converts a [WebClientResponseException] to the appropriate Spring AI exception type.
 *
 * The provider response body may contain sensitive data: it is part of the propagated message only when
 * [exposeBody] is true (`agentos.ai-provider-errors.expose-response-body`), and then only sanitized by
 * [sanitizeProviderBody]. Whatever the flag, the (unmasked) body goes to a bounded DEBUG log.
 */
private fun WebClientResponseException.toProviderAiException(exposeBody: Boolean): Exception {
    val rawBody = responseBodyAsString
    logger.debug {
        val body =
            rawBody
                .take(MAX_BODY_CHARS + TRUNCATION_MARKER.length)
                .let { raw ->
                    if (raw.length > MAX_BODY_CHARS) raw.take(MAX_BODY_CHARS) + TRUNCATION_MARKER else raw
                }.ifBlank { "<empty body>" }
        "Provider error response body for $statusCode from ${request?.method} ${request?.uri}: $body"
    }
    val statusLine = "$statusCode from ${request?.method} ${request?.uri}"
    val sanitizedBody = if (exposeBody) sanitizeProviderBody(rawBody) else ""
    val message = if (sanitizedBody.isEmpty()) statusLine else "$statusLine$BODY_PREFIX$sanitizedBody"
    return when {
        statusCode.is4xxClientError -> NonTransientAiException(message, this)
        else -> TransientAiException(message, this)
    }
}

private val BEARER_TOKEN = Regex("""(?i)\bBearer\s+[A-Za-z0-9._~+/=-]+""")

/** `sk-...`-style keys: OpenAI, Anthropic (`sk-ant-`), OpenRouter (`sk-or-`), Requesty... */
private val SK_KEY = Regex("""\bsk-[A-Za-z0-9_-]{8,}""")

/** A JSON string value whose field name looks like a credential: `"api_key": "..."`, `"apiKey"`, `"Authorization"`... */
private val SECRET_JSON_FIELD =
    Regex(
        """(?i)("[\w.-]*(?:api[_-]?key|authorization|secret|password|access[_-]?token)[\w.-]*"\s*:\s*)"(?:[^"\\]|\\.)*"""",
    )

/** Plain-text `api_key=...` / `authorization: ...` pairs, as found in non-JSON error pages. */
private val SECRET_KEY_VALUE =
    Regex("""(?i)\b(api[_-]?key|authorization)(\s*[=:]\s*)[^\s,;"']+""")

private val WHITESPACE = Regex("""\s+""")

/**
 * Makes a provider response body fit to be shown to the users of a case.
 *
 * In order: masks the secrets (`Bearer <token>`, `sk-...` keys, JSON fields named like `api_key`, `apiKey`,
 * `authorization`, `secret`, `password`, `access_token`, plain `api_key=...` pairs), collapses every run of
 * whitespace to one space so that a pretty-printed body stays readable on one line, trims, and bounds the result
 * to [MAX_BODY_CHARS] characters followed by a truncation marker. Masking comes first so that a secret cut by the
 * bound cannot escape the patterns.
 *
 * Returns an empty string for a blank body.
 */
internal fun sanitizeProviderBody(raw: String): String {
    val masked =
        raw
            .replace(BEARER_TOKEN, "Bearer $MASK")
            .replace(SK_KEY, "sk-$MASK")
            .replace(SECRET_JSON_FIELD, "$1\"$MASK\"")
            .replace(SECRET_KEY_VALUE, "$1$2$MASK")
    val compact = masked.replace(WHITESPACE, " ").trim()
    return if (compact.length > MAX_BODY_CHARS) compact.take(MAX_BODY_CHARS) + TRUNCATION_MARKER else compact
}
