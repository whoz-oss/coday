package io.whozoss.agentos.plugins.gradle

/**
 * The mandatory `timeoutSeconds` of the Gradle tools: the agent must always say how long a run may last, within
 * [1, [MAX_SECONDS]]. The run is killed when it is exceeded.
 */
internal object ToolTimeout {
    const val MAX_SECONDS = 300

    /** Start of the `timeoutSeconds` param description; each tool appends its own sizing advice. */
    const val PARAM_DESCRIPTION =
        "Mandatory timeout in seconds, from 1 to $MAX_SECONDS: the Gradle run is killed when it is exceeded. "

    /** Message returned when the agent did not provide `timeoutSeconds` at all. */
    const val MISSING_MESSAGE =
        "'timeoutSeconds' is mandatory (1 to $MAX_SECONDS): the Gradle run is killed when it is exceeded."

    /** @return a message naming `timeoutSeconds` and the allowed range when [seconds] is outside it, null otherwise. */
    fun validate(seconds: Int): String? =
        if (seconds in 1..MAX_SECONDS) {
            null
        } else {
            "'timeoutSeconds' must be between 1 and $MAX_SECONDS, got $seconds."
        }
}
