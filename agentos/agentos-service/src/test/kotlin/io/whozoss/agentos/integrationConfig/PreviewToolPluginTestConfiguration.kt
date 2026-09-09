package io.whozoss.agentos.integrationConfig

import com.fasterxml.jackson.databind.JsonNode
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import io.whozoss.agentos.sdk.tool.ToolPlugin
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

/**
 * Registers a deterministic [ToolPlugin] (type [INTEGRATION_TYPE]) in the test context so the
 * MVC spec of `POST /api/integration-configs/{id}/preview-tools` can exercise a real plugin
 * through [io.whozoss.agentos.tool.ToolRegistryService] without any PF4J jar.
 */
@TestConfiguration
class PreviewToolPluginTestConfiguration {
    @Bean
    fun previewTestToolPlugin(): ToolPlugin =
        object : ToolPlugin {
            override val integrationType = INTEGRATION_TYPE
            override val configSchema: JsonNode? = null

            override fun provideTools(
                config: JsonNode?,
                configName: String?,
                context: ToolContext?,
            ): List<StandardTool<*>> = listOf(EchoTool("${configName}__Echo"))

            override suspend fun describeNamespace(
                config: JsonNode?,
                configName: String?,
                context: ToolContext?,
            ): String = "Preview namespace line for $configName"
        }

    private class EchoTool(
        override val name: String,
    ) : StandardTool<Nothing> {
        override val description = "Echoes its input"
        override val inputSchema = """{"type":"object","properties":{"text":{"type":"string"}}}"""
        override val version = "1.0.0"
        override val paramType: Class<Nothing>? = null

        override suspend fun execute(
            input: Nothing?,
            context: ToolContext,
        ): ToolExecutionResult = ToolExecutionResult.success(name)
    }

    companion object {
        const val INTEGRATION_TYPE = "PREVIEW_TEST"
    }
}
