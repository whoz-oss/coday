package io.whozoss.agentos.permissions

import java.time.Instant

/**
 * The caller's direct relation to an entity, together with their per-user state
 * (favorite flag and read timestamp). Resolved in a single query for a whole entity
 * type so list endpoints can enrich each resource without an extra round-trip per row.
 *
 * Both [favorite] and [readAt] come from the `[:WATCHES]` relationship properties.
 *
 * @property relation The caller's direct permission relation (ADMIN or MEMBER).
 * @property favorite True when the user has favorited the entity.
 *   Maps directly to [io.whozoss.agentos.sdk.api.case.CaseDto.favorite].
 * @property readAt Timestamp of the user's last read; null means never read (unread).
 */
data class DirectRelation(
    val relation: PermissionRelation,
    val favorite: Boolean = false,
    val readAt: Instant? = null,
)
