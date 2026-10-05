package io.whozoss.agentos.plugins.factorybridge

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.plugins.factorybridge.tools.FactorySubmitStepResultTool
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolPlugin
import org.pf4j.Extension

/**
 * Builds the terminal worker result tool from the shared plugin services.
 *
 * Human questions are emitted exclusively by AgentOS' standard `queryUser`
 * capability. The retired Factory ask-step tool remains source-compatible for
 * old in-flight data, but is no longer exposed to agents.
 */
internal fun buildFactoryWorkerTools(services: FactoryBridgeServices): List<StandardTool<*>> {
    val baseUrl = services.config.baseUrl
    val httpClient = services.httpClient
    val objectMapper = services.objectMapper
    return listOf(
        FactorySubmitStepResultTool(
            baseUrl,
            httpClient,
            objectMapper,
            services.stepResultBindings,
            services.capabilityRefresher,
        ),
    )
}

/**
 * Tool provider for the `FACTORY_WORKER` integration — the Worker trust boundary.
 *
 * Exposes only `FACTORY_WORKER__submit_step_result`. AgentConfig and
 * IntegrationConfig determine exposure; the tool authorizes each invocation
 * against the active Factory attempt binding and resolves attempt identity from
 * it, never from model-authored input. Agent questions use AgentOS `queryUser`.
 *
 * The integration needs no parameters, but unlike the retired config-less `FACTORY`
 * plugin it declares a non-null empty-object [configSchema] so it registers in the
 * standard integration catalog and resolves through the ordinary
 * `ToolResolverService` flow (catalog + allowlist filtering).
 */
@Extension
class FactoryWorkerToolPlugin
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
        ): List<StandardTool<*>> = buildFactoryWorkerTools(services())

        companion object {
            const val INTEGRATION_TYPE = "FACTORY_WORKER"

            val CONFIG_SCHEMA: JsonNode = jacksonObjectMapper().readTree(
                """
                {
                    "type": "object",
                    "title": "Factory Worker Integration",
                    "description": "Case-scoped worker result channel. Human questions use AgentOS queryUser. Fail-closed on an active Factory attempt binding. No configuration required.",
                    "properties": {},
                    "additionalProperties": false
                }
                """.trimIndent(),
            )
        }
    }
