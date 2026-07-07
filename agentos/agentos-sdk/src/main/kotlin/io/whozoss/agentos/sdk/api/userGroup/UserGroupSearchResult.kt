package io.whozoss.agentos.sdk.api.userGroup

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

/**
 * HTTP response item returned by `GET /api/user-groups` and `GET /api/user-groups/{id}`.
 *
 * Represents a UserGroup with its membership count and the list of agent IDs that have
 * been deployed to it.
 */
@Schema(name = "UserGroupSearchResult")
@JsonIgnoreProperties(ignoreUnknown = true)
data class UserGroupSearchResult(
    val userGroupId: UUID,
    val namespaceId: UUID,
    val namespaceExternalId: String,
    val name: String,
    val agentIds: List<UUID> = emptyList(),
    val userCount: Int = 0,
)
