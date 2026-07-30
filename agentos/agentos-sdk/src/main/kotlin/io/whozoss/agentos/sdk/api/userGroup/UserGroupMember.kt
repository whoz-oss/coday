package io.whozoss.agentos.sdk.api.userGroup

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

/**
 * HTTP response item returned by `GET /api/user-groups/{userGroupId}/members`.
 *
 * A user linked to the group by a `[:MEMBER]` or `[:ADMIN]` relationship. [externalId] is the key the
 * create/update requests use to add/remove members and to designate admins; [role] is the user's
 * relation to the group (`ADMIN` = can manage the group, `MEMBER` = read). The rest are display fields.
 */
@Schema(name = "UserGroupMember")
@JsonIgnoreProperties(ignoreUnknown = true)
data class UserGroupMember(
    val userId: UUID,
    @Schema(description = "Identity-provider key used to add or remove this member")
    val externalId: String,
    @Schema(description = "The user's relation to the group", allowableValues = ["ADMIN", "MEMBER"])
    val role: String,
    val email: String? = null,
    val firstname: String? = null,
    val lastname: String? = null,
)
