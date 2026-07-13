package io.whozoss.agentos.sdk.api.case

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * Request body for `POST /api/cases/by-user/in-namespace`.
 *
 * Lists cases concerning a specific user scoped to a single namespace, both
 * identified by their external IDs (identity-provider keys / federation IDs).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ListByUserInNamespaceRequest(
    val userExternalId: String,
    val namespaceExternalId: String,
)
