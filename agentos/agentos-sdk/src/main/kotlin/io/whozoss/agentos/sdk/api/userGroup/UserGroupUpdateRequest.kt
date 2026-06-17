package io.whozoss.agentos.sdk.api.userGroup

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.util.UUID

/**
 * Request body for `POST /api/user-groups/{userGroupId}`.
 *
 * Updates a UserGroup. [userExternalIdsToAdd] and [userExternalIdsToRemove] are delta
 * sets applied atomically. [agentIds] is the **complete** desired set of deployed agents
 * (replace semantics, not delta).
 *
 * Both add/remove sets are bounded to 200 entries per request; the server enforces this.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class UserGroupUpdateRequest(
    val name: String,
    val userExternalIdsToAdd: Set<String> = emptySet(),
    val userExternalIdsToRemove: Set<String> = emptySet(),
    val agentIds: Set<UUID> = emptySet(),
)
