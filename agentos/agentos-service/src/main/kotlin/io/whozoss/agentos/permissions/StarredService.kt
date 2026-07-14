package io.whozoss.agentos.permissions

/**
 * Service for the per-user `[:STARRED]` (favorite) relationship.
 *
 * Deliberately separate from [PermissionService]: starring is a user preference,
 * not an access-control concern.
 */
interface StarredService {

    /**
     * Sets or clears the favorite flag for the entity on behalf of the user.
     *
     * Requires the user to hold a direct `[:ADMIN]` or `[:MEMBER]` relation on the
     * entity — starring is not allowed via transitive (namespace-level) access only.
     *
     * @return true if the user had a direct relation and the operation was persisted;
     *   false if they had none (no-op). Callers may reject the request on false.
     */
    fun setStarred(userId: String, entityType: EntityType, entityId: String, starred: Boolean): Boolean

    /**
     * Returns the caller's direct permission relation and starred flag for every
     * entity of [entityType] they have a direct edge on, keyed by entity id.
     * Resolved in a single round-trip; used to enrich list responses.
     */
    fun listStarred(userId: String, entityType: EntityType): Map<String, DirectRelation>
}
