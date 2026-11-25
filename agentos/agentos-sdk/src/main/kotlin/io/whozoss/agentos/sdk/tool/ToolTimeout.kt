package io.whozoss.agentos.sdk.tool

/**
 * The mandatory `timeoutSeconds` of the tools that run something which can last: a build, a style check...
 * The agent must always say how long a run may last, within [1, [MAX_SECONDS]].
 *
 * The bound and the way it is worded live here so that every such tool states the same limit. What happens to the
 * run on expiry depends on the tool (a process is killed, an in-process engine stops between two files): each tool
 * says it with the `onExpiry` text, e.g. `"the Gradle run is killed"`.
 */
object ToolTimeout {
    /** Kept under the 5 minutes of the context cache, with a margin: a call that outlives it makes the next turn pay the cache again. */
    const val MAX_SECONDS = 270

    /** Start of the `timeoutSeconds` param description, [onExpiry] telling what happens on expiry; each tool appends its own sizing advice. */
    fun paramDescription(onExpiry: String): String = "Mandatory timeout in seconds, from 1 to $MAX_SECONDS: $onExpiry when it is exceeded. "

    /** Message returned when the agent did not provide `timeoutSeconds` at all. */
    fun missingMessage(onExpiry: String): String = "'timeoutSeconds' is mandatory (1 to $MAX_SECONDS): $onExpiry when it is exceeded."

    /** @return a message naming `timeoutSeconds` and the allowed range when [seconds] is outside it, null otherwise. */
    fun validate(seconds: Int): String? =
        when (seconds) {
            in 1..MAX_SECONDS -> null
            else -> "'timeoutSeconds' must be between 1 and $MAX_SECONDS, got $seconds."
        }
}
