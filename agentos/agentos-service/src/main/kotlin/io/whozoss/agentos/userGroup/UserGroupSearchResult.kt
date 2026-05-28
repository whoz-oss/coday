package io.whozoss.agentos.userGroup

import java.util.*

data class UserGroupSearchResult(
    val userGroupId: UUID,
    val namespaceId: UUID,
    val namespaceExternalId: String,
    val name: String,
    val agentIds: List<UUID> = emptyList(),
    /**
     * Full member list with roles. Returned by [UserGroupRepository.findByIdWithDetails].
     * Empty by default on list queries ([UserGroupRepository.findByNamespaceId]) which
     * return [userCount] instead for performance — loading thousands of members per group
     * in a namespace listing is not acceptable.
     */
    val members: List<UserGroupMember> = emptyList(),
    /**
     * Member count for list queries. Set to [members].size when members are loaded.
     * When [members] is empty and [userCount] is 0 it means no members; when [members]
     * is empty and [userCount] > 0 it means this is a list result (members not loaded).
     */
    val userCount: Int = 0,
)
