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

/**
 * Builds the eight `FACTORY_WORKSTREAM__*` tools from the shared plugin services: the six
 * read-only views plus the two boundary request commands (`start_workflow` and the governed
 * `request_agent_retry`).
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
        FactoryStartWorkflowTool(baseUrl, httpClient, objectMapper, services.trustedHeaderSigner),
        FactoryRequestAgentRetryTool(baseUrl, httpClient, objectMapper, services.trustedHeaderSigner),
    )
}

/**
 * Tool provider for the `FACTORY_WORKSTREAM` integration — the Workstream trust boundary.
 *
 * Exposes exactly eight Workstream tools: the six read-only views
 * (`FACTORY_WORKSTREAM__get_workstream`, `__list_workflows`, `__get_workflow`,
 * `__get_step_attempts`, `__get_blockers`, `__get_required_human_actions`) plus two boundary
 * request commands (`FACTORY_WORKSTREAM__start_workflow` and the governed
 * `FACTORY_WORKSTREAM__request_agent_retry`). The integration is no longer strictly read-only,
 * but authority remains strictly with the Factory: `start_workflow` creates an authoritative
 * governed workflow and `request_agent_retry` only opens a `pending-human` request under the
 * revision fence (unblocking/skipping steps stays human-only in the Factory cockpit). None of
 * these tools is capability-gated (see [FactoryToolGrantPolicy]) and all identities are derived
 * from the trusted [ToolContext] rather than from model-authored input.
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
                    "description": "Workstream trust boundary for the Workstream Agent persona: six read-only views over Factory workstreams/workflows plus two governed boundary request commands (start_workflow, request_agent_retry). Authority stays with the Factory — request_agent_retry opens a pending-human request under a revision fence. No configuration required.",
                    "properties": {},
                    "additionalProperties": false
                }
                """.trimIndent(),
            )
        }
    }
