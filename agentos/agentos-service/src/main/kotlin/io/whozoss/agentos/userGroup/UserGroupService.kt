package io.whozoss.agentos.userGroup

import io.whozoss.agentos.entity.EntityService
import io.whozoss.agentos.sdk.api.userGroup.UserGroupCreateRequest
import io.whozoss.agentos.user.User
import java.util.UUID

interface UserGroupService : EntityService<UserGroup, UUID> {
    fun findByNamespaceId(namespaceId: UUID): List<UserGroupSearchResult>

    fun findByIdWithDetails(id: UUID): UserGroupSearchResult?

    fun getMembers(userGroupId: UUID): List<UserGroupMember>

    fun createFromRequest(request: UserGroupCreateRequest): UserGroupSearchResult

    fun updateFromRequest(
        userGroupId: UUID,
        request: UserGroupUpdateRequest,
    ): UserGroupSearchResult

    /**
     * Returns groups for the given user external IDs visible to [user], optionally scoped to a namespace.
     *
     * When [namespaceId] is null, groups from all namespaces are returned.
     * When [namespaceId] is provided, only groups belonging to that namespace are returned.
     */
    fun findGroupsByUserExternalIdsVisibleToUser(
        externalIds: Collection<String>,
        user: User,
        namespaceId: UUID? = null,
    ): Map<String, List<UserGroupSummary>>
}
