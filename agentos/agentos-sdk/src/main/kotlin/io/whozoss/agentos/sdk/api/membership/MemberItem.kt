package io.whozoss.agentos.sdk.api.membership

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

/**
 * A user holding a direct ADMIN or MEMBER relation on any shareable entity
 * (Namespace, Case, UserGroup, …).
 *
 * Returned by [MembershipApi.getMembers] and [MembershipApi.updateMembers].
 * Users with both ADMIN and MEMBER relations appear once with `role = "ADMIN"`.
 *
 * Possible [role] values: `"ADMIN"`, `"MEMBER"`.
 */
@Schema(name = "MemberItem")
@JsonIgnoreProperties(ignoreUnknown = true)
data class MemberItem(
    val id: UUID,
    val externalId: String,
    val email: String,
    val firstname: String? = null,
    val lastname: String? = null,
    @field:Schema(
        description = "The user's direct role on this entity.",
        allowableValues = ["ADMIN", "MEMBER"],
    )
    val role: String,
)
