package io.whozoss.agentos.sdk.tool

/**
 * Result of tool execution with metadata for tracking and observability.
 */
data class ToolExecutionResult(
    val toolName: String,
    val success: Boolean,
    val output: String,
    val executionDurationMs: Long,
    val errorType: String? = null,
    val errorMessage: String? = null,
)
