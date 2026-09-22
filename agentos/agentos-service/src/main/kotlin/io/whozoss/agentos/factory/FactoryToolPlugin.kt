package io.whozoss.agentos.factory

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolPlugin
import okhttp3.OkHttpClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

/** Config-less built-in bridge; agents must explicitly grant each FACTORY capability. */
@Component
class FactoryToolPlugin(
    private val objectMapper: ObjectMapper,
    @Value("\${agentos.factory.base-url:http://localhost:3141}") private val baseUrl: String,
    @Value("\${agentos.factory.runtime-id:agentos-primary}") private val runtimeId: String = "agentos-primary",
) : ToolPlugin {
    override val integrationType = "FACTORY"
    override val configSchema: JsonNode? = null
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    override fun provideTools(config: JsonNode?, configName: String?, context: ToolContext?): List<StandardTool<*>> =
        listOf(
            FactoryGetWorkflowTool(baseUrl, httpClient, objectMapper),
            FactoryProvisionEnvironmentTool(baseUrl, httpClient, objectMapper),
            FactoryStartWorkflowTool(baseUrl, httpClient, objectMapper, runtimeId),
            FactoryRecordAgentResultTool(baseUrl, httpClient, objectMapper, runtimeId),
            FactoryRecordArtifactTool(baseUrl, httpClient, objectMapper, runtimeId),
            FactoryRequestHumanDecisionTool(baseUrl, httpClient, objectMapper, runtimeId),
            FactoryRequestTransitionTool(baseUrl, httpClient, objectMapper, runtimeId),
            FactoryTransitionWorkflowTool(baseUrl, httpClient, objectMapper, runtimeId),
            FactoryPublishProjectionTool(baseUrl, httpClient, objectMapper, runtimeId),
        )

    companion object { const val INTEGRATION_TYPE = "FACTORY" }
}
