package io.whozoss.agentos.plugins.ktlint

import io.whozoss.agentos.sdk.tool.ToolExecutionResult

/** Error type of a ktlint run that found violations still to fix. */
internal const val KTLINT_FAILURE = "KTLINT_FAILURE"

/** Error type of a ktlint tool call that could not do its job. */
internal const val KTLINT_ERROR = "KTLINT_ERROR"

/**
 * Outcome of a ktlint tool call, decided where it is known and never guessed back from the text.
 *
 * - [Success]: ktlint ran and the code is clean (or was fully autocorrected).
 * - [Failure]: ktlint ran and found violations still to fix: [text] is its report, the answer the agent needs.
 * - [Error]: the tool could not do its job (bad input, path refused, timeout, interruption): [text] says why.
 */
sealed interface KtlintToolResult {
    val text: String

    data class Success(
        override val text: String,
    ) : KtlintToolResult

    data class Failure(
        override val text: String,
    ) : KtlintToolResult

    data class Error(
        override val text: String,
    ) : KtlintToolResult

    companion object {
        /** [Success] when there is no violation left to report, [Failure] otherwise. */
        fun ofViolations(
            remaining: Int,
            text: String,
        ): KtlintToolResult = if (remaining == 0) Success(text) else Failure(text)
    }
}

/** `Success` -> success, `Failure` -> `KTLINT_FAILURE`, `Error` -> `KTLINT_ERROR` (its text is prefixed with `ERROR: `). */
internal fun KtlintToolResult.toToolExecutionResult(): ToolExecutionResult =
    when (this) {
        is KtlintToolResult.Success -> ToolExecutionResult.success(output = text)
        is KtlintToolResult.Failure -> ToolExecutionResult.error(output = text, errorType = KTLINT_FAILURE)
        is KtlintToolResult.Error -> ToolExecutionResult.error(output = "ERROR: $text", errorType = KTLINT_ERROR)
    }

/** The agent did not provide the mandatory `timeoutSeconds`: nothing is run, the answer names the parameter. */
internal fun missingTimeoutResult(): ToolExecutionResult =
    KtlintToolResult.Error(text = KtlintToolTexts.MISSING_TIMEOUT_MESSAGE).toToolExecutionResult()
