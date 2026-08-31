package io.whozoss.agentos.permissions

/**
 * Repository for per-user favorite state — orthogonal to the `[:ADMIN]`/`[:MEMBER]`
 * permission edges — and the single-round-trip read that resolves the caller's direct
 * role + favorite flag per entity ([listDirectRelations]).
 *
 * State is stored on the `[:HAS_USER_CASE_STATE]` relationship-with-properties, which
 * also carries [DirectRelation.readAt]. The legacy `[:STARRED]` plain edge has been
 * replaced by this consolidated edge.
 *
 * Kept separate from [PermissionRepository]: it owns per-user preference state. The
 * role it surfaces is for list decoration only; it grants and checks no access.
 */
interface FavoriteRepository {
    /**
     * Sets or clears the `favoriteAt` property on the `[:HAS_USER_CASE_STATE]` edge
     * between the user and the entity.
     *
     * The operation is guarded by a prior `[:ADMIN|MEMBER]` MATCH: the edge is only
     * written when the user already holds a direct permission relation on the entity,
     * preventing orphaned state edges.
     *
     * @return true if the user has a direct `[:ADMIN]` or `[:MEMBER]` edge on the entity
     *   (the favorite flag was set or cleared as requested); false if they have no direct
     *   permission edge (the call was a no-op regardless of [favorite]).
     *   Note: for `favorite=false`, returns true even when `favoriteAt` was already null —
     *   the guard only checks for the permission edge, not the prior state of the flag.
     */
    fun setFavorite(
        userId: String,
        entityType: EntityType,
        entityId: String,
        favorite: Boolean,
    ): Boolean

    /**
     * Returns the caller's direct permission relation and favorite flag for every entity
     * of [entityType] they have a direct `[:ADMIN]`/`[:MEMBER]` edge on, keyed by
     * entity id. Includes both favorited and non-favorited entities. Resolved in a single
     * round-trip; used to enrich list responses with role and favorite metadata.
     */
    fun listDirectRelations(
        userId: String,
        entityType: EntityType,
    ): Map<String, DirectRelation>
}
