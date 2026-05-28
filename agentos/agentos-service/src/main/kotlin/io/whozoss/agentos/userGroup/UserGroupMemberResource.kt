package io.whozoss.agentos.userGroup

import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

@Schema(name = "UserGroupMember")
data class UserGroupMemberResource(
    val userId: UUID,
    val externalId: String,
    val email: String,
    @Schema(description = "Role on the group", allowableValues = ["ADMIN", "MEMBER"])
    val role: String,
)
