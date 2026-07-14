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
     * The operation is guarded: a `[:STARRED]` edge is only created when the user
     * already holds a direct `[:ADMIN]` or `[:MEMBER]` relation on the entity,
     * preventing orphaned starred edges.
     *
     * @return true if the user has a direct permission relation on the entity
     *   (star was persisted or cleared), false if they have none (no-op).
     */
    fun setStarred(userId: String, entityType: EntityType, entityId: String, starred: Boolean): Boolean

    /**
     * Returns the caller's direct permission relation and starred flag for every
     * entity of [entityType] they have a direct `[:ADMIN]`/`[:MEMBER]` edge on,
     * keyed by entity id. Resolved in a single round-trip.
     */
    fun listStarred(userId: String, entityType: EntityType): Map<String, DirectRelation>
}
