package io.whozoss.agentos.claudeCode

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldStartWith
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import org.springframework.boot.web.context.WebServerInitializedEvent
import java.time.Duration
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

class ClaudeCodeToolBridgeSpec :
    StringSpec({
        val noTool = ClaudeCodeToolHandler { _, _, _ -> ClaudeCodeToolResult("") }

        fun started(
            port: Int,
            namespace: String? = null,
        ): WebServerInitializedEvent =
            mockk {
                every { applicationContext.serverNamespace } returns namespace
                every { webServer.port } returns port
            }

        "a run gets its own endpoint on the server port and a token, until it closes" {
            val bridge = ClaudeCodeToolBridge(ClaudeCodeProperties(toolTimeout = Duration.ofMinutes(5)))
            bridge.onApplicationEvent(started(8124))
            bridge.onApplicationEvent(started(9000, namespace = "management"))

            val run = bridge.open(emptyList(), noTool)
            val other = bridge.open(emptyList(), noTool)

            run.mcpServer.url shouldBe "http://127.0.0.1:8124/internal/claude-code/mcp/${run.id}"
            run.mcpServer.toolTimeout shouldBe Duration.ofMinutes(5)
            run.mcpServer.bearerToken shouldMatch Regex("[A-Za-z0-9_-]{43}")
            other.mcpServer.bearerToken shouldNotBe run.mcpServer.bearerToken
            bridge.find(run.id, "Bearer ${run.mcpServer.bearerToken}") shouldBe run

            run.close()
            bridge.find(run.id, "Bearer ${run.mcpServer.bearerToken}").shouldBeNull()
            bridge.find(other.id, "Bearer ${other.mcpServer.bearerToken}") shouldBe other
        }

        "a token of another run, a bare token or none finds no run" {
            val bridge = ClaudeCodeToolBridge(ClaudeCodeProperties(bridgeUrl = "http://127.0.0.1:8124/"))
            val run = bridge.open(emptyList(), noTool)
            val other = bridge.open(emptyList(), noTool)

            run.mcpServer.url shouldStartWith "http://127.0.0.1:8124/internal/"
            bridge.find(run.id, "Bearer ${other.mcpServer.bearerToken}").shouldBeNull()
            bridge.find(run.id, run.mcpServer.bearerToken).shouldBeNull()
            bridge.find(run.id, null).shouldBeNull()
        }

        "no run opens before the server port is known, unless a bridge URL is set" {
            shouldThrow<IllegalStateException> { ClaudeCodeToolBridge(ClaudeCodeProperties()).open(emptyList(), noTool) }
        }

        "tool names the model API accepts are kept, others get a short stable alias" {
            ClaudeCodeToolRun.exposedName("FILES__read") shouldBe "FILES__read"
            val long = "AGENTOS_CONFIG__" + "x".repeat(60)
            val alias = ClaudeCodeToolRun.exposedName(long)
            alias.length shouldBeLessThanOrEqual ClaudeCodeToolRun.MAX_NAME_LENGTH
            alias shouldStartWith "AGENTOS_CONFIG__xxx"
            ClaudeCodeToolRun.exposedName(long) shouldBe alias
            ClaudeCodeToolRun.exposedName(long + "y") shouldNotBe alias
            ClaudeCodeToolRun.exposedName("MCP__get.page") shouldMatch Regex("MCP__get_page_[0-9a-f]{8}")
        }

        "a run is busy while one of its tools runs" {
            val bridge = ClaudeCodeToolBridge(ClaudeCodeProperties(bridgeUrl = "http://127.0.0.1:8124"))
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val tool = McpTestServer.tool("SLOW__wait") { ToolExecutionResult.success("") }
            val run =
                bridge.open(listOf(tool)) { _, _, _ ->
                    started.countDown()
                    release.await()
                    ClaudeCodeToolResult("done")
                }

            run.busy shouldBe false
            val caller = thread { run.call("SLOW__wait", "{}", null) }
            started.await()
            run.busy shouldBe true
            release.countDown()
            caller.join()
            run.busy shouldBe false
            run.call("NOPE__tool", "{}", null).shouldBeNull()
        }
    })
