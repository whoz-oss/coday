package io.whozoss.agentos.plugins.factorybridge

import com.fasterxml.jackson.databind.JsonNode
import io.whozoss.agentos.plugins.factorybridge.tools.FactoryGetWorkflowTool
import io.whozoss.agentos.plugins.factorybridge.tools.FactoryProvisionEnvironmentTool
import io.whozoss.agentos.plugins.factorybridge.tools.FactoryPublishProjectionTool
import io.whozoss.agentos.plugins.factorybridge.tools.FactoryRecordAgentResultTool
import io.whozoss.agentos.plugins.factorybridge.tools.FactoryRecordArtifactTool
import io.whozoss.agentos.plugins.factorybridge.tools.FactoryRequestHumanDecisionTool
import io.whozoss.agentos.plugins.factorybridge.tools.FactoryRequestTransitionTool
import io.whozoss.agentos.plugins.factorybridge.tools.FactoryStartWorkflowTool
import io.whozoss.agentos.plugins.factorybridge.tools.FactorySubmitStepResultTool
import io.whozoss.agentos.plugins.factorybridge.tools.FactoryTransitionWorkflowTool
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolPlugin
import org.pf4j.Extension

/**
 * Instantiates every `FACTORY__*` tool from the shared plugin services.
 *
 * Extracted as a top-level function so both [FactoryToolPlugin] and
 * [FactoryToolGrantService] build the exact same tool set.
 */
internal fun buildFactoryTools(services: FactoryBridgeServices): List<StandardTool<*>> {
    val baseUrl = services.config.baseUrl
    val httpClient = services.httpClient
    val objectMapper = services.objectMapper
    val runtimeId = services.config.runtimeId
    return listOf(
        FactoryGetWorkflowTool(baseUrl, httpClient, objectMapper),
        FactoryProvisionEnvironmentTool(baseUrl, httpClient, objectMapper),
        FactoryStartWorkflowTool(baseUrl, httpClient, objectMapper, runtimeId),
        FactoryRecordAgentResultTool(baseUrl, httpClient, objectMapper, runtimeId),
        FactoryRecordArtifactTool(baseUrl, httpClient, objectMapper, runtimeId),
        FactorySubmitStepResultTool(baseUrl, httpClient, objectMapper, services.stepResultBindings),
        FactoryRequestHumanDecisionTool(baseUrl, httpClient, objectMapper, runtimeId),
        FactoryRequestTransitionTool(baseUrl, httpClient, objectMapper, runtimeId),
        FactoryTransitionWorkflowTool(baseUrl, httpClient, objectMapper, runtimeId),
        FactoryPublishProjectionTool(baseUrl, httpClient, objectMapper, runtimeId),
    )
}

/**
 * Tool provider for the `FACTORY` integration.
 *
 * Config-less by design: agents must explicitly grant each FACTORY capability
 * (see [FactoryToolGrantService]), and all identities are derived from the trusted
 * [ToolContext] rather than from model-authored input.
 */
@Extension
class FactoryToolPlugin
    @JvmOverloads
    constructor(
        private val services: () -> FactoryBridgeServices = { FactoryBridgePluginHolder.current },
    ) : ToolPlugin {
        override val integrationType = INTEGRATION_TYPE

        override val configSchema: JsonNode? = null

        override fun provideTools(
            config: JsonNode?,
            configName: String?,
            context: ToolContext?,
        ): List<StandardTool<*>> = buildFactoryTools(services())

        companion object {
            const val INTEGRATION_TYPE = "FACTORY"
        }
    }
