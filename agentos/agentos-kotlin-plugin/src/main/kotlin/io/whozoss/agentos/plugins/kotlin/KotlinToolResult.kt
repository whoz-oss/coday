package io.whozoss.agentos.plugins.kotlin

import io.whozoss.agentos.sdk.tool.ToolExecutionResult

/** Error type of a call where the server answered with problems to fix, e.g. compile errors in the diagnostics. */
internal const val KOTLIN_FAILURE = "KOTLIN_FAILURE"

/** Error type of a call that could not do its job. */
internal const val KOTLIN_ERROR = "KOTLIN_ERROR"

/**
 * Outcome of a Kotlin LSP tool call, decided where it is known and never guessed back from the text.
 *
 * - [Success]: the server answered, [text] is the answer.
 * - [Failure]: the server answered and reports problems to fix (diagnostics with errors): [text] is the report.
 * - [Error]: the tool could not do its job (bad input, path refused, symbol not resolved, server unavailable,
 *   timeout, interruption): [text] says why and how to retry.
 */
internal sealed interface KotlinToolResult {
    val text: String

    /** The server answered. */
    data class Success(
        override val text: String,
    ) : KotlinToolResult

    /** The server answered and reports problems to fix. */
    data class Failure(
        override val text: String,
    ) : KotlinToolResult

    /** The tool could not do its job. */
    data class Error(
        override val text: String,
    ) : KotlinToolResult
}

/** `Success` -> success, `Failure` -> `KOTLIN_FAILURE`, `Error` -> `KOTLIN_ERROR` (its text is prefixed with `ERROR: `). */
internal fun KotlinToolResult.toToolExecutionResult(): ToolExecutionResult =
    when (this) {
        is KotlinToolResult.Success -> ToolExecutionResult.success(output = text)
        is KotlinToolResult.Failure -> ToolExecutionResult.error(output = text, errorType = KOTLIN_FAILURE)
        is KotlinToolResult.Error -> ToolExecutionResult.error(output = "ERROR: $text", errorType = KOTLIN_ERROR)
    }

/** The agent did not provide the mandatory `timeoutSeconds`: nothing is run, the answer names the parameter. */
internal fun missingTimeoutResult(): ToolExecutionResult =
    KotlinToolResult.Error(text = KotlinToolTexts.MISSING_TIMEOUT_MESSAGE).toToolExecutionResult()

/** An [KotlinToolResult.Error] naming [name] when [value] is missing or blank, null otherwise. */
internal fun blankError(
    value: String?,
    name: String,
): KotlinToolResult.Error? = if (value.isNullOrBlank()) KotlinToolResult.Error(text = "`$name` is required") else null
