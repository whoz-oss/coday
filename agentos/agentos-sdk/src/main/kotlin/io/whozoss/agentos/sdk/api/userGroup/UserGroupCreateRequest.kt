package io.whozoss.agentos.sdk.api.userGroup

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.util.UUID

/**
 * Request body for `POST /api/user-groups`.
 *
 * Creates a new UserGroup in the given namespace. [userExternalIdsToAdd] is the initial
 * set of user external IDs (IdP keys) to add as members. [agentIds] is the initial set
 * of agent configs to deploy to this group.
 *
 * Both lists are bounded to 200 entries per request; the server enforces this limit.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class UserGroupCreateRequest(
    val namespaceId: UUID,
    val name: String,
    val userExternalIdsToAdd: Set<String> = emptySet(),
    val agentIds: Set<UUID> = emptySet(),
)
