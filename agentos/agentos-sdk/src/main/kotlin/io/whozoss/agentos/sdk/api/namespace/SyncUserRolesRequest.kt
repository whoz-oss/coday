package io.whozoss.agentos.sdk.api.namespace

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * Request body for `POST /api/namespaces/update-roles-by-external-id`.
 *
 * Describes the **complete** desired role set for [userExternalId] across namespaces.
 * Any namespace relation the user currently holds that is not listed in [namespaceRoles]
 * will be revoked.
 *
 * This endpoint is restricted to SUPER_ADMIN callers.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SyncUserRolesRequest(
    val userExternalId: String,
    val namespaceRoles: List<NamespaceRoleEntry> = emptyList(),
)
