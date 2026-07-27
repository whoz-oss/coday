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
     * Returns groups for the given user external IDs, optionally scoped to a namespace.
     *
     * When [namespaceId] is null, groups from all namespaces are returned.
     * When [namespaceId] is provided, only groups belonging to that namespace are returned.
     */
    fun findGroupsByUserExternalIds(externalIds: Collection<String>, namespaceId: UUID? = null): Map<String, List<UserGroupSummary>>
}
