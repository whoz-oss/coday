package io.whozoss.agentos.userGroup

import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.tool.ToolPlugin
import mu.KLogging
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Spring configuration for the USER_GROUP internal integration.
 *
 * Follows the same pattern as [io.whozoss.agentos.casePlugin.CasePluginConfiguration]:
 * inject the repository and [PermissionService] directly rather than the full service,
 * to avoid a circular Spring dependency.
 *
 * The dependency chain that makes [UserGroupService] unsafe to inject here:
 * ```
 * UserGroupServiceImpl
 *   → NamespaceService / PermissionService / UserService / AgentConfigRepository
 *     → (transitive) → ToolRegistryService
 *       → springToolPlugins: List<ToolPlugin>
 *         → UserGroupToolPluginConfiguration → UserGroupService  ← cycle
 * ```
 *
 * By injecting [UserGroupRepository] and [PermissionService] directly — neither of which
 * has an upward dependency on [io.whozoss.agentos.tool.ToolRegistryService] — the cycle
 * is eliminated.
 *
 * ## Authorization
 *
 * Each loader lambda enforces the same access rules as the corresponding REST endpoint:
 * - namespace isolation: the group must belong to the caller's namespace
 * - permission check: the caller must hold UserGroup READ (via [PermissionService.hasPermission])
 * - anonymous calls (userId == null): fail-closed, return null / empty
 *
 * Returning null from a loader causes the tool to surface a NOT_FOUND error to the agent,
 * matching the @HideOnAccessDenied behaviour on the REST side.
 */
@Configuration
class UserGroupToolPluginConfiguration(
    private val userGroupRepository: UserGroupRepository,
    private val permissionService: PermissionService,
) {
    @Bean
    fun userGroupToolPlugin(): ToolPlugin =
        UserGroupToolPlugin(
            membersLoader = { groupId, namespaceId, userId ->
                val group = userGroupRepository.findByIds(listOf(groupId)).firstOrNull()
                    ?: return@UserGroupToolPlugin null
                if (group.namespaceId != namespaceId) return@UserGroupToolPlugin null
                if (userId == null || !permissionService.hasPermission(
                        userId.toString(), EntityType.USER_GROUP, groupId.toString(), Action.READ,
                    )
                ) return@UserGroupToolPlugin null
                userGroupRepository.findMembers(groupId)
            },
            usersByAgentLoader = { agentConfigId, namespaceId, userId ->
                // For this query there is no single group to check READ on — the agent
                // spans multiple groups. We require the caller to hold Namespace READ
                // (the same floor that GET /api/user-groups?namespaceId= enforces),
                // which ensures the result is scoped to groups in the caller's namespace.
                if (userId == null || !permissionService.hasPermission(
                        userId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.READ,
                    )
                ) return@UserGroupToolPlugin null
                userGroupRepository.findUsersByAgentId(agentConfigId, namespaceId)
            },
            membersByGroupAndAgentLoader = { groupId, agentConfigId, namespaceId, userId ->
                val group = userGroupRepository.findByIds(listOf(groupId)).firstOrNull()
                    ?: return@UserGroupToolPlugin null
                if (group.namespaceId != namespaceId) return@UserGroupToolPlugin null
                if (userId == null || !permissionService.hasPermission(
                        userId.toString(), EntityType.USER_GROUP, groupId.toString(), Action.READ,
                    )
                ) return@UserGroupToolPlugin null
                userGroupRepository.findMembersByGroupAndAgent(groupId, agentConfigId)
            },
        )

    companion object : KLogging()
}
