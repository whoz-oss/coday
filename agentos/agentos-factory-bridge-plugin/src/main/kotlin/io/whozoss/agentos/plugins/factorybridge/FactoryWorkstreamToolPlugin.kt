package io.whozoss.agentos.plugins.factorybridge

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.plugins.factorybridge.tools.FactoryGetBlockersTool
import io.whozoss.agentos.plugins.factorybridge.tools.FactoryGetRequiredHumanActionsTool
import io.whozoss.agentos.plugins.factorybridge.tools.FactoryGetStepAttemptsTool
import io.whozoss.agentos.plugins.factorybridge.tools.FactoryGetWorkflowTool
import io.whozoss.agentos.plugins.factorybridge.tools.FactoryGetWorkstreamTool
import io.whozoss.agentos.plugins.factorybridge.tools.FactoryListWorkflowsTool
import io.whozoss.agentos.plugins.factorybridge.tools.FactoryRequestAgentRetryTool
import io.whozoss.agentos.plugins.factorybridge.tools.FactoryStartWorkflowTool
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolPlugin
import org.pf4j.Extension

internal fun buildFactoryWorkstreamTools(services: FactoryBridgeServices): List<StandardTool<*>> = listOf(
    FactoryGetWorkflowTool(services.config.baseUrl, services.httpClient, services.objectMapper),
    FactoryGetWorkstreamTool(services.config.baseUrl, services.httpClient, services.objectMapper),
    FactoryListWorkflowsTool(services.config.baseUrl, services.httpClient, services.objectMapper),
    FactoryGetStepAttemptsTool(services.config.baseUrl, services.httpClient, services.objectMapper),
    FactoryGetBlockersTool(services.config.baseUrl, services.httpClient, services.objectMapper),
    FactoryGetRequiredHumanActionsTool(services.config.baseUrl, services.httpClient, services.objectMapper),
    FactoryStartWorkflowTool(services.config.baseUrl, services.httpClient, services.objectMapper, services.signer),
    FactoryRequestAgentRetryTool(services.config.baseUrl, services.httpClient, services.objectMapper, services.signer),
)

@Extension
class FactoryWorkstreamToolPlugin @JvmOverloads constructor(
    private val services: () -> FactoryBridgeServices = { FactoryBridgePluginHolder.current },
) : ToolPlugin {
    override val integrationType = "FACTORY_WORKSTREAM"
    override val configSchema: JsonNode = CONFIG_SCHEMA
    override fun provideTools(config: JsonNode?, configName: String?, context: ToolContext?): List<StandardTool<*>> =
        buildFactoryWorkstreamTools(services())

    companion object {
        val CONFIG_SCHEMA: JsonNode = jacksonObjectMapper().readTree(
            """{"type":"object","title":"Factory Workstream Integration","properties":{},"additionalProperties":false}""",
        )
    }
}
