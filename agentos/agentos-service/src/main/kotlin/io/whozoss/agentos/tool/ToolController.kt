package io.whozoss.agentos.tool

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import io.whozoss.agentos.sdk.tool.ToolOutput
import io.whozoss.agentos.sdk.tool.ToolRegistry
import mu.KLogging
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * REST API for tool registry operations.
 * Provides endpoints for tool discovery, metadata retrieval, and testing.
 */
@RestController
@RequestMapping("/api/tools")
class ToolController(
    private val toolRegistry: ToolRegistry,
    private val toolExecutor: ToolExecutorService,
    private val objectMapper: ObjectMapper,
) {
    /**
     * List all available tools.
     *
     * @return List of tool metadata for all registered tools
     */
    @GetMapping
    fun listTools(): ResponseEntity<List<ToolOutput>> {
        logger.debug { "Listing all tools" }
        val tools = toolRegistry.listTools().map { it.toOutput() }
        logger.debug { "Found ${tools.size} tool(s)" }
        return ResponseEntity.ok(tools)
    }

    /**
     * Get specific tool metadata by name.
     *
     * @param toolName The exact name of the tool
     * @return Tool metadata or 404 if not found
     */
    @GetMapping("/{toolName}")
    fun getTool(
        @PathVariable toolName: String,
    ): ResponseEntity<ToolOutput> {
        logger.debug { "Getting tool: $toolName" }

        val tool =
            toolRegistry.findTool(toolName)?.toOutput()

        return if (tool != null) {
            ResponseEntity.ok(tool)
        } else {
            logger.warn { "Tool not found: $toolName" }
            ResponseEntity.notFound().build()
        }
    }

    /**
     * Execute a tool (for testing/debugging).
     * This endpoint allows direct tool execution with JSON parameters.
     *
     * @param toolName The name of the tool to execute
     * @param parameters Map of parameters to pass to the tool
     * @return Tool execution result with success status and output
     */
    @PostMapping("/{toolName}/execute")
    fun executeTool(
        @PathVariable toolName: String,
        @RequestBody parameters: Map<String, Any>,
    ): ResponseEntity<ToolExecutionResult> {
        logger.info { "Executing tool: $toolName with parameters: $parameters" }

        // Use companion object mapper instead of creating new instance
        val parametersJson = objectMapper.writeValueAsString(parameters)

        val result = toolExecutor.executeTool(toolName, parametersJson)

        // Always return 200 OK with the result - the success field indicates if tool succeeded
        // This distinguishes between tool execution errors vs HTTP/server errors
        return ResponseEntity.ok(result)
    }

    companion object : KLogging()

    fun StandardTool<*>.toOutput(): ToolOutput =
        ToolOutput(
            name = this.name,
            description = this.description,
            inputSchema = this.inputSchema,
            version = this.version,
        )
}
