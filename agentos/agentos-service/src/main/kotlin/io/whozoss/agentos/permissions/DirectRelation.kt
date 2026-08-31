package io.whozoss.agentos.permissions

import java.time.Instant

/**
 * The caller's direct relation to an entity, together with their per-user state
 * (starred and read timestamps). Resolved in a single query for a whole entity type
 * so list endpoints can enrich each resource without an extra round-trip per row.
 *
 * Both [favoriteAt] and [readAt] come from the `[:HAS_USER_CASE_STATE]` relationship
 * properties.
 *
 * @property relation The caller's direct permission relation (ADMIN or MEMBER).
 * @property favoriteAt Non-null when the user has favorited the entity; null otherwise.
 *   Named to match [io.whozoss.agentos.sdk.api.case.CaseDto.favorite].
 * @property readAt Timestamp of the user's last read; null means never read (unread).
 */
data class DirectRelation(
    val relation: PermissionRelation,
    val favoriteAt: Instant? = null,
    val readAt: Instant? = null,
) {
    /** Convenience accessor: true when [favoriteAt] is non-null. */
    val isFavorite: Boolean get() = favoriteAt != null
}
