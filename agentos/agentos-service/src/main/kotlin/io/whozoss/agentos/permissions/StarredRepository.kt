package io.whozoss.agentos.permissions

/**
 * Repository for the per-user `[:STARRED]` relationship — orthogonal to the
 * `[:ADMIN]`/`[:MEMBER]` permission edges.
 *
 * Deliberately separate from [PermissionRepository]: starring is a user preference,
 * not an access-control concern.
 */
interface StarredRepository {

    /**
     * Creates or removes the `[:STARRED]` edge between the user and the entity.
     *
     * The operation is guarded by a prior `[:ADMIN|MEMBER]` MATCH: the `[:STARRED]`
     * edge is only created (or removed) when the user already holds a direct permission
     * relation on the entity, preventing orphaned `[:STARRED]` edges.
     *
     * @return true if the user has a direct `[:ADMIN]` or `[:MEMBER]` edge on the entity
     *   (the `[:STARRED]` edge was created or deleted as requested); false if they have
     *   no direct permission edge (the call was a no-op regardless of [starred]).
     *   Note: for `starred=false`, returns true even when no `[:STARRED]` edge existed —
     *   the guard only checks for the permission edge, not the prior existence of the star.
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
