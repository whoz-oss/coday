package io.whozoss.agentos.userGroup

import io.whozoss.agentos.entity.EntityService
import io.whozoss.agentos.user.User
import java.util.UUID

interface UserGroupService : EntityService<UserGroup, UUID> {
    fun findByNamespaceId(namespaceId: UUID): List<UserGroupSearchResult>
    fun findByIdWithDetails(id: UUID): UserGroupSearchResult?
    fun createFromRequest(request: UserGroupCreateRequest): UserGroupSearchResult
    fun updateFromRequest(userGroupId: UUID, request: UserGroupUpdateRequest): UserGroupSearchResult
    fun findGroupsByUserExternalIdsVisibleToUser(externalIds: Collection<String>, user: User): Map<String, List<UserGroupSummary>>
}
