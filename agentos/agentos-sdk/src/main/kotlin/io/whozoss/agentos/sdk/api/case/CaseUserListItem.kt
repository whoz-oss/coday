package io.whozoss.agentos.sdk.api.case

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

/**
 * HTTP response item returned by `GET /api/cases/{caseId}/users`.
 *
 * Represents a user holding a direct `[:ADMIN]` or `[:MEMBER]` relation on the case.
 * Follows the same pattern as [io.whozoss.agentos.sdk.api.namespace.NamespaceUserListItem].
 */
@Schema(name = "CaseUserListItem")
@JsonIgnoreProperties(ignoreUnknown = true)
data class CaseUserListItem(
    val id: UUID,
    val externalId: String,
    val email: String,
    val firstname: String? = null,
    val lastname: String? = null,
    @field:Schema(
        description = "The user's direct role on this case.",
        allowableValues = ["ADMIN", "MEMBER"],
    )
    val role: String,
)
