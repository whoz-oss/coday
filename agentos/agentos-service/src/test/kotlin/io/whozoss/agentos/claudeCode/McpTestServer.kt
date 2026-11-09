package io.whozoss.agentos.claudeCode

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Serves [ClaudeCodeMcpController] over real loopback HTTP, as Spring does, so a fake CLI can call the tools
 * of an agent run with `curl`.
 */
class McpTestServer(
    properties: ClaudeCodeProperties,
) : AutoCloseable {
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val bridge = ClaudeCodeToolBridge(properties.copy(bridgeUrl = "http://127.0.0.1:${server.address.port}"))
    val controller = ClaudeCodeMcpController(bridge, jacksonObjectMapper())

    init {
        server.createContext(ClaudeCodeToolBridge.PATH) { exchange ->
            exchange.use {
                val runId = exchange.requestURI.path.substringAfterLast('/')
                val response =
                    when (exchange.requestMethod) {
                        "POST" -> {
                            val body = exchange.requestBody.readAllBytes().decodeToString()
                            controller.post(runId, exchange.requestHeaders.getFirst("Authorization"), body)
                        }

                        else -> controller.get()
                    }
                val bytes = (response.body as? String)?.toByteArray() ?: ByteArray(0)
                response.headers.contentType?.let { exchange.responseHeaders.add("Content-Type", it.toString()) }
                exchange.sendResponseHeaders(response.statusCode.value(), if (bytes.isEmpty()) -1 else bytes.size.toLong())
                if (bytes.isNotEmpty()) exchange.responseBody.write(bytes)
            }
        }
        server.executor = executor
        server.start()
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }

    companion object {
        /** A tool without parameters whose call runs [execute]. */
        fun tool(
            name: String,
            inputSchema: String = """{"type":"object","properties":{"timezone":{"type":"string"}}}""",
            execute: suspend (ToolContext) -> ToolExecutionResult,
        ): StandardTool<Nothing> =
            object : StandardTool<Nothing> {
                override val name = name
                override val description = "Test tool $name"
                override val inputSchema = inputSchema
                override val version = "1.0.0"
                override val paramType: Class<Nothing>? = null

                override suspend fun execute(
                    input: Nothing?,
                    context: ToolContext,
                ): ToolExecutionResult = execute(context)
            }
    }
}
