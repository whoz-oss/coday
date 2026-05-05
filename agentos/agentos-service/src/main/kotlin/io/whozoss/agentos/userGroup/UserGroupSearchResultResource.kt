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
    val userCount: Int = 0,
)
