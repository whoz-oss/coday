package io.whozoss.agentos.sdk.api.common

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.util.UUID

/**
 * Request body for `POST /by-ids` endpoints across all entity types.
 *
 * Used by `POST /api/cases/by-ids`, `POST /api/agent-configs/by-ids`,
 * `POST /api/ai-providers/by-ids`, `POST /api/ai-models/by-ids`,
 * `POST /api/namespaces/by-ids`, `POST /api/users/by-ids`, and
 * `POST /api/integration-configs/by-ids`.
 *
 * Requests are capped at 1 000 IDs per call; the server enforces this limit.
 *
 * @property ids List of entity UUIDs to fetch.
 * @property withRemoved When `true`, soft-deleted entities are included in the result.
 *   Defaults to `false`.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class GetByIdsRequest(
    val ids: List<UUID>,
    val withRemoved: Boolean = false,
)
