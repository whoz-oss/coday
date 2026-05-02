package io.whozoss.agentos.usergroup

import java.util.UUID

interface UserGroupService {
    fun list(namespaceId: UUID): List<UserGroup>
    fun get(userGroupId: UUID): UserGroup
    fun getAgentIds(userGroupId: UUID): List<UUID>
    fun countUsers(userGroupId: UUID): Int
    fun create(request: UserGroupCreateRequest): UserGroup
    fun update(userGroupId: UUID, request: UserGroupUpdateRequest): UserGroup
    fun delete(userGroupId: UUID)
}
