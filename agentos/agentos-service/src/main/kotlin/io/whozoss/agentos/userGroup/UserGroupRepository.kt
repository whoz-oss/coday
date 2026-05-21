package io.whozoss.agentos.userGroup

import io.whozoss.agentos.entity.EntityRepository
import java.util.UUID

interface UserGroupRepository : EntityRepository<UserGroup, UUID> {
    fun findByNamespaceId(namespaceId: UUID): List<UserGroupSearchResult>
    fun findByIdWithDetails(id: UUID): UserGroupSearchResult?
    fun addAgents(userGroupId: UUID, agentConfigIds: Collection<UUID>)
    fun removeAllAgents(userGroupId: UUID)
    fun addUsers(userGroupId: UUID, userExternalIds: Collection<String>)
    fun removeUsers(userGroupId: UUID, userExternalIds: Collection<String>)
    fun findGroupsByUserExternalIds(externalIds: Collection<String>): Map<String, List<UserGroupSummary>>
}
