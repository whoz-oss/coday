package io.whozoss.agentos.userGroup

import io.whozoss.agentos.entity.EntityService
import io.whozoss.agentos.pagination.KeysetPage
import io.whozoss.agentos.pagination.KeysetPaging
import io.whozoss.agentos.sdk.api.userGroup.UserGroupCreateRequest
import io.whozoss.agentos.user.User
import java.util.UUID

interface UserGroupService : EntityService<UserGroup, UUID> {
    fun findByNamespaceId(namespaceId: UUID): List<UserGroupSearchResult>

    fun findByIdWithDetails(id: UUID): UserGroupSearchResult?

    fun getMembers(userGroupId: UUID): List<UserGroupMember>

    /**
     * One page of the members of [userGroupId], sorted by external id; [limit] is 1..500 (default 100, validated at the edge) and [next]
     * is the cursor of the previous page (see [KeysetPaging]). `totalCount` is the whole roster size.
     */
    fun getMembersPage(userGroupId: UUID, limit: Int?, next: String?): KeysetPage<UserGroupMember>

    /**
     * Returns all non-removed users who are members of any UserGroup in [namespaceId]
     * that has [agentConfigId] deployed to it.
     */
    fun getUsersByAgent(agentConfigId: UUID, namespaceId: UUID): List<UserGroupMember>

    /**
     * Returns all members of [userGroupId] if [agentConfigId] is deployed to that group,
     * or an empty list when the agent is not deployed there.
     */
    fun getMembersByGroupAndAgent(userGroupId: UUID, agentConfigId: UUID): List<UserGroupMember>

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
