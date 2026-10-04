package io.whozoss.agentos.plugins.factorybridge

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.plugins.factorybridge.tools.FactoryGetBlockersTool
import io.whozoss.agentos.plugins.factorybridge.tools.FactoryGetRequiredHumanActionsTool
import io.whozoss.agentos.plugins.factorybridge.tools.FactoryGetStepAttemptsTool
import io.whozoss.agentos.plugins.factorybridge.tools.FactoryGetWorkflowTool
import io.whozoss.agentos.plugins.factorybridge.tools.FactoryGetWorkstreamTool
import io.whozoss.agentos.plugins.factorybridge.tools.FactoryListWorkflowsTool
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolPlugin
import org.pf4j.Extension

/**
 * Builds the six read-only `FACTORY_WORKSTREAM__*` tools from the shared plugin services.
 */
internal fun buildFactoryWorkstreamTools(services: FactoryBridgeServices): List<StandardTool<*>> {
    val baseUrl = services.config.baseUrl
    val httpClient = services.httpClient
    val objectMapper = services.objectMapper
    return listOf(
        FactoryGetWorkflowTool(baseUrl, httpClient, objectMapper),
        FactoryGetWorkstreamTool(baseUrl, httpClient, objectMapper),
        FactoryListWorkflowsTool(baseUrl, httpClient, objectMapper),
        FactoryGetStepAttemptsTool(baseUrl, httpClient, objectMapper),
        FactoryGetBlockersTool(baseUrl, httpClient, objectMapper),
        FactoryGetRequiredHumanActionsTool(baseUrl, httpClient, objectMapper),
    )
}

/**
 * Tool provider for the `FACTORY_WORKSTREAM` integration — the Workstream trust boundary.
 *
 * Exposes exactly the six read-only Workstream tools (`FACTORY_WORKSTREAM__get_workstream`,
 * `__list_workflows`, `__get_workflow`, `__get_step_attempts`, `__get_blockers`,
 * `__get_required_human_actions`). Pure reads with no mutation surface: they are never
 * capability-gated (see [FactoryToolGrantPolicy]) and all identities are derived from the
 * trusted [ToolContext] rather than from model-authored input.
 *
 * The integration needs no parameters, but unlike the retired config-less `FACTORY`
 * plugin it declares a non-null empty-object [configSchema] so it registers in the
 * standard integration catalog and resolves through the ordinary
 * `ToolResolverService` flow (catalog + allowlist filtering).
 */
@Extension
class FactoryWorkstreamToolPlugin
    @JvmOverloads
    constructor(
        private val services: () -> FactoryBridgeServices = { FactoryBridgePluginHolder.current },
    ) : ToolPlugin {
        override val integrationType = INTEGRATION_TYPE

        override val configSchema: JsonNode = CONFIG_SCHEMA

        override fun provideTools(
            config: JsonNode?,
            configName: String?,
            context: ToolContext?,
        ): List<StandardTool<*>> = buildFactoryWorkstreamTools(services())

        companion object {
            const val INTEGRATION_TYPE = "FACTORY_WORKSTREAM"

            val CONFIG_SCHEMA: JsonNode = jacksonObjectMapper().readTree(
                """
                {
                    "type": "object",
                    "title": "Factory Workstream Integration",
                    "description": "Read-only visibility over Factory workstreams and workflows for the Workstream Agent persona. No configuration required.",
                    "properties": {},
                    "additionalProperties": false
                }
                """.trimIndent(),
            )
        }
    }
