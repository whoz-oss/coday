package io.whozoss.agentos.plugins.mcp

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.modelcontextprotocol.spec.McpSchema.Tool
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import mu.KLogging

/**
 * A [StandardTool] that delegates execution to a live [StdioMcpConnection].
 *
 * Each instance represents one tool advertised by a remote MCP server.
 * The [StdioMcpConnection] is owned by [McpConnectionPool] and outlives individual
 * agent runs — this tool holds only a reference, never closes the connection.
 *
 * The [inputSchema] is taken verbatim from the MCP server's tool definition so
 * the LLM sees exactly the schema the MCP server declared.
 */
class McpTool(
    private val mcpTool: Tool,
    private val connection: McpConnectionPort,
    configName: String?,
) : StandardTool<McpTool.Input> {

    override val name: String = if (configName != null) "${configName}__${mcpTool.name()}" else mcpTool.name()

    override val description: String = mcpTool.description() ?: "Tool '${mcpTool.name()}' from MCP server"

    override val version: String = "1.0.0"

    override val paramType: Class<Input> = Input::class.java

    override val inputSchema: String = buildInputSchema(mcpTool)

    /**
     * Raw JSON arguments from the LLM, passed through verbatim to the MCP server.
     * We use a single opaque [args] string rather than a typed data class because
     * the MCP tool schema is dynamic — it varies per server and per tool.
     * The actual deserialization into a [Map] happens in [execute].
     */
    data class Input(val args: String? = null)

    override fun execute(input: Input?, context: ToolContext): String {
        val arguments: Map<String, Any?> =
            when {
                input?.args.isNullOrBlank() -> {
                    emptyMap()
                }

                else -> {
                    runCatching { objectMapper.readValue<Map<String, Any?>>(input!!.args!!) }
                        .getOrElse { e ->
                            logger.warn { "[MCP] Could not parse args for tool '${mcpTool.name()}': ${e.message}" }
                            emptyMap()
                        }
                }
            }
        logger.debug { "[MCP] Calling '${mcpTool.name()}' with args: $arguments" }
        return connection.callTool(mcpTool.name(), arguments)
    }

    /**
     * Override [executeWithJson] to pass the raw JSON directly as [Input.args].
     *
     * The MCP server owns the schema and expects the raw argument map. Rather than
     * deserializing into a typed class (which would require a class per tool), we
     * wrap the raw JSON string in [Input] and re-serialize it inside [execute].
     * This keeps deserialization inside the plugin classloader as required.
     */
    override fun executeWithJson(json: String?, context: ToolContext): String =
        execute(Input(args = json), context)

    companion object : KLogging() {
        private val objectMapper = jacksonObjectMapper()

        /**
         * Build the [StandardTool.inputSchema] from the MCP tool's declared input schema.
         *
         * We serialize the inputSchema object back to JSON so the LLM sees exactly
         * the schema the MCP server declared, with no transformation.
         */
        private fun buildInputSchema(tool: Tool): String {
            val schema = tool.inputSchema()
            return when {
                schema == null -> {
                    """
                    {
                        "\$schema": "https://json-schema.org/draft/2020-12/schema",
                        "type": "object",
                        "properties": {},
                        "additionalProperties": false
                    }
                    """.trimIndent()
                }

                else -> {
                    runCatching { objectMapper.writeValueAsString(schema) }
                        .getOrElse { objectMapper.writeValueAsString(mapOf("type" to "object")) }
                }
            }
        }
    }
}
