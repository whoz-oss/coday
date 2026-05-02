package io.whozoss.agentos.usergroup

import io.whozoss.agentos.entity.EntityRepository
import java.util.UUID

interface UserGroupRepository : EntityRepository<UserGroup, UUID> {
    fun findAgentIds(userGroupId: UUID): List<UUID>
    fun countUsers(userGroupId: UUID): Int
    fun countAgents(userGroupId: UUID): Int
    fun addUser(userGroupId: UUID, userId: UUID)
    fun removeUser(userGroupId: UUID, userId: UUID)
    fun replaceAgents(userGroupId: UUID, agentIds: Set<UUID>)
    fun softDeleteWithRelationships(userGroupId: UUID)
}
