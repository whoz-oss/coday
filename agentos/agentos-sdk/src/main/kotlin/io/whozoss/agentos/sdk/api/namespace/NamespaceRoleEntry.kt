package io.whozoss.agentos.sdk.api.namespace

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank

/**
 * One (namespace, role) pair within a [SyncUserRolesRequest].
 *
 * [namespaceExternalId] must identify a known namespace; unknown IDs are silently
 * skipped by the service. [role] must be either `"ADMIN"` or `"MEMBER"`.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class NamespaceRoleEntry(
    @field:NotBlank
    val namespaceExternalId: String,
    @field:Schema(
        description = "Role to assign. Must be ADMIN or MEMBER.",
        allowableValues = ["ADMIN", "MEMBER"],
    )
    @field:NotBlank
    val role: String,
)
