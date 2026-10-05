package io.whozoss.agentos.plugins.factorybridge

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.plugins.factorybridge.tools.FactoryAskStepQuestionTool
import io.whozoss.agentos.plugins.factorybridge.tools.FactorySubmitStepResultTool
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolPlugin
import org.pf4j.Extension

/**
 * Builds the two worker `FACTORY_WORKER__*` tools from the shared plugin services.
 */
internal fun buildFactoryWorkerTools(services: FactoryBridgeServices): List<StandardTool<*>> {
    val baseUrl = services.config.baseUrl
    val httpClient = services.httpClient
    val objectMapper = services.objectMapper
    return listOf(
        FactorySubmitStepResultTool(baseUrl, httpClient, objectMapper, services.stepResultBindings),
        FactoryAskStepQuestionTool(baseUrl, httpClient, objectMapper, services.stepResultBindings),
    )
}

/**
 * Tool provider for the `FACTORY_WORKER` integration — the Worker trust boundary.
 *
 * Exposes exactly the two worker tools (`FACTORY_WORKER__submit_step_result`,
 * `FACTORY_WORKER__ask_step_question`). AgentConfig and IntegrationConfig determine
 * exposure; the tools themselves authorize each invocation against the active Factory
 * attempt binding and resolve attempt identity from it, never from model-authored input.
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
                    "description": "Case-scoped worker result channel (submit step result, ask step question), fail-closed on an active Factory attempt binding. No configuration required.",
                    "properties": {},
                    "additionalProperties": false
                }
                """.trimIndent(),
            )
        }
    }
