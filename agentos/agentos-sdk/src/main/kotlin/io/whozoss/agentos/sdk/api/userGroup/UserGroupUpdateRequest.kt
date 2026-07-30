package io.whozoss.agentos.sdk.api.userGroup

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.util.UUID

/**
 * Request body for `POST /api/user-groups/{userGroupId}`.
 *
 * Updates a UserGroup. [userExternalIdsToAdd] and [userExternalIdsToRemove] are delta
 * sets applied atomically. [adminExternalIds] is the **complete** desired set of members holding the
 * ADMIN role (replace semantics — members not listed are demoted to MEMBER). [agentIds] is the
 * **complete** desired set of deployed agents (replace semantics, not delta).
 *
 * The add/remove/admin sets are bounded to 200 entries per request; the server enforces this.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class UserGroupUpdateRequest(
    @field:NotBlank
    @field:Size(max = 250)
    val name: String,
    @field:Size(max = 200)
    val userExternalIdsToAdd: Set<@NotBlank String> = emptySet(),
    @field:Size(max = 200)
    val userExternalIdsToRemove: Set<@NotBlank String> = emptySet(),
    @field:Schema(
        description = "Complete desired set of member external IDs holding the ADMIN role (replace semantics): " +
            "members not listed are demoted to MEMBER, and an omitted or empty set demotes every current admin. " +
            "Every listed id must be a member after the update.",
    )
    @field:Size(max = 200)
    val adminExternalIds: Set<@NotBlank String> = emptySet(),
    @field:Size(max = 200)
    val agentIds: Set<UUID> = emptySet(),
)
