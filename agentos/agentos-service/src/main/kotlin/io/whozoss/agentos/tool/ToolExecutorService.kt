package io.whozoss.agentos.tool

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import io.whozoss.agentos.tool.ToolRegistry
import mu.KLogging
import org.springframework.stereotype.Service
import kotlin.system.measureTimeMillis

/**
 * Service for executing tools with proper error handling and tracking.
 * Provides a safe execution environment for tools with timing and error capture.
 */
@Service
class ToolExecutorService(
    private val toolRegistry: ToolRegistry,
    private val objectMapper: ObjectMapper,
) {
    /**
     * Execute a tool by name with JSON parameters.
     *
     * @param toolName The name of the tool to execute
     * @param parametersJson JSON string containing tool parameters
     * @return ToolExecutionResult with success status, output, and timing information
     */
    fun executeTool(
        toolName: String,
        parametersJson: String,
    ): ToolExecutionResult {
        val tool = toolRegistry.findTool(toolName)

        if (tool == null) {
            logger.warn { "Tool not found: $toolName" }
            return ToolExecutionResult(
                toolName = toolName,
                success = false,
                output = "",
                executionDurationMs = 0,
                errorType = "ToolNotFound",
                errorMessage = "Tool not found: $toolName",
            )
        }

        var output = ""
        var success = true
        var errorType: String? = null
        var errorMessage: String? = null

        val duration =
            measureTimeMillis {
                try {
                    // Parse parameters based on tool's paramType
                    val input =
                        if (tool.paramType != null && parametersJson.isNotBlank()) {
                            try {
                                logger.debug { "Parsing parameters for tool $toolName: $parametersJson" }
                                objectMapper.readValue(parametersJson, tool.paramType)
                            } catch (e: JsonProcessingException) {
                                logger.error(e) { "Failed to parse JSON parameters for tool $toolName: $parametersJson" }
                                throw IllegalArgumentException("Invalid JSON parameters for tool $toolName: ${e.message}", e)
                            }
                        } else {
                            logger.debug { "Tool $toolName has no parameters or empty parameters" }
                            null
                        }

                    // Execute tool using type-erased execution path
                    logger.debug { "Executing tool: $toolName" }
                    output = tool.executeWithAny(input)
                    logger.debug { "Tool $toolName executed successfully" }
                } catch (e: Exception) {
                    success = false
                    errorType = e.javaClass.simpleName
                    errorMessage = e.message ?: "Unknown error"
                    logger.error(e) { "Error executing tool $toolName: ${e.message}" }
                }
            }

        val result =
            ToolExecutionResult(
                toolName = toolName,
                success = success,
                output = output,
                executionDurationMs = duration,
                errorType = errorType,
                errorMessage = errorMessage,
            )

        logger.info {
            if (success) {
                "Tool $toolName executed successfully in ${duration}ms"
            } else {
                "Tool $toolName failed in ${duration}ms: $errorType - $errorMessage"
            }
        }

        // TODO(WZ-28275): Emit tool_executed event when event system is integrated

        return result
    }

    companion object : KLogging()
}
