package io.whozoss.agentos.plugins.mcp

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.modelcontextprotocol.spec.McpError
import io.modelcontextprotocol.spec.McpSchema
import io.modelcontextprotocol.spec.McpSchema.Tool
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import mu.KLogging
import java.util.concurrent.TimeoutException

/**
 * A [StandardTool] that delegates execution to a live MCP connection.
 *
 * Each instance represents one tool advertised by a remote MCP server.
 * For stdio transport, the connection is pooled via [McpConnectionPool] and
 * outlives individual agent runs. For HTTP transport, the connection lives for
 * the duration of the agent run and is not shared across users.
 * In both cases this tool holds only a reference and never closes the connection.
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

    override suspend fun execute(input: Input?, context: ToolContext): ToolExecutionResult {
        val arguments: Map<String, Any?> = run {
            val rawArgs = input?.args
            if (rawArgs.isNullOrBlank()) {
                emptyMap()
            } else {
                runCatching { objectMapper.readValue<Map<String, Any?>>(rawArgs) }
                    .getOrElse { e ->
                        logger.warn { "[MCP] Could not parse args for tool '${mcpTool.name()}': ${e.message}" }
                        emptyMap()
                    }
            }
        }
        logger.trace { "[MCP] Calling '${mcpTool.name()}' with args: $arguments" }
        return runCatching { connection.callTool(mcpTool.name(), arguments) }
            .fold(
                onSuccess = { ToolExecutionResult.success(it) },
                onFailure = { e ->
                    when (e) {
                        is McpToolErrorException ->
                            // The MCP server ran the tool but the tool itself failed.
                            // Surface the error content to the agent so it can adapt.
                            ToolExecutionResult.error(
                                output = e.content,
                                errorType = "MCP_TOOL_ERROR",
                                errorMessage = e.message,
                            )
                        else -> {
                            // A failed call carries the server's own words (URL, headers, tokens): the detail
                            // stays in the logs, the model gets a fixed text naming only the kind of failure.
                            logger.error(e) { "[MCP] Tool call '${mcpTool.name()}' failed: ${e.message}" }
                            val output = callFailureOutput(e)
                            ToolExecutionResult.error(
                                output = output,
                                errorType = e::class.simpleName ?: "Error",
                                errorMessage = output,
                            )
                        }
                    }
                },
            )
    }

    /**
     * Override [executeWithJson] to pass the raw JSON directly as [Input.args].
     *
     * The MCP server owns the schema and expects the raw argument map. Rather than
     * deserializing into a typed class (which would require a class per tool), we
     * wrap the raw JSON string in [Input] and re-serialize it inside [execute].
     * This keeps deserialization inside the plugin classloader as required.
     */
    override suspend fun executeWithJson(json: String?, context: ToolContext): ToolExecutionResult =
        execute(Input(args = json), context)

    companion object : KLogging() {
        private val objectMapper = jacksonObjectMapper()

        /**
         * What the model sees when the integration could not be reached, as opposed to the tool answering with an
         * error.
         */
        internal const val TRANSPORT_FAILURE_OUTPUT =
            "The tool call failed because the integration could not be reached. The cause is in the server logs."

        /** What the model sees when the integration did not answer within the request timeout. */
        internal const val TIMEOUT_OUTPUT =
            "The tool call failed because the integration did not answer in time. The cause is in the server logs."

        /** What the model sees when the integration refused the arguments (JSON-RPC invalid params). */
        internal const val INVALID_PARAMS_OUTPUT =
            "The integration rejected the arguments of the call (JSON-RPC error -32602, invalid params). " +
                "Check them against the tool's input schema before retrying. The detail is in the server logs."

        /** Bounds the walk up the cause chain, which a faulty exception could make cyclic. */
        private const val MAX_CAUSE_DEPTH = 10

        /**
         * Picks the fixed text the model gets for a failed call, from the cause chain of [error].
         *
         * An answer of the server to the call itself ([McpError], a JSON-RPC error response) is told apart from a
         * timeout and from a server that could not be reached, so the model knows whether fixing the call can help.
         * Only the numeric JSON-RPC code leaves the server, never the server's message, which can echo a URL or a
         * header.
         */
        internal fun callFailureOutput(error: Throwable): String {
            val causes = generateSequence(error) { it.cause }.take(MAX_CAUSE_DEPTH).toList()
            val rejection = causes.firstNotNullOfOrNull { it as? McpError }
            return when {
                rejection != null -> rejectedCallOutput(code = rejection.jsonRpcError?.code())
                causes.any { it is TimeoutException } -> TIMEOUT_OUTPUT
                else -> TRANSPORT_FAILURE_OUTPUT
            }
        }

        private fun rejectedCallOutput(code: Int?): String =
            if (code == McpSchema.ErrorCodes.INVALID_PARAMS) {
                INVALID_PARAMS_OUTPUT
            } else {
                "The integration refused the call with JSON-RPC error ${code ?: "(no code)"}. " +
                    "The detail is in the server logs."
            }

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
