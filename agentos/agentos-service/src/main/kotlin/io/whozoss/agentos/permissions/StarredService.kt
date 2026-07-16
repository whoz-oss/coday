package io.whozoss.agentos.permissions

/**
 * Service for the per-user `[:STARRED]` (favorite) relationship, plus the read-model
 * that decorates case listings with the caller's own metadata.
 *
 * Kept separate from [PermissionService]: it owns the `[:STARRED]` edge (a user
 * preference) and a single-round-trip read of the caller's *direct* role + favorite
 * flag per entity ([listDirectRelations]). It neither grants nor checks access — the
 * role it returns is for display only; authorization stays with [PermissionService]
 * and `@PreAuthorize`.
 */
interface StarredService {

    /**
     * Sets or clears the favorite flag for the entity on behalf of the user.
     *
     * Requires the user to hold a direct `[:ADMIN]` or `[:MEMBER]` relation on the
     * entity — starring is not allowed via transitive (namespace-level) access only.
     *
     * @return true if the user has a direct `[:ADMIN]` or `[:MEMBER]` edge on the entity
     *   (the `[:STARRED]` edge was created or deleted as requested); false if they have
     *   no direct permission edge (no-op). Callers may reject the request on false.
     *   Note: for `starred=false`, returns true even when the entity was not previously
     *   starred — the guard only checks for the permission edge.
     */
    fun setStarred(userId: String, entityType: EntityType, entityId: String, starred: Boolean): Boolean

    /**
     * Returns the caller's direct permission relation and starred flag for every entity
     * of [entityType] they have a direct `[:ADMIN]`/`[:MEMBER]` edge on, keyed by
     * entity id. Includes both starred and non-starred entities. Resolved in a single
     * round-trip; used to enrich list responses with role and favorite metadata.
     */
    fun listDirectRelations(userId: String, entityType: EntityType): Map<String, DirectRelation>
}
