package io.whozoss.agentos.usergroup

import java.util.UUID

data class UserGroupResponse(
    val userGroupId: UUID,
    val namespaceId: UUID,
    val name: String,
    val agentIds: List<UUID>,
    val userCount: Int,
)
