package io.whozoss.agentos.plugins.factorybridge

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.plugins.factorybridge.tools.FactorySubmitStepResultTool
import io.whozoss.agentos.sdk.caseEvent.ToolRequestEvent
import io.whozoss.agentos.sdk.tool.ToolContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.UUID

class FactoryWorkerIdentitySpec : StringSpec({
    suspend fun submit(resolvedName: String?, executionName: String?): List<String?> {
        val names = mutableListOf<String?>()
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            names.add(chain.request().header("X-AgentOS-Agent-Name"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(201).message("Created").body("{}".toResponseBody()).build()
        }).build()
        val config = FactoryBridgeConfig("http://localhost", "test-secret", "bridge", listOf("workflow:write"))
        val services = FactoryBridgeServices(config, jacksonObjectMapper(), http,
            FactoryTrustedHeaderSigner("test-secret", "bridge", listOf("workflow:write")))
        val namespace = UUID.randomUUID()
        val caseId = UUID.randomUUID()
        val context = ToolContext(
            namespaceId = namespace,
            userId = UUID.randomUUID(),
            userExternalId = "test-user",
            caseEvents = listOf(ToolRequestEvent(namespaceId = namespace, caseId = caseId,
                toolRequestId = "request-1", toolName = "FACTORY_WORKER__submit_step_result", args = null)),
            agentName = resolvedName,
            toolRequestId = "request-1",
        )
        try {
            val tool = FactoryWorkerToolPlugin { services }.provideTools(null, null, context).single()
                as FactorySubmitStepResultTool
            val result = tool.execute(
                FactorySubmitStepResultTool.Input(status = "PASS", summary = "done",
                    claims = FactorySubmitStepResultTool.Claims(emptyList())),
                context.copy(agentName = executionName),
            )
            result.success shouldBe (names.isNotEmpty())
            return names
        } finally {
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }

    "uses the runtime resolution identity when execution omits agentName" {
        submit("Analyst", null) shouldBe listOf("Analyst")
    }
    "accepts matching runtime identities" {
        submit("Analyst", "Analyst") shouldBe listOf("Analyst")
    }
    "fails closed when neither runtime context supplies an agent" {
        submit(null, null) shouldBe emptyList()
    }
    "rejects a different execution agent before any HTTP request" {
        submit("Analyst", "Editor") shouldBe emptyList()
    }
})
