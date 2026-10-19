package io.whozoss.agentos.userGroup

import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import mu.KLogging
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Service backing the USER_GROUP tool integration.
 *
 * Provides the three query operations exposed by the USER_GROUP tools, each enforcing
 * the same authorization rules as the corresponding REST endpoint:
 * - namespace isolation (group must belong to the caller's namespace)
 * - permission check via [PermissionService]
 * - anonymous calls (userId == null): fail-closed, return null
 *
 * Returning null signals NOT_FOUND or access-denied to the calling tool, which maps it
 * to [io.whozoss.agentos.sdk.tool.ToolExecutionResult.error] with errorType = "NOT_FOUND",
 * mirroring the @HideOnAccessDenied behaviour on the REST side.
 *
 * ## Why this service exists and does not extend [UserGroupService]
 *
 * [UserGroupService] / [UserGroupServiceImpl] cannot be injected into
 * [UserGroupToolPluginConfiguration] because [UserGroupServiceImpl] depends on
 * [io.whozoss.agentos.agentConfig.AgentConfigRepository], which creates a transitive
 * dependency on [io.whozoss.agentos.tool.ToolRegistryService], closing a Spring circular
 * bean cycle:
 *
 * ```
 * ToolRegistryService
 *   → springToolPlugins: List<ToolPlugin>
 *     → UserGroupToolPluginConfiguration
 *       → UserGroupServiceImpl
 *         → AgentConfigRepository → … → ToolRegistryService  ← cycle
 * ```
 *
 * This service breaks the cycle by injecting [UserGroupRepository] and [PermissionService]
 * directly — neither has an upward dependency on [ToolRegistryService]. It follows the
 * same pattern as [io.whozoss.agentos.casePlugin.CasePluginConfiguration], which injects
 * [io.whozoss.agentos.caseFlow.CaseRepository] and [io.whozoss.agentos.caseEvent.CaseEventService]
 * rather than [io.whozoss.agentos.caseFlow.CaseService] for the same reason.
 */
@Service
class UserGroupToolService(
    private val userGroupRepository: UserGroupRepository,
    private val permissionService: PermissionService,
) {
    /**
     * Returns the members of [groupId], or null when:
     * - the group does not exist
     * - the group belongs to a different namespace
     * - [userId] is null (anonymous call)
     * - the caller lacks UserGroup READ
     *
     * Mirrors `GET /api/user-groups/{id}/members` + @HideOnAccessDenied.
     */
    fun getMembers(groupId: UUID, namespaceId: UUID, userId: UUID?): List<UserGroupMember>? {
        val group = userGroupRepository.findByIds(listOf(groupId)).firstOrNull()
        val allowed = group != null &&
            group.namespaceId == namespaceId &&
            userId != null &&
            permissionService.hasPermission(userId.toString(), EntityType.USER_GROUP, groupId.toString(), Action.READ)
        return if (allowed) userGroupRepository.findMembers(groupId) else null
    }

    /**
     * Returns all users who have access to [agentConfigId] through UserGroup deployment
     * within [namespaceId], or null when:
     * - [userId] is null (anonymous call)
     * - the caller lacks Namespace READ
     *
     * Namespace READ is the same floor that `GET /api/user-groups?namespaceId=` enforces.
     */
    fun getUsersByAgent(agentConfigId: UUID, namespaceId: UUID, userId: UUID?): List<UserGroupMember>? {
        val allowed = userId != null &&
            permissionService.hasPermission(userId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.READ)
        return if (allowed) userGroupRepository.findUsersByAgentId(agentConfigId, namespaceId) else null
    }

    /**
     * Returns the members of [groupId] if [agentConfigId] is deployed to that group,
     * or an empty list when the agent is not deployed there, or null when:
     * - the group does not exist
     * - the group belongs to a different namespace
     * - [userId] is null (anonymous call)
     * - the caller lacks UserGroup READ
     */
    fun getMembersByGroupAndAgent(
        groupId: UUID,
        agentConfigId: UUID,
        namespaceId: UUID,
        userId: UUID?,
    ): List<UserGroupMember>? {
        val group = userGroupRepository.findByIds(listOf(groupId)).firstOrNull()
        val allowed = group != null &&
            group.namespaceId == namespaceId &&
            userId != null &&
            permissionService.hasPermission(userId.toString(), EntityType.USER_GROUP, groupId.toString(), Action.READ)
        return if (allowed) userGroupRepository.findMembersByGroupAndAgent(groupId, agentConfigId) else null
    }

    companion object : KLogging()
}
