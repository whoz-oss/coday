package io.whozoss.agentos.claudeCode

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import io.swagger.v3.oas.annotations.Hidden
import mu.KLogging
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * The loopback MCP server the Claude Code CLI calls the AgentOS tools through ([ClaudeCodeToolBridge]).
 *
 * MCP over Streamable HTTP, reduced to what a tool server needs: every request is a POST answered with one
 * JSON-RPC response (`initialize`, `ping`, `tools/list`, `tools/call`), notifications are acknowledged,
 * and no session nor server-sent stream is kept. Unknown methods get `-32601`, which is also how the CLI
 * learns that `server/discover` is not supported before it falls back to `initialize`.
 *
 * Not part of the AgentOS API: hidden from the OpenAPI description, and an unknown run or a wrong token
 * gets a 404 (a 401 would start the CLI's OAuth discovery).
 */
@Hidden
@RestController
@RequestMapping("${ClaudeCodeToolBridge.PATH}/{runId}")
class ClaudeCodeMcpController(
    private val bridge: ClaudeCodeToolBridge,
    private val objectMapper: ObjectMapper,
) {
    @PostMapping
    fun post(
        @PathVariable runId: String,
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @RequestBody body: String,
    ): ResponseEntity<String> {
        val run =
            runCatching { UUID.fromString(runId) }.getOrNull()?.let { bridge.find(it, authorization) }
                ?: return ResponseEntity.notFound().build()
        val message =
            runCatching { objectMapper.readTree(body) }.getOrNull()
                ?: return json(HttpStatus.BAD_REQUEST, error(null, PARSE_ERROR, "Parse error"))
        if (!message.isObject) return json(HttpStatus.BAD_REQUEST, error(null, INVALID_REQUEST, "Batches are not supported"))
        val id = message.get("id")?.takeUnless { it.isNull }
        val method = message.path("method").textValue()
        // A notification, or a response to a request this server never sends.
        if (id == null || method == null) return ResponseEntity.accepted().build()

        val params = message.path("params")
        val response =
            when (method) {
                "initialize" -> result(id, initializeResult(params))
                "ping" -> result(id, JsonNodeFactory.instance.objectNode())
                "tools/list" -> result(id, toolsList(run))
                "tools/call" -> callTool(run, id, params)
                else -> error(id, METHOD_NOT_FOUND, "Method not found: $method")
            }
        return json(HttpStatus.OK, response)
    }

    /** No server-sent stream to open: every response comes back on its POST. */
    @GetMapping
    fun get(): ResponseEntity<Void> = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).header(HttpHeaders.ALLOW, "POST").build()

    /** No session to end. */
    @DeleteMapping
    fun delete(): ResponseEntity<Void> = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).header(HttpHeaders.ALLOW, "POST").build()

    private fun initializeResult(params: JsonNode): ObjectNode {
        val requested = params.path("protocolVersion").textValue()
        return JsonNodeFactory.instance.objectNode().apply {
            put("protocolVersion", requested?.takeIf { it in PROTOCOL_VERSIONS } ?: PROTOCOL_VERSIONS.first())
            putObject("capabilities").putObject("tools").put("listChanged", false)
            putObject("serverInfo").put("name", ClaudeCodeCommand.MCP_SERVER_NAME).put("version", SERVER_VERSION).put("title", "AgentOS tools")
        }
    }

    private fun toolsList(run: ClaudeCodeToolRun): ObjectNode =
        JsonNodeFactory.instance.objectNode().apply {
            val tools = putArray("tools")
            run.tools.forEach { (name, tool) ->
                tools
                    .addObject()
                    .put("name", name)
                    .put("description", tool.description)
                    .set<JsonNode>("inputSchema", inputSchema(tool.inputSchema))
            }
        }

    /** MCP requires an object schema: a tool without a readable one takes any object. */
    private fun inputSchema(schema: String): ObjectNode {
        val parsed = runCatching { objectMapper.readTree(schema) }.getOrNull() as? ObjectNode
        return (parsed ?: JsonNodeFactory.instance.objectNode()).apply { if (!has("type")) put("type", "object") }
    }

    private fun callTool(
        run: ClaudeCodeToolRun,
        id: JsonNode,
        params: JsonNode,
    ): ObjectNode {
        val name = params.path("name").textValue() ?: return error(id, INVALID_PARAMS, "Missing tool name")
        val arguments = params.get("arguments")?.takeIf { it.isObject }?.toString() ?: "{}"
        val toolUseId = params.path("_meta").path(TOOL_USE_ID_META).textValue()
        val outcome =
            try {
                run.call(name, arguments, toolUseId)
            } catch (e: Exception) {
                logger.warn(e) { "[ClaudeCodeMcp] run=${run.id} tool '$name' could not be run" }
                ClaudeCodeToolResult("Error executing tool: ${e.message ?: e::class.simpleName}", isError = true)
            } ?: return error(id, INVALID_PARAMS, "Unknown tool: $name")
        val content =
            JsonNodeFactory.instance.arrayNode().apply {
                addObject().put("type", "text").put("text", outcome.text)
                outcome.images.forEach { addObject().put("type", "image").put("data", it.content).put("mimeType", it.mimeType) }
            }
        return result(
            id,
            JsonNodeFactory.instance.objectNode().apply {
                set<JsonNode>("content", content)
                put("isError", outcome.isError)
            },
        )
    }

    private fun result(
        id: JsonNode,
        result: JsonNode,
    ): ObjectNode =
        JsonNodeFactory.instance.objectNode().apply {
            put("jsonrpc", "2.0")
            set<JsonNode>("id", id)
            set<JsonNode>("result", result)
        }

    private fun error(
        id: JsonNode?,
        code: Int,
        message: String,
    ): ObjectNode =
        JsonNodeFactory.instance.objectNode().apply {
            put("jsonrpc", "2.0")
            set<JsonNode>("id", id ?: JsonNodeFactory.instance.nullNode())
            putObject("error").put("code", code).put("message", message)
        }

    private fun json(
        status: HttpStatus,
        body: JsonNode,
    ): ResponseEntity<String> = ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(objectMapper.writeValueAsString(body))

    companion object : KLogging() {
        /** Newest first; a client asking for another version gets the newest. */
        val PROTOCOL_VERSIONS = listOf("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05")

        /** Required by MCP next to the server name; only changes with this protocol surface. */
        const val SERVER_VERSION = "1.0.0"

        /** Set by the CLI on `tools/call`: the id of the `tool_use` block in its stream. */
        const val TOOL_USE_ID_META = "claudecode/toolUseId"

        private const val PARSE_ERROR = -32700
        private const val INVALID_REQUEST = -32600
        private const val METHOD_NOT_FOUND = -32601
        private const val INVALID_PARAMS = -32602
    }
}
