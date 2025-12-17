package io.biznet.agentos.sdk

import org.pf4j.ExtensionPoint

/**
 * Base interface for Tool plugins.
 * Tools are capabilities that agents can use to perform specific actions.
 */
interface ToolPlugin : ExtensionPoint {
    /**
     * Execute the tool with the given parameters.
     * 
     * @param parameters The tool parameters
     * @return The tool execution result
     */
    suspend fun execute(parameters: Map<String, Any>): ToolResult
    
    /**
     * Get the tool's definition (name, description, parameters schema).
     */
    fun getDefinition(): ToolDefinition
}

/**
 * Result of a tool execution.
 */
data class ToolResult(
    val success: Boolean,
    val output: Any?,
    val error: String? = null
)

/**
 * Definition of a tool.
 */
data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: List<ToolParameter>
)

/**
 * Parameter definition for a tool.
 */
data class ToolParameter(
    val name: String,
    val description: String,
    val type: ParameterType,
    val required: Boolean = false,
    val defaultValue: Any? = null
)

/**
 * Supported parameter types.
 */
enum class ParameterType {
    STRING,
    NUMBER,
    BOOLEAN,
    OBJECT,
    ARRAY
}
