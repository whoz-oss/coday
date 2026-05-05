package io.whozoss.agentos.userGroup

import java.util.*

data class UserGroupSearchResult(
    val userGroupId: UUID,
    val namespaceId: UUID,
    val namespaceExternalId: String,
    val name: String,
    val agentIds: List<UUID> = emptyList(),
    val userCount: Int = 0,
)
