package io.whozoss.agentos.sdk.api.namespace

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * One (namespace, role) pair within a [SyncUserRolesRequest].
 *
 * [namespaceExternalId] must identify a known namespace; unknown IDs are silently
 * skipped by the service. [role] must be either `"ADMIN"` or `"MEMBER"`.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class NamespaceRoleEntry(
    val namespaceExternalId: String,
    val role: String,
)
