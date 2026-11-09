package io.whozoss.agentos.claudeCode

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import org.springframework.http.HttpStatus
import java.time.Duration

class ClaudeCodeMcpControllerSpec :
    StringSpec({
        val mapper = jacksonObjectMapper()
        val bridge = ClaudeCodeToolBridge(ClaudeCodeProperties(bridgeUrl = "http://127.0.0.1:8124", toolTimeout = Duration.ofMinutes(5)))
        val controller = ClaudeCodeMcpController(bridge, mapper)
        val calls = mutableListOf<Triple<String, String, String?>>()

        fun open(vararg tools: StandardTool<*>) =
            bridge.open(tools.toList()) { tool, arguments, toolUseId ->
                calls.add(Triple(tool.name, arguments, toolUseId))
                ClaudeCodeToolResult("ran ${tool.name}")
            }

        fun ClaudeCodeToolRun.post(body: String) = controller.post(id.toString(), "Bearer ${mcpServer.bearerToken}", body)

        fun ClaudeCodeToolRun.request(
            method: String,
            params: String = "{}",
        ): JsonNode {
            val response = post("""{"jsonrpc":"2.0","id":7,"method":"$method","params":$params}""")
            response.statusCode shouldBe HttpStatus.OK
            return mapper.readTree(response.body).also { it["id"].intValue() shouldBe 7 }
        }

        beforeTest { calls.clear() }

        "initialize answers with the client's protocol version when known, else the newest" {
            open().use { run ->
                val result = run.request("initialize", """{"protocolVersion":"2025-06-18","capabilities":{}}""")["result"]
                result["protocolVersion"].textValue() shouldBe "2025-06-18"
                result["capabilities"]["tools"]["listChanged"].booleanValue() shouldBe false
                result["serverInfo"]["name"].textValue() shouldBe "agentos"
                // Required: the CLI refuses an initialize result without it (INVALID_RESULT).
                result["serverInfo"]["version"].textValue() shouldBe "1.0.0"

                run.request("initialize", """{"protocolVersion":"2099-01-01"}""")["result"]["protocolVersion"].textValue() shouldBe "2025-11-25"
            }
        }

        "server/discover is unknown, so the CLI falls back to initialize" {
            open().use { run ->
                run.request("server/discover")["error"]["code"].intValue() shouldBe -32601
            }
        }

        "notifications are acknowledged without a body" {
            open().use { run ->
                val response = run.post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
                response.statusCode shouldBe HttpStatus.ACCEPTED
                response.body.shouldBeNull()
            }
        }

        "tools/list gives each tool its name, description and object schema" {
            val dated = McpTestServer.tool("DATETIME__now") { ToolExecutionResult.success("") }
            val broken = McpTestServer.tool("BROKEN__tool", inputSchema = "not json") { ToolExecutionResult.success("") }
            open(dated, broken).use { run ->
                val tools = run.request("tools/list")["result"]["tools"]
                tools.map { it["name"].textValue() } shouldBe listOf("DATETIME__now", "BROKEN__tool")
                tools[0]["description"].textValue() shouldBe "Test tool DATETIME__now"
                tools[0]["inputSchema"] shouldBe mapper.readTree("""{"type":"object","properties":{"timezone":{"type":"string"}}}""")
                tools[1]["inputSchema"] shouldBe mapper.readTree("""{"type":"object"}""")
            }
        }

        "tools/call runs the tool with its arguments and the CLI's tool_use id" {
            open(McpTestServer.tool("DATETIME__now") { ToolExecutionResult.success("") }).use { run ->
                val result =
                    run.request(
                        "tools/call",
                        """{"name":"DATETIME__now","arguments":{"timezone":"Europe/Paris"},"_meta":{"claudecode/toolUseId":"toolu_1","progressToken":2}}""",
                    )["result"]

                calls.single() shouldBe Triple("DATETIME__now", """{"timezone":"Europe/Paris"}""", "toolu_1")
                result shouldBe mapper.readTree("""{"content":[{"type":"text","text":"ran DATETIME__now"}],"isError":false}""")

                run.request("tools/call", """{"name":"DATETIME__now"}""")
                calls.last() shouldBe Triple("DATETIME__now", "{}", null)
            }
        }

        "a tool's images and failure reach the CLI" {
            val image = MessageContent.Image(content = "aGVsbG8=", mimeType = "image/png")
            bridge
                .open(listOf(McpTestServer.tool("FILES__read") { ToolExecutionResult.success("") })) { _, _, _ ->
                    ClaudeCodeToolResult("Rendered 1 page", images = listOf(image), isError = true)
                }.use { run ->
                    run.request("tools/call", """{"name":"FILES__read","arguments":{}}""")["result"] shouldBe
                        mapper.readTree(
                            """{"content":[{"type":"text","text":"Rendered 1 page"},{"type":"image","data":"aGVsbG8=","mimeType":"image/png"}],"isError":true}""",
                        )
                }
        }

        "a call to an unknown tool is an invalid request" {
            open().use { run ->
                run.request("tools/call", """{"name":"NOPE__tool","arguments":{}}""")["error"]["code"].intValue() shouldBe -32602
            }
        }

        "an unknown run, a wrong token or a closed run gets a 404" {
            val run = open()
            controller.post(run.id.toString(), "Bearer wrong", "{}").statusCode shouldBe HttpStatus.NOT_FOUND
            controller.post(run.id.toString(), null, "{}").statusCode shouldBe HttpStatus.NOT_FOUND
            controller.post("not-a-uuid", "Bearer ${run.mcpServer.bearerToken}", "{}").statusCode shouldBe HttpStatus.NOT_FOUND
            run.close()
            run.post("""{"jsonrpc":"2.0","id":1,"method":"ping"}""").statusCode shouldBe HttpStatus.NOT_FOUND
        }

        "unreadable JSON and batches are refused" {
            open().use { run ->
                run.post("{").let {
                    it.statusCode shouldBe HttpStatus.BAD_REQUEST
                    mapper.readTree(it.body)["error"]["code"].intValue() shouldBe -32700
                }
                run.post("""[{"jsonrpc":"2.0","id":1,"method":"ping"}]""").let {
                    it.statusCode shouldBe HttpStatus.BAD_REQUEST
                    mapper.readTree(it.body)["error"]["code"].intValue() shouldBe -32600
                }
            }
        }

        "no server-sent stream nor session" {
            controller.get().statusCode shouldBe HttpStatus.METHOD_NOT_ALLOWED
            controller.delete().statusCode shouldBe HttpStatus.METHOD_NOT_ALLOWED
        }
    })
