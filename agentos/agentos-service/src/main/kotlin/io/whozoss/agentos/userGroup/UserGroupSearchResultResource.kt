package io.whozoss.agentos.userGroup

import io.swagger.v3.oas.annotations.media.Schema
import java.util.*

@Schema(name = "UserGroupSearchResult")
data class UserGroupSearchResultResource(
    val userGroupId: UUID,
    val namespaceId: UUID,
    val namespaceExternalId: String,
    val name: String,
    val agentIds: List<UUID> = emptyList(),
    /**
     * Full member list with roles. Populated on single-group GET; empty on list queries.
     * Use [userCount] for display in list contexts.
     */
    val members: List<UserGroupMemberResource> = emptyList(),
    /** Member count. On single-group GET this equals members.size. */
    val userCount: Int = 0,
)
