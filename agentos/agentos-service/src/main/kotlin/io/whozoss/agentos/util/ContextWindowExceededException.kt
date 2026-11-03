package io.whozoss.agentos.util

import org.springframework.ai.retry.NonTransientAiException

/**
 * The provider rejected a request because the prompt does not fit in the model's context window.
 *
 * It extends [NonTransientAiException] on purpose: retrying the same payload is useless, and every
 * existing `catch (e: NonTransientAiException)` keeps applying. Only code that cares about the
 * difference (the agents' error reporting, a future compactor) looks at this subtype.
 *
 * The provider response body never appears in [message] (it can hold sensitive data): the token
 * counts parsed from it are carried in [promptTokens] and [maxTokens] instead. Both are `null` when
 * the provider did not state them.
 */
class ContextWindowExceededException(
    message: String,
    cause: Throwable? = null,
    /** Size of the rejected prompt, in tokens, when the provider stated it. */
    val promptTokens: Long? = null,
    /** Context window of the model, in tokens, when the provider stated it. */
    val maxTokens: Long? = null,
) : NonTransientAiException(message, cause)
