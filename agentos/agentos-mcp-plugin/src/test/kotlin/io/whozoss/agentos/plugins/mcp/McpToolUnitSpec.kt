package io.whozoss.agentos.plugins.mcp

import ch.qos.logback.classic.Level
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.modelcontextprotocol.spec.McpError
import io.modelcontextprotocol.spec.McpSchema
import io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse.JSONRPCError
import io.modelcontextprotocol.spec.McpSchema.Tool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import kotlinx.coroutines.runBlocking
import java.util.UUID
import java.util.concurrent.TimeoutException

class McpToolUnitSpec : StringSpec({

    val ctx = ToolContext(UUID.randomUUID(), null, null, emptyList())

    fun makeTool(
        name: String = "search_repos",
        description: String = "Search repositories",
        configName: String? = null,
        connection: McpConnectionPort = mockk(),
    ): McpTool {
        val mcpTool = Tool.builder()
            .name(name)
            .description(description)
            .build()
        return McpTool(mcpTool, connection, configName)
    }

    "name without configName uses raw tool name" {
        val tool = makeTool(name = "search_repos", configName = null)
        tool.name shouldBe "search_repos"
    }

    "name with configName is prefixed" {
        val tool = makeTool(name = "search_repos", configName = "GITHUB")
        tool.name shouldBe "GITHUB__search_repos"
    }

    "description falls back to generated string when null" {
        val mcpTool = Tool.builder().name("no_desc").build()
        val tool = McpTool(mcpTool, mockk(), null)
        tool.description shouldContain "no_desc"
    }

    "execute passes parsed args map to connection" {
        val connection = mockk<McpConnectionPort>()
        val argsSlot = slot<Map<String, Any?>>()
        every { connection.callTool(any(), capture(argsSlot)) } returns "result"

        val tool = makeTool(connection = connection)
        tool.execute(McpTool.Input(args = """{"query":"kotlin"}"""), ctx)

        argsSlot.captured["query"] shouldBe "kotlin"
    }

    "execute passes empty map when args is null" {
        val connection = mockk<McpConnectionPort>()
        val argsSlot = slot<Map<String, Any?>>()
        every { connection.callTool(any(), capture(argsSlot)) } returns "ok"

        val tool = makeTool(connection = connection)
        tool.execute(null, ctx)

        argsSlot.captured shouldBe emptyMap()
    }

    "execute passes empty map when args is blank" {
        val connection = mockk<McpConnectionPort>()
        val argsSlot = slot<Map<String, Any?>>()
        every { connection.callTool(any(), capture(argsSlot)) } returns "ok"

        val tool = makeTool(connection = connection)
        tool.execute(McpTool.Input(args = "  "), ctx)

        argsSlot.captured shouldBe emptyMap()
    }

    "executeWithJson passes raw json as args" {
        val connection = mockk<McpConnectionPort>()
        val argsSlot = slot<Map<String, Any?>>()
        every { connection.callTool(any(), capture(argsSlot)) } returns "ok"

        val tool = makeTool(connection = connection)
        tool.executeWithJson("""{"query":"test"}""", ctx)

        argsSlot.captured["query"] shouldBe "test"
    }

    "returns connection result" {
        val connection = mockk<McpConnectionPort>()
        every { connection.callTool(any(), any()) } returns "search results here"

        val tool = makeTool(connection = connection)
        val result = tool.execute(McpTool.Input(args = "{}"), ctx)
        result shouldBe ToolExecutionResult.success("search results here")
    }

    "MCP tool error becomes ToolExecutionResult.error with MCP_TOOL_ERROR type" {
        val connection = mockk<McpConnectionPort>()
        every { connection.callTool(any(), any()) } throws McpToolErrorException("search_repos", "repository not found")

        val tool = makeTool(connection = connection)
        val result = tool.execute(McpTool.Input(args = "{}"), ctx)

        result.success shouldBe false
        result.output shouldBe "repository not found"
        result.errorType shouldBe "MCP_TOOL_ERROR"
    }

    "transport failure becomes ToolExecutionResult.error with exception type" {
        val connection = mockk<McpConnectionPort>()
        every { connection.callTool(any(), any()) } throws McpConnectionException("process died")

        val tool = makeTool(connection = connection)
        val result = tool.execute(McpTool.Input(args = "{}"), ctx)

        result.success shouldBe false
        result.errorType shouldBe "McpConnectionException"
    }

    "transport failure gives the model a fixed text, never the exception message, and logs the detail" {
        val connection = mockk<McpConnectionPort>()
        every { connection.callTool(any(), any()) } throws
            McpConnectionException("Tool call 'search_repos' failed: 401 for https://mcp.example.com/?token=secret=XYZ")

        val tool = makeTool(connection = connection)
        var result: ToolExecutionResult? = null
        val logs = LogCapture.capturing { result = runBlocking { tool.execute(McpTool.Input(args = "{}"), ctx) } }

        result!!.success shouldBe false
        result!!.output shouldBe McpTool.TRANSPORT_FAILURE_OUTPUT
        result!!.output shouldNotContain "XYZ"
        (result!!.errorMessage ?: "") shouldNotContain "XYZ"
        result!!.errorType shouldBe "McpConnectionException"
        logs.messagesAt(Level.ERROR).single() shouldContain "secret=XYZ"
    }

    "a call the server refuses as invalid tells the model to fix its arguments, without the server's message" {
        val connection = mockk<McpConnectionPort>()
        val refusal =
            McpError(JSONRPCError(McpSchema.ErrorCodes.INVALID_PARAMS, "query is required (secret=XYZ)", null))
        every { connection.callTool(any(), any()) } throws
            McpConnectionException("Tool call 'search_repos' failed: ${refusal.message}", refusal)

        val tool = makeTool(connection = connection)
        val result = tool.execute(McpTool.Input(args = "{}"), ctx)

        result.success shouldBe false
        result.output shouldBe McpTool.INVALID_PARAMS_OUTPUT
        result.output shouldNotContain "XYZ"
        (result.errorMessage ?: "") shouldNotContain "XYZ"
    }

    "a call the server refuses with another JSON-RPC error gives the model the code only" {
        val connection = mockk<McpConnectionPort>()
        val refusal =
            McpError(JSONRPCError(McpSchema.ErrorCodes.INTERNAL_ERROR, "db down at 10.0.0.3 (secret=XYZ)", null))
        every { connection.callTool(any(), any()) } throws McpConnectionException("Tool call failed", refusal)

        val result = makeTool(connection = connection).execute(McpTool.Input(args = "{}"), ctx)

        result.output shouldContain "JSON-RPC error -32603"
        result.output shouldNotContain "XYZ"
        result.output shouldNotContain "10.0.0.3"
    }

    "a call that times out says so instead of calling the integration unreachable" {
        val connection = mockk<McpConnectionPort>()
        every { connection.callTool(any(), any()) } throws
            McpConnectionException("Tool call failed", RuntimeException(TimeoutException("Did not observe any item")))

        val result = makeTool(connection = connection).execute(McpTool.Input(args = "{}"), ctx)

        result.success shouldBe false
        result.output shouldBe McpTool.TIMEOUT_OUTPUT
    }

    "an MCP tool error keeps the tool's own content, which is its answer and not a transport detail" {
        val connection = mockk<McpConnectionPort>()
        every { connection.callTool(any(), any()) } throws
            McpToolErrorException("search_repos", "rate limit exceeded, retry in 30s (ref secret=XYZ)")

        val tool = makeTool(connection = connection)
        val result = tool.execute(McpTool.Input(args = "{}"), ctx)

        result.success shouldBe false
        result.output shouldBe "rate limit exceeded, retry in 30s (ref secret=XYZ)"
        result.errorType shouldBe "MCP_TOOL_ERROR"
    }
})
