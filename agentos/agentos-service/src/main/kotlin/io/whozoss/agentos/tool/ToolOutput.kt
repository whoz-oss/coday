package io.whozoss.agentos.tool

/**
 * Metadata for tool registration and discovery.
 * Contains information about a registered tool without exposing its implementation.
 */
data class ToolOutput(
    val name: String,
    val description: String,
    val version: String,
    val inputSchema: String,
)
