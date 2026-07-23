package io.whozoss.agentos.permissions

/**
 * The caller's direct relation to an entity, together with whether they have
 * starred (favorited) it. Resolved in a single query for a whole entity type so
 * list endpoints can enrich each resource without an extra round-trip per row.
 */
data class DirectRelation(
    val relation: PermissionRelation,
    val starred: Boolean,
)
