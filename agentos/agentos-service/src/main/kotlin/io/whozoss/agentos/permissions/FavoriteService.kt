package io.whozoss.agentos.permissions

/**
 * Service for the per-user favorite flag and read state, plus the read-model
 * that decorates case listings with the caller's own metadata.
 *
 * Kept separate from [PermissionService]: it owns the `[:WATCHES]` edge properties
 * (`favorite`, `readAt`) and a single-round-trip read of the caller's *direct*
 * role + favorite flag per entity ([listDirectRelations]). It neither grants nor
 * checks access — the role it returns is for display only; authorization stays with
 * [PermissionService] and `@PreAuthorize`.
 */
interface FavoriteService {

    /**
     * Sets or clears the favorite flag for the entity on behalf of the user.
     *
     * Requires the user to hold a direct `[:ADMIN]` or `[:MEMBER]` relation on the
     * entity — favoriting is not allowed via transitive (namespace-level) access only.
     *
     * @return true if the user has a direct `[:ADMIN]` or `[:MEMBER]` edge on the entity
     *   (the favorite flag was set or cleared as requested); false if they have no direct
     *   permission edge (no-op). Callers may reject the request on false.
     *   Note: for `favorite=false`, returns true even when the entity was not previously
     *   favorited — the guard only checks for the permission edge.
     */
    fun setFavorite(userId: String, entityType: EntityType, entityId: String, favorite: Boolean): Boolean

    /**
     * Returns the caller's direct permission relation and favorite flag for every entity
     * of [entityType] they have a direct `[:ADMIN]`/`[:MEMBER]` edge on, keyed by
     * entity id. Includes both favorited and non-favorited entities. Resolved in a single
     * round-trip; used to enrich list responses with role and favorite metadata.
     */
    fun listDirectRelations(userId: String, entityType: EntityType): Map<String, DirectRelation>
}
