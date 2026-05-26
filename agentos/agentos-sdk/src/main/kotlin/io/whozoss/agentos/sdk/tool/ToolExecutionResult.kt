package io.whozoss.agentos.sdk.tool

/**
 * Result of a [StandardTool] execution.
 *
 * Carries the textual output the LLM will see, optional opaque metadata for
 * the service layer, and observability fields (success flag, error details).
 *
 * [metadata] is intentionally untyped ([Map]<[String], [Any?]>) — each integration
 * decides what to store there. Typical use cases: entity references for coherence
 * checks on subsequent tool calls, pagination cursors, session tokens.
 * The service layer persists [metadata] alongside the [io.whozoss.agentos.sdk.caseEvent.ToolResponseEvent]
 * so that later tool invocations in the same case can read it back via
 * [ToolContext.caseEvents].
 *
 * Use the companion factory methods for the common cases:
 * ```kotlin
 * // happy path, no metadata
 * return ToolExecutionResult.success("result text")
 *
 * // happy path, with metadata
 * return ToolExecutionResult.success("result text", mapOf("entityId" to id))
 *
 * // error
 * return ToolExecutionResult.error("Something went wrong", errorType = "NOT_FOUND")
 * ```
 */
data class ToolExecutionResult(
    val output: String,
    val success: Boolean,
    val metadata: Map<String, Any?> = emptyMap(),
    val errorType: String? = null,
    val errorMessage: String? = null,
) {
    companion object {
        fun success(
            output: String,
            metadata: Map<String, Any?> = emptyMap(),
        ): ToolExecutionResult =
            ToolExecutionResult(
                output = output,
                success = true,
                metadata = metadata,
            )

        fun error(
            output: String,
            errorType: String? = null,
            errorMessage: String? = null,
        ): ToolExecutionResult =
            ToolExecutionResult(
                output = output,
                success = false,
                errorType = errorType,
                errorMessage = errorMessage,
            )
    }
}
