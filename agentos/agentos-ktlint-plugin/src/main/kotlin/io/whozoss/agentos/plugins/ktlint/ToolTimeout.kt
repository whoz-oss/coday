package io.whozoss.agentos.plugins.ktlint

/**
 * The mandatory `timeoutSeconds` of the ktlint tools: the agent must always say how long a run may last, within
 * [1, [MAX_SECONDS]]. The ktlint run stops when it is exceeded.
 */
internal object ToolTimeout {
    /** Kept under the 5 minutes of the context cache, with a margin: a call that outlives it makes the next turn pay the cache again. */
    const val MAX_SECONDS = 270

    /** Start of the `timeoutSeconds` param description; each tool appends its own sizing advice. */
    const val PARAM_DESCRIPTION =
        "Mandatory timeout in seconds, from 1 to $MAX_SECONDS: the ktlint run stops when it is exceeded. "

    /** Message returned when the agent did not provide `timeoutSeconds` at all. */
    const val MISSING_MESSAGE =
        "'timeoutSeconds' is mandatory (1 to $MAX_SECONDS): the ktlint run stops when it is exceeded."

    /** @return a message naming `timeoutSeconds` and the allowed range when [seconds] is outside it, null otherwise. */
    fun validate(seconds: Int): String? =
        if (seconds in 1..MAX_SECONDS) {
            null
        } else {
            "'timeoutSeconds' must be between 1 and $MAX_SECONDS, got $seconds."
        }
}
