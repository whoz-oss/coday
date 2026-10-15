package io.whozoss.agentos.userGroup

import io.whozoss.agentos.sdk.tool.ToolPlugin
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Spring configuration for the LIST_USER_GROUP_MEMBERS internal integration.
 *
 * Follows the same pattern as [io.whozoss.agentos.casePlugin.CasePluginConfiguration]:
 * inject the repository directly rather than the full service, to avoid a circular
 * Spring dependency.
 *
 * The dependency chain that makes [UserGroupService] unsafe to inject here:
 * ```
 * UserGroupServiceImpl
 *   → NamespaceService / PermissionService / UserService / AgentConfigRepository
 *     → (transitive) → ToolRegistryService
 *       → springToolPlugins: List<ToolPlugin>
 *         → ListUserGroupMembersConfiguration → UserGroupService  ← cycle
 * ```
 *
 * By injecting [UserGroupRepository] directly — which has no upward dependency on
 * [io.whozoss.agentos.tool.ToolRegistryService] — the cycle is eliminated. The repository
 * method [UserGroupRepository.findMembers] is the same underlying Cypher query that
 * [UserGroupServiceImpl.getMembers] delegates to, so there is no behavioural difference.
 *
 * [io.whozoss.agentos.tool.ToolRegistryService] collects all `ToolPlugin` beans via its
 * `springToolPlugins: List<ToolPlugin>` constructor parameter, so this bean is automatically
 * discovered alongside PF4J-loaded plugins and other Spring-managed plugins.
 */
@Configuration
class UserGroupToolPluginConfiguration(
    private val userGroupRepository: UserGroupRepository,
) {
    @Bean
    fun userGroupToolPlugin(): ToolPlugin = UserGroupToolPlugin(membersLoader = userGroupRepository::findMembers)
}
