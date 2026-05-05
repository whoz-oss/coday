package io.whozoss.agentos.userGroup

import java.util.UUID

data class UserGroupSearchResult(
    val userGroupId: UUID,
    val namespaceId: UUID,
    val namespaceExternalId: String,
    val name: String,
    val agentIds: List<String> = emptyList(),
    val userCount: Int = 0,
)
