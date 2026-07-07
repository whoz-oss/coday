package io.whozoss.agentos.sdk.api.userGroup

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
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
    @field:NotBlank
    @field:Size(max = 250)
    val name: String,
    @field:Size(max = 200)
    val userExternalIdsToAdd: Set<String> = emptySet(),
    @field:Size(max = 200)
    val agentIds: Set<UUID> = emptySet(),
)
