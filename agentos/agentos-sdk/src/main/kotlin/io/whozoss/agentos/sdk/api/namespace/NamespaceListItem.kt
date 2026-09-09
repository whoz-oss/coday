package io.whozoss.agentos.sdk.api.namespace

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

/**
 * HTTP response item returned by `GET /api/namespaces`.
 *
 * Carries the caller's effective [role] on each namespace in addition to the
 * namespace's own properties.
 *
 * Possible [role] values:
 * - `"SUPER-ADMIN"` — the caller has `isAdmin = true`; visible for every namespace
 * - `"ADMIN"`       — the caller holds a direct `[:ADMIN]` relation on the namespace
 * - `"MEMBER"`      — the caller holds a direct `[:MEMBER]` relation but no ADMIN
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class NamespaceListItem(
    val id: UUID,
    val name: String,
    val description: String? = null,
    val configPath: String? = null,
    val externalId: String? = null,
    val defaultAgentName: String? = null,
    @field:Schema(
        description = "The caller's effective role on this namespace.",
        allowableValues = ["SUPER-ADMIN", "ADMIN", "MEMBER"],
    )
    val role: String,
)
