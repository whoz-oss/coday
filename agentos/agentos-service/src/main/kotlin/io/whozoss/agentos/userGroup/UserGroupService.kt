package io.whozoss.agentos.userGroup

import io.whozoss.agentos.entity.EntityService
import java.util.UUID

interface UserGroupService : EntityService<UserGroup, UUID> {
    fun findByNamespaceExternalId(externalId: String): List<UserGroupSearchResult>
    fun findByIdWithDetails(id: UUID): UserGroupSearchResult?
    fun createFromRequest(request: UserGroupCreateRequest): UserGroupSearchResult
    fun updateFromRequest(userGroupId: UUID, request: UserGroupUpdateRequest): UserGroupSearchResult
    fun findGroupsByUserExternalIds(externalIds: Collection<String>): Map<String, List<UserGroupSummary>>
}
