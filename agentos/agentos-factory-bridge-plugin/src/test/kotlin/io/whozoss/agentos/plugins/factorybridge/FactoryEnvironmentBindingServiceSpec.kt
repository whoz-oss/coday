package io.whozoss.agentos.plugins.factorybridge

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import io.whozoss.agentos.sdk.tool.ToolPlugin
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.UUID

private class FixedNameTool(
    override val name: String,
) : StandardTool<String> {
    override val description = "test"
    override val inputSchema = "{}"
    override val version = "1.0.0"
    override val paramType = String::class.java

    override suspend fun execute(
        input: String?,
        context: ToolContext,
    ): ToolExecutionResult = ToolExecutionResult.success("ok")
}

private class FakeFilePlugin(
    private val tools: List<StandardTool<*>>,
) : ToolPlugin {
    override val integrationType = "FILE_ACCESS"
    override val configSchema: JsonNode? = null

    override fun provideTools(
        config: JsonNode?,
        configName: String?,
        context: ToolContext?,
    ): List<StandardTool<*>> = tools
}

class FactoryEnvironmentBindingServiceSpec : StringSpec({
    val mapper = jacksonObjectMapper()

    fun context(): ToolContext = ToolContext(UUID.randomUUID(), UUID.randomUUID(), "external", emptyList(), "Worker")

    "grants nothing when the workflow or case identity is missing" {
        val service =
            FactoryEnvironmentBindingService(
                fileAccess = { FakeFilePlugin(listOf(FixedNameTool("FILES__read"))) },
                objectMapper = mapper,
                baseUrl = "http://127.0.0.1:1",
                httpClient = OkHttpClient(),
            )
        service.grantTools(null, "case", null, context()).isEmpty() shouldBe true
        service.grantTools("wf", null, null, context()).isEmpty() shouldBe true
    }

    "grants nothing when the FILE_ACCESS plugin is not loaded" {
        val service =
            FactoryEnvironmentBindingService(
                fileAccess = { null },
                objectMapper = mapper,
                baseUrl = "http://127.0.0.1:1",
                httpClient = OkHttpClient(),
            )
        service.grantTools("wf", "case", null, context()).isEmpty() shouldBe true
    }

    "grants FILE_ACCESS tools only after exact canonical worktree proof" {
        val root = Files.createTempDirectory("factory-worktree").toRealPath()
        val workflowId = "wf-1"
        val caseId = "case-1"
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.requestBody.close()
            val body =
                """
                {"data":{"fileAccess":{"status":"bound"},"environment":{"parentCaseId":"case-1","workflowId":"wf-1","worktreePath":"$root"}}}
                """.trimIndent().toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val service =
                FactoryEnvironmentBindingService(
                    fileAccess = { FakeFilePlugin(listOf(FixedNameTool("FILES__read"), FixedNameTool("FILES__write"))) },
                    objectMapper = mapper,
                    baseUrl = "http://127.0.0.1:${server.address.port}",
                    httpClient = OkHttpClient(),
                )
            service.grantTools(workflowId, caseId, null, context()).map { it.name } shouldBe listOf("FILES__read", "FILES__write")
            service.grantTools(workflowId, caseId, listOf("FILES__read"), context()).map { it.name } shouldBe listOf("FILES__read")
        } finally {
            server.stop(0)
            root.toFile().deleteRecursively()
        }
    }

    "grants nothing when Factory does not prove the binding" {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.requestBody.close()
            val body = """{"data":{"fileAccess":{"status":"unbound"},"environment":{}}}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val service =
                FactoryEnvironmentBindingService(
                    fileAccess = { FakeFilePlugin(listOf(FixedNameTool("FILES__read"))) },
                    objectMapper = mapper,
                    baseUrl = "http://127.0.0.1:${server.address.port}",
                    httpClient = OkHttpClient(),
                )
            service.grantTools("wf-1", "case-1", null, context()).isEmpty() shouldBe true
        } finally {
            server.stop(0)
        }
    }
})
