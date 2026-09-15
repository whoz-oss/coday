package io.whozoss.agentos.util

import kotlinx.coroutines.CancellationException
import org.springframework.ai.retry.NonTransientAiException
import org.springframework.ai.retry.TransientAiException
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.core.Exceptions

/** Maximum number of characters retained from the HTTP response body in the error message. */
private const val MAX_BODY_CHARS = 4_000

/** Marker appended when the response body is truncated. */
private const val TRUNCATION_MARKER = "…[truncated]"

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
 * Converts a [WebClientResponseException] to the appropriate Spring AI exception type,
 * embedding the response body in the message (bounded to [MAX_BODY_CHARS]).
 *
 * The body is what the provider sent as its error description — exactly the information
 * that is otherwise lost on the streaming path.
 */
private fun WebClientResponseException.toProviderAiException(): Exception {
    val body = responseBodyAsString
        .take(MAX_BODY_CHARS + TRUNCATION_MARKER.length)
        .let { raw ->
            if (raw.length > MAX_BODY_CHARS) raw.take(MAX_BODY_CHARS) + TRUNCATION_MARKER else raw
        }
        .ifBlank { "<empty body>" }
    val message = "${statusCode} from ${request?.method} ${request?.uri}: $body"
    return when {
        statusCode.is4xxClientError -> NonTransientAiException(message, this)
        else -> TransientAiException(message, this)
    }
}
