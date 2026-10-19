package io.whozoss.agentos.userGroup

import io.whozoss.agentos.entity.EntityRepository
import java.util.UUID

interface UserGroupRepository : EntityRepository<UserGroup, UUID> {
    fun findByNamespaceId(namespaceId: UUID): List<UserGroupSearchResult>
    fun findByIdWithDetails(id: UUID): UserGroupSearchResult?
    fun findMembers(userGroupId: UUID): List<UserGroupMember>
    fun addAgents(userGroupId: UUID, agentConfigIds: Collection<UUID>)
    fun removeAllAgents(userGroupId: UUID)
    fun addUsers(userGroupId: UUID, userExternalIds: Collection<String>)
    fun removeUsers(userGroupId: UUID, userExternalIds: Collection<String>)

    /**
     * Unlinks the user (by id) from every UserGroup within a single namespace.
     * Used by namespace-scoped user offboarding.
     */
    fun removeUserFromGroupsInNamespace(userId: String, namespaceId: UUID)

    /**
     * Returns groups for the given user external IDs, optionally scoped to a namespace.
     *
     * When [namespaceId] is null, groups from all namespaces are returned.
     * When [namespaceId] is provided, only groups belonging to that namespace are returned.
     */
    fun findGroupsByUserExternalIds(externalIds: Collection<String>, namespaceId: UUID? = null): Map<String, List<UserGroupSummary>>

    /**
     * Returns all non-removed users who are members (MEMBER or ADMIN) of any non-removed
     * UserGroup that has [agentConfigId] deployed to it via a [:DEPLOYED_TO] edge.
     * Deduplicates users that belong to multiple qualifying groups.
     */
    fun findUsersByAgentId(agentConfigId: UUID): List<UserGroupMember>

    /**
     * Returns all non-removed members of [userGroupId] if — and only if — [agentConfigId]
     * is deployed to that group. Returns an empty list when the agent is not deployed to the group.
     */
    fun findMembersByGroupAndAgent(userGroupId: UUID, agentConfigId: UUID): List<UserGroupMember>
}
