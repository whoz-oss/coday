package io.whozoss.agentos.userGroup

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolPlugin
import java.util.UUID

/**
 * Spring-managed [ToolPlugin] that provides the LIST_USER_GROUP_MEMBERS integration.
 *
 * Unlike PF4J plugins, this class is NOT annotated with `@Component` — it is
 * instantiated as a `@Bean` in [UserGroupToolPluginConfiguration] so that the
 * [UserGroupService] dependency can be wired without creating a circular Spring
 * dependency through [io.whozoss.agentos.tool.ToolRegistryService].
 *
 * [io.whozoss.agentos.tool.ToolRegistryService] collects all `ToolPlugin` beans via
 * its `springToolPlugins: List<ToolPlugin>` constructor parameter, so this bean is
 * automatically discovered alongside PF4J-loaded plugins.
 *
 * ## Configuration
 *
 * This plugin declares a non-null [configSchema] (an empty object schema) so that it is
 * catalogued by [io.whozoss.agentos.integrationConfig.CompositeIntegrationTypeRegistry]:
 * plugins with a null schema are skipped during registration and cannot be declared in
 * the integration form. An empty schema means the integration requires no parameters —
 * the admin simply creates an [io.whozoss.agentos.integrationConfig.IntegrationConfig]
 * of type [INTEGRATION_TYPE] and assigns it to the relevant agent.
 *
 * ## Public API alignment
 *
 * The membership data returned by this tool is identical to what the REST endpoint
 * `GET /api/user-groups/{userGroupId}/members` returns, and the same [UserGroupService]
 * method ([UserGroupService.getMembers]) is used in both paths. Clients that prefer HTTP
 * over agent-tool invocation can call the existing endpoint directly.
 */
class UserGroupToolPlugin(
    private val membersLoader: (UUID) -> List<UserGroupMember>,
) : ToolPlugin {
    override val integrationType: String = INTEGRATION_TYPE

    /**
     * Empty object schema: the integration needs no configuration parameters.
     * Non-null so the type is registered in the integration catalogue.
     */
    override val configSchema: JsonNode = CONFIG_SCHEMA

    override fun provideTools(
        config: JsonNode?,
        configName: String?,
        context: ToolContext?,
    ): List<StandardTool<*>> = listOf(ListUserGroupMembersTool(configName = configName, membersLoader = membersLoader))

    companion object {
        const val INTEGRATION_TYPE = "USER_GROUP"

        val CONFIG_SCHEMA: JsonNode =
            jacksonObjectMapper().readTree(
                """
                {
                  "type": "object",
                  "title": "User Group Configuration",
                  "description": "Allows an agent to list all users belonging to a user group.",
                  "properties": {},
                  "additionalProperties": false
                }
                """.trimIndent(),
            )
    }
}
