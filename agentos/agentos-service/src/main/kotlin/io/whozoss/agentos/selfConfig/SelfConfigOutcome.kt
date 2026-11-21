package io.whozoss.agentos.selfConfig

/**
 * The outcome of a `SELF_CONFIG` operation: either a value, or a named failure that the calling
 * tool turns into a [io.whozoss.agentos.sdk.tool.ToolExecutionResult.error].
 *
 * It exists so that **no exception ever escapes into an agent run**. An exception thrown inside
 * tool execution is caught far from here and reported without the detail that would let the LLM
 * recover; a named [Failure] is, instead, feedback the LLM can read and correct its next call
 * from.
 */
sealed interface SelfConfigOutcome<out T> {
    data class Ok<T>(val value: T) : SelfConfigOutcome<T>

    data class Failure(val errorType: String, val message: String) : SelfConfigOutcome<Nothing>
}

/** Error types produced by [SelfConfigToolService]; shared with the tools and their specs. */
object SelfConfigErrors {
    /** The run carries no identified user: there is no personal configuration to act on. */
    const val NOT_AUTHENTICATED = "NOT_AUTHENTICATED"

    /** The target row exists and is readable, but is shared (platform or namespace) rather than personal. */
    const val SCOPE_NOT_ALLOWED = "SCOPE_NOT_ALLOWED"

    const val NOT_FOUND = "NOT_FOUND"
    const val ACCESS_DENIED = "ACCESS_DENIED"
    const val CONFLICT = "CONFLICT"
    const val INVALID_INPUT = "INVALID_INPUT"
    const val UNEXPECTED = "UNEXPECTED_ERROR"
    const val MISSING_INPUT = "MISSING_INPUT"
}
