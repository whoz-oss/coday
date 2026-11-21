package io.whozoss.agentos.selfConfig

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.integrationConfig.IntegrationTypeConstraints
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolPlugin

/**
 * Internal Spring-managed [ToolPlugin] providing the `SELF_CONFIG` integration: letting an agent
 * manage the **calling user's own** integration configurations.
 *
 * Five tools, all prefixed with the configuration's name:
 * - `listIntegrationTypes` -- the catalogue, with each type's parameter schema
 * - `listIntegrations` -- the user's own configurations, parameters included
 * - `createIntegration`, `updateIntegration`, `deleteIntegration`
 *
 * Like [io.whozoss.agentos.redirect.RedirectToolPlugin] and
 * [io.whozoss.agentos.userGroup.UserGroupToolPlugin], this class is NOT annotated `@Component`:
 * it is instantiated as a `@Bean` in [SelfConfigToolPluginConfiguration] so its dependencies can
 * be wired without closing the Spring cycle that runs through
 * [io.whozoss.agentos.tool.ToolRegistryService], which collects every `ToolPlugin` bean.
 *
 * ## Why the schema is empty, and why it is not null
 *
 * [CONFIG_SCHEMA] declares no parameters. That is a decision, not an omission: a parameter on
 * *this* type would be configuration about configuration, and since a user may create a
 * user-scoped overlay of any type they are allowed, such a parameter would be the one knob an
 * agent could turn to widen its own reach. The grant itself is the only lever, and it stays with
 * whoever assigns the integration to an agent.
 *
 * It is nevertheless a non-null, empty **object** schema, because
 * [io.whozoss.agentos.integrationConfig.CompositeIntegrationTypeRegistry.registerFromPlugin] skips
 * any plugin whose `configSchema` is null -- a null here would make `SELF_CONFIG` permanently
 * absent from the catalogue and therefore undeclarable in the integration form. Same reasoning,
 * and same empty-object shape, as [io.whozoss.agentos.userGroup.UserGroupToolPlugin.CONFIG_SCHEMA].
 *
 * ## Granting
 *
 * `SELF_CONFIG` is deliberately **not** auto-grantable (see
 * [IntegrationTypeConstraints.SELF_CONFIG_TYPE]): an agent able to rewrite a user's configuration
 * is an explicit, reviewed assignment. An agent opts in by naming the configuration in its
 * `integrations` map, optionally narrowing it to the read tools
 * (`SELF_CONFIG: [listIntegrationTypes, listIntegrations]`).
 */
class SelfConfigToolPlugin(
    private val selfConfigToolService: SelfConfigToolService,
) : ToolPlugin {
    override val integrationType: String = INTEGRATION_TYPE

    override val configSchema: JsonNode = CONFIG_SCHEMA

    override fun provideTools(
        config: JsonNode?,
        configName: String?,
        context: ToolContext?,
    ): List<StandardTool<*>> =
        listOf(
            ListIntegrationTypesTool(configName = configName, typesLoader = selfConfigToolService::listTypes),
            ListIntegrationsTool(configName = configName, listLoader = selfConfigToolService::listOwn),
            CreateIntegrationTool(configName = configName, creator = selfConfigToolService::create),
            UpdateIntegrationTool(configName = configName, updater = selfConfigToolService::update),
            DeleteIntegrationTool(configName = configName, deleter = selfConfigToolService::delete),
        )

    companion object {
        const val INTEGRATION_TYPE = IntegrationTypeConstraints.SELF_CONFIG_TYPE

        val CONFIG_SCHEMA: JsonNode =
            jacksonObjectMapper().readTree(
                """
                {
                  "type": "object",
                  "title": "Self Configuration",
                  "description": "Lets an agent read the integration catalogue and manage the calling user's own personal integration configurations. Shared (namespace or platform) configurations are never modified through this integration.",
                  "properties": {},
                  "additionalProperties": false
                }
                """.trimIndent(),
            )
    }
}
