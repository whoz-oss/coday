package io.whozoss.agentos.entity

import java.util.UUID

/**
 * Request body for `POST /by-ids` (service-internal form).
 *
 * The SDK counterpart [io.whozoss.agentos.sdk.api.common.GetByIdsRequest] is what
 * controllers receive from the HTTP layer. Controllers convert it to this type before
 * passing it to [EntityCrudDelegate.getByIds].
 *
 * @param ids List of entity UUIDs to fetch.
 * @param withRemoved When true, soft-deleted entities are included in the result.
 */
data class GetByIdsRequest(
    val ids: List<UUID>,
    val withRemoved: Boolean = false,
)
