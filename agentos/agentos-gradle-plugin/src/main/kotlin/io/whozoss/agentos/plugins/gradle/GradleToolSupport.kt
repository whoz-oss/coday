package io.whozoss.agentos.plugins.gradle

import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import io.whozoss.agentos.sdk.tool.ToolTimeout

/**
 * Outcome of a Gradle tool call, decided where it is known and never guessed back from the text.
 *
 * - [Success]: Gradle ran and succeeded.
 * - [Failure]: Gradle ran and failed (build, compilation or tests): [text] is its report, the answer the agent needs.
 * - [Error]: the tool could not do its job (bad input, no `gradlew`, timeout, interruption): [text] says why.
 */
sealed interface GradleToolResult {
    val text: String

    data class Success(
        override val text: String,
    ) : GradleToolResult

    data class Failure(
        override val text: String,
    ) : GradleToolResult

    data class Error(
        override val text: String,
    ) : GradleToolResult

    companion object {
        /** [Success] when Gradle exited with 0, [Failure] otherwise. */
        fun ofExit(
            exitCode: Int,
            text: String,
        ): GradleToolResult = if (exitCode == 0) Success(text) else Failure(text)
    }
}

/** `Success` -> success, `Failure` -> `GRADLE_FAILURE`, `Error` -> `GRADLE_ERROR` (its text is prefixed with `ERROR: `). */
internal fun GradleToolResult.toToolExecutionResult(): ToolExecutionResult =
    when (this) {
        is GradleToolResult.Success -> ToolExecutionResult.success(text)
        is GradleToolResult.Failure -> ToolExecutionResult.error(text, errorType = "GRADLE_FAILURE")
        is GradleToolResult.Error -> ToolExecutionResult.error("ERROR: $text", errorType = "GRADLE_ERROR")
    }

/** What happens to a Gradle run whose time box expires, as told to the agent. */
private const val ON_EXPIRY = "the Gradle run is killed"

/** Start of the `timeoutSeconds` param description of the Gradle tools; each tool appends its own sizing advice. */
internal val TIMEOUT_PARAM_DESCRIPTION: String = ToolTimeout.paramDescription(onExpiry = ON_EXPIRY)

/** The agent did not provide the mandatory `timeoutSeconds`: nothing is run, the answer names the parameter. */
internal fun missingTimeoutResult(): ToolExecutionResult =
    GradleToolResult.Error(ToolTimeout.missingMessage(onExpiry = ON_EXPIRY)).toToolExecutionResult()
