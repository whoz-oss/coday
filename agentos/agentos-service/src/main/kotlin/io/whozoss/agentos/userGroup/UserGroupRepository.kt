package io.whozoss.agentos.userGroup

import io.whozoss.agentos.entity.EntityRepository
import java.util.UUID

interface UserGroupRepository : EntityRepository<UserGroup, UUID> {
    fun findByNamespaceExternalId(externalId: String): List<UserGroupSearchResult>
    fun addAgents(userGroupId: UUID, agentConfigIds: Collection<UUID>)
    fun removeAllAgents(userGroupId: UUID)
    fun addUsers(userGroupId: UUID, userExternalIds: Collection<String>)
}
