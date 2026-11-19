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
 * Admin delta mode, for a caller that has not loaded the whole roster: send [adminExternalIdsToAdd]
 * and/or [adminExternalIdsToRemove] instead of [adminExternalIds]. Delta mode applies as soon as either
 * is sent (even as an empty set): the "add" members are promoted to ADMIN, the "remove" members are
 * demoted to MEMBER (a non-admin is left as is), and no other member's role changes. Not sending either
 * keeps the replace semantics above. The request is rejected with 422 when a non-empty [adminExternalIds]
 * is sent with a delta field, when a member is in both delta sets, when an "add" member is also in
 * [userExternalIdsToRemove], or when an "add" member is not on the roster after the update. Promoting a
 * member added by the same request is allowed.
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
            "Every listed id must be a member after the update. Must stay empty when a delta field " +
            "(adminExternalIdsToAdd / adminExternalIdsToRemove) is sent.",
    )
    @field:Size(max = 200)
    val adminExternalIds: Set<@NotBlank String> = emptySet(),
    @field:Size(max = 200)
    val agentIds: Set<UUID> = emptySet(),
    @field:Schema(
        description = "Delta mode: member external IDs to promote to ADMIN. Null (not sent) differs from empty: " +
            "sending either delta field, even empty, selects delta mode. Must not be combined with a non-empty " +
            "adminExternalIds, and every listed id must be a member after the update.",
        nullable = true,
    )
    @field:Size(max = 200)
    val adminExternalIdsToAdd: Set<@NotBlank String>? = null,
    @field:Schema(
        description = "Delta mode: member external IDs to demote to MEMBER; a non-admin is left unchanged. " +
            "Null (not sent) differs from empty: sending either delta field, even empty, selects delta mode. " +
            "Must not be combined with a non-empty adminExternalIds, nor list an id also in adminExternalIdsToAdd.",
        nullable = true,
    )
    @field:Size(max = 200)
    val adminExternalIdsToRemove: Set<@NotBlank String>? = null,
)
