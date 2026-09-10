package io.whozoss.agentos.agentosPlugin

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolPlugin
import mu.KLogging
import org.springframework.stereotype.Service

/**
 * Internal Spring-managed [ToolPlugin] that provides the AGENTOS integration.
 *
 * Registered as a `@Service` so Spring injects it directly into the `List<ToolPlugin>`
 * collected by [io.whozoss.agentos.tool.ToolRegistryService]. No `@Configuration` class
 * is needed because [AgentAdminOperationsImpl] depends only on [AgentConfigService] and
 * [PermissionService], neither of which pulls in [ToolRegistryService] or any [ToolPlugin].
 *
 * An admin creates an [io.whozoss.agentos.integrationConfig.IntegrationConfig] of
 * type `AGENTOS_AGENTS` and assigns it to the relevant
 * [io.whozoss.agentos.agentConfig.AgentConfig] via `integrations`. The tools are
 * then available to that agent for managing other agents in the same namespace.
 *
 * This is a config-less plugin: the [configSchema] accepts no properties. All
 * access control is enforced by [AgentAdminOperations].
 *
 * Exposed tools:
 * - [ListAgentsTool] — list agents in the namespace (Namespace READ)
 * - [GetAgentTool] — retrieve a single agent by name (AgentConfig READ, resolved transitively)
 * - [CreateAgentTool] — create a new agent (Namespace WRITE)
 * - [UpdateAgentTool] — update an existing agent (AgentConfig WRITE)
 * - [SetAgentEnabledTool] — enable or disable an agent (AgentConfig WRITE)
 * - [SetAgentDeploymentTool] — deploy or undeploy an agent on the namespace (Namespace WRITE)
 */
@Service
class AgentosAgentsToolPlugin(
    private val operations: AgentAdminOperations,
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
            ListAgentsTool(configName = configName, operations = operations),
            GetAgentTool(configName = configName, operations = operations),
            CreateAgentTool(configName = configName, operations = operations),
            UpdateAgentTool(configName = configName, operations = operations),
            SetAgentEnabledTool(configName = configName, operations = operations),
            SetAgentDeploymentTool(configName = configName, operations = operations),
        )
    }

    companion object : KLogging() {
        const val INTEGRATION_TYPE = "AGENTOS_AGENTS"

        val CONFIG_SCHEMA: JsonNode =
            jacksonObjectMapper().readTree(
                """
                {
                    "type": "object",
                    "title": "AgentOS Plugin Configuration",
                    "description": "Allows an agent to list, create and update other agents in the same namespace.",
                    "properties": {},
                    "additionalProperties": false
                }
                """.trimIndent(),
            )
    }
}
