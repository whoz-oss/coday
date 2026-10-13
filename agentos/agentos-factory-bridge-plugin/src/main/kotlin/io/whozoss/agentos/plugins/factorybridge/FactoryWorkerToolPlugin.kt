package io.whozoss.agentos.plugins.factorybridge

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.plugins.factorybridge.tools.FactorySubmitStepResultTool
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolPlugin
import org.pf4j.Extension

@Extension
class FactoryWorkerToolPlugin @JvmOverloads constructor(
    private val services: () -> FactoryBridgeServices = { FactoryBridgePluginHolder.current },
) : ToolPlugin {
    override val integrationType = "FACTORY_WORKER"
    override val configSchema: JsonNode = CONFIG_SCHEMA
    override fun provideTools(config: JsonNode?, configName: String?, context: ToolContext?): List<StandardTool<*>> {
        val current = services()
        return listOf(FactorySubmitStepResultTool(current.config.baseUrl, current.httpClient, current.objectMapper, current.signer))
    }

    companion object {
        val CONFIG_SCHEMA: JsonNode = jacksonObjectMapper().readTree(
            """{"type":"object","title":"Factory Worker Integration","properties":{},"additionalProperties":false}""",
        )
    }
}
