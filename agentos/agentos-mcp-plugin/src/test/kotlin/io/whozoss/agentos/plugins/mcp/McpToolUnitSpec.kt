package io.whozoss.agentos.plugins.mcp

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.modelcontextprotocol.spec.McpSchema.Tool
import io.whozoss.agentos.sdk.tool.ToolContext
import java.util.UUID

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
        result shouldBe "search results here"
    }
})
