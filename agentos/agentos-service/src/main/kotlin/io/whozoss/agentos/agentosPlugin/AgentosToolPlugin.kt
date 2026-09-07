package io.whozoss.agentos.agentosPlugin

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolPlugin
import mu.KLogging
import java.util.UUID

/**
 * Internal Spring-managed [ToolPlugin] that provides the AGENTOS integration.
 *
 * Like [io.whozoss.agentos.casePlugin.CaseToolPlugin], this class is NOT annotated
 * with `@Component` — it is instantiated as a `@Bean` in [AgentosPluginConfiguration]
 * so that the operation lambdas can be wired without a circular Spring dependency.
 * [io.whozoss.agentos.tool.ToolRegistryService] collects it alongside PF4J-loaded
 * plugins via `List<ToolPlugin>` constructor injection.
 *
 * An admin creates an [io.whozoss.agentos.integrationConfig.IntegrationConfig] of
 * type `AGENTOS` and assigns it to the relevant
 * [io.whozoss.agentos.agentConfig.AgentConfig] via `integrations`. The tools are
 * then available to that agent for managing other agents in the same namespace.
 *
 * This is a config-less plugin: the [configSchema] accepts no properties. All
 * access control is enforced by [AgentosPluginConfiguration] via [PermissionService].
 *
 * Exposed tools:
 * - [ListAgentsTool] — list agents in the namespace (Namespace READ)
 * - [GetAgentTool] — retrieve a single agent by name (AgentConfig READ)
 * - [CreateAgentTool] — create a new agent (Namespace WRITE)
 * - [UpdateAgentTool] — update an existing agent (AgentConfig WRITE)
 * - [EnableAgentTool] — enable an agent (AgentConfig WRITE)
 * - [DisableAgentTool] — disable an agent (AgentConfig WRITE)
 */
class AgentosToolPlugin(
    private val listAgents: (namespaceId: UUID, userId: UUID?, withDisabled: Boolean) -> List<AgentConfig>?,
    private val getAgent: (namespaceId: UUID, userId: UUID?, name: String) -> AgentConfig?,
    private val createAgent: (namespaceId: UUID, userId: UUID?, input: CreateAgentTool.Input) -> AgentConfig?,
    private val updateAgent: (namespaceId: UUID, userId: UUID?, input: UpdateAgentTool.Input) -> AgentConfig?,
    private val enableAgent: (namespaceId: UUID, userId: UUID?, name: String) -> AgentConfig?,
    private val disableAgent: (namespaceId: UUID, userId: UUID?, name: String) -> AgentConfig?,
) : ToolPlugin {

    override val integrationType: String = INTEGRATION_TYPE
    override val configSchema: JsonNode = CONFIG_SCHEMA

    override fun provideTools(
        config: JsonNode?,
        configName: String?,
        context: ToolContext?,
    ): List<StandardTool<*>> {
        val namespaceId = context?.namespaceId
        if (namespaceId == null) {
            logger.warn { "[AgentosToolPlugin] No namespaceId in context, cannot provide agent management tools" }
            return emptyList()
        }
        return listOf(
            ListAgentsTool(configName = configName, listAgents = listAgents),
            GetAgentTool(configName = configName, getAgent = getAgent),
            CreateAgentTool(configName = configName, createAgent = createAgent),
            UpdateAgentTool(configName = configName, updateAgent = updateAgent),
            EnableAgentTool(configName = configName, enableAgent = enableAgent),
            DisableAgentTool(configName = configName, disableAgent = disableAgent),
        )
    }

    companion object : KLogging() {
        const val INTEGRATION_TYPE = "AGENTOS"

        val CONFIG_SCHEMA: JsonNode = jacksonObjectMapper().readTree(
            """
            {
                "type": "object",
                "title": "AgentOS Plugin Configuration",
                "description": "Allows an agent to list, create and update other agents in the same namespace.",
                "properties": {},
                "additionalProperties": false
            }
            """.trimIndent()
        )
    }
}
