package io.whozoss.agentos.namespace

import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

/**
 * HTTP resource returned by `GET /api/namespaces`.
 *
 * Represents a namespace from the caller's perspective, carrying the caller's
 * effective [role] on that namespace. Distinct from [NamespaceResource] (used for
 * POST/PUT and `GET /{id}`) because the list endpoint is the only place where a
 * computed per-caller role is meaningful.
 *
 * Possible [role] values:
 * - `"SUPER-ADMIN"` — the caller has `isAdmin = true`; visible for every namespace
 * - `"ADMIN"`       — the caller holds a direct `[:ADMIN]` relation on the namespace
 * - `"MEMBER"`      — the caller holds a direct `[:MEMBER]` relation but no ADMIN
 */
@Schema(name = "NamespaceListItem")
data class NamespaceListItem(
    val id: UUID,
    val name: String,
    val description: String? = null,
    @Schema(description = "Optional filesystem path to a directory containing base configuration for this namespace")
    val configPath: String? = null,
    @Schema(description = "Optional external identifier for this namespace, e.g. a federation id from an external system")
    val externalId: String? = null,
    @Schema(description = "Logical name of the default agent for this namespace. Null means no default is configured.")
    val defaultAgentName: String? = null,
    @Schema(description = "The caller's effective role on this namespace", allowableValues = ["SUPER-ADMIN", "ADMIN", "MEMBER"])
    val role: String,
)
