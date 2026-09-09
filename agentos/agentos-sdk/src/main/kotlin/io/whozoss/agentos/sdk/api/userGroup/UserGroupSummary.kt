package io.whozoss.agentos.sdk.api.userGroup

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

/**
 * Compact summary of a UserGroup — returned inside
 * `POST /api/users/groups-by-external-ids` responses.
 */
@Schema(name = "UserGroupSummary")
@JsonIgnoreProperties(ignoreUnknown = true)
data class UserGroupSummary(
    val id: UUID,
    val name: String,
)
