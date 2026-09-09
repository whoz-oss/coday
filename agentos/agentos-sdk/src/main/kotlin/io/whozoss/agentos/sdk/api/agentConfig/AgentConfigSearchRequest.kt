package io.whozoss.agentos.sdk.api.agentConfig

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import jakarta.validation.constraints.NotBlank
import java.util.UUID

/**
 * Request body for `POST /api/agent-configs/search`.
 *
 * Returns the deduplicated list of [AgentConfigDto] available to the user identified
 * by [userExternalId] within the given namespace. Availability is the union of:
 * - Agents deployed on any UserGroup the user is a member of
 * - Agents deployed directly on any Namespace the user has MEMBER or ADMIN access to
 *
 * [userId] is an optional additional filter (reserved for future autocomplete use).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class AgentConfigSearchRequest(
    val namespaceId: UUID,
    @field:NotBlank(message = "userExternalId must not be blank")
    val userExternalId: String,
    val userId: String? = null,
)
