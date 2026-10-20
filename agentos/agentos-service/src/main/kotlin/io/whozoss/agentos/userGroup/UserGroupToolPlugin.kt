package io.whozoss.agentos.userGroup

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.integrationConfig.IntegrationTypeConstraints
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolPlugin
import java.util.UUID

/**
 * Spring-managed [ToolPlugin] that provides the USER_GROUP integration.
 *
 * Not annotated with `@Component` — instantiated as a `@Bean` in
 * [UserGroupToolPluginConfiguration] so that [UserGroupRepository] and
 * [io.whozoss.agentos.permissions.PermissionService] can be wired without creating a
 * circular Spring dependency through [io.whozoss.agentos.tool.ToolRegistryService].
 *
 * ## Tools provided
 *
 * - [ListUserGroupMembersTool] (`listMembers`) — list all members of a group
 * - [ListAgentUsersTool] (`listAgentUsers`) — list all users who have access to an agent
 * - [ListGroupAgentUsersTool] (`listGroupAgentUsers`) — list members of a group that
 *   has a specific agent deployed to it
 *
 * ## Authorization
 *
 * All three loaders enforce namespace isolation and permission checks inside
 * [UserGroupToolPluginConfiguration]. The data returned by [ListUserGroupMembersTool]
 * is identical to `GET /api/user-groups/{id}/members` minus the Spring Security layer;
 * that layer is replaced here by explicit [io.whozoss.agentos.permissions.PermissionService]
 * checks in the loader lambdas.
 *
 * ## Configuration
 *
 * Non-null [configSchema] (empty object) so the type appears in the integration catalogue.
 * No parameters are needed — all access decisions are runtime, not config-time.
 */
class UserGroupToolPlugin(
    private val membersLoader: suspend (groupId: UUID, namespaceId: UUID, userId: UUID?) -> List<UserGroupMember>?,
    private val usersByAgentLoader: suspend (agentConfigId: UUID, namespaceId: UUID, userId: UUID?) -> List<UserGroupMember>?,
    private val membersByGroupAndAgentLoader: suspend (
        groupId: UUID,
        agentConfigId: UUID,
        namespaceId: UUID,
        userId: UUID?,
    ) -> List<UserGroupMember>?,
) : ToolPlugin {
    override val integrationType: String = INTEGRATION_TYPE

    override val configSchema: JsonNode = CONFIG_SCHEMA

    override fun provideTools(
        config: JsonNode?,
        configName: String?,
        context: ToolContext?,
    ): List<StandardTool<*>> =
        listOf(
            ListUserGroupMembersTool(configName = configName, membersLoader = membersLoader),
            ListAgentUsersTool(configName = configName, usersByAgentLoader = usersByAgentLoader),
            ListGroupAgentUsersTool(
                configName = configName,
                membersByGroupAndAgentLoader = membersByGroupAndAgentLoader,
            ),
        )

    companion object {
        const val INTEGRATION_TYPE = IntegrationTypeConstraints.USER_GROUP_TYPE

        val CONFIG_SCHEMA: JsonNode =
            jacksonObjectMapper().readTree(
                """
                {
                  "type": "object",
                  "title": "User Group Configuration",
                  "description": "Find users thanks to their UserGroup membership and/or their access to an agent.",
                  "properties": {},
                  "additionalProperties": false
                }
                """.trimIndent(),
            )
    }
}
