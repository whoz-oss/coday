package io.whozoss.agentos.sdk.api.user

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.util.UUID

/**
 * Request body for `POST /api/users/groups-by-external-ids`.
 *
 * Returns a map from each provided external ID to the list of [UserGroupSummary]
 * objects the corresponding user belongs to, scoped to [namespaceId].
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class GroupsByExternalIdsRequest(
    val externalIds: List<String>,
    val namespaceId: UUID,
)
