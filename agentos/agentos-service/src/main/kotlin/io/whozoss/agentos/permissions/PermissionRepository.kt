package io.whozoss.agentos.permissions

/**
 * Repository interface for permission operations.
 * This is the abstraction layer that the service uses, allowing for different implementations
 * (Neo4j, in-memory, etc.).
 */
interface PermissionRepository {

    /**
     * Checks if a user has a direct permission relationship with an entity.
     * Does not check transitive permissions through namespace hierarchy.
     *
     * @param userId The ID of the user
     * @param entityType The type of entity (e.g., "Namespace", "Case")
     * @param entityId The ID of the entity
     * @param relation The permission relationship to check (ADMIN or MEMBER)
     * @return true if the direct relationship exists, false otherwise
     */
    fun hasDirectPermission(
        userId: String,
        entityType: EntityType,
        entityId: String,
        relation: PermissionRelation
    ): Boolean

    /**
     * Checks if a user has transitive permission to an entity through namespace hierarchy.
     *
     * @param userId The ID of the user
     * @param entityType The type of entity
     * @param entityId The ID of the entity
     * @param relation The permission relationship to check
     * @return true if transitive permission exists, false otherwise
     */
    fun hasTransitivePermission(
        userId: String,
        entityType: EntityType,
        entityId: String,
        relation: PermissionRelation
    ): Boolean

    /**
     * Grants a direct permission relationship between a user and an entity.
     *
     * @param userId The ID of the user
     * @param entityType The type of entity
     * @param entityId The ID of the entity
     * @param relation The permission relationship to grant
     */
    fun grantPermission(
        userId: String,
        entityType: EntityType,
        entityId: String,
        relation: PermissionRelation
    )

    /**
     * Revokes a direct permission relationship between a user and an entity.
     *
     * @param userId The ID of the user
     * @param entityType The type of entity
     * @param entityId The ID of the entity
     * @param relation The permission relationship to revoke
     */
    fun revokePermission(
        userId: String,
        entityType: EntityType,
        entityId: String,
        relation: PermissionRelation
    )

    /**
     * Lists all users with a specific permission relationship to an entity.
     *
     * @param entityType The type of entity
     * @param entityId The ID of the entity
     * @param relation Optional filter for specific relationship type
     * @return List of user IDs with the specified relationship
     */
    fun listUsersWithPermission(
        entityType: EntityType,
        entityId: String,
        relation: PermissionRelation? = null
    ): List<String>

    /**
     * Lists all entities of a specific type that a user has permissions for.
     * Includes both direct and transitive permissions.
     *
     * @param userId The ID of the user
     * @param entityType The type of entities to list
     * @param relation The permission relationship to check
     * @return List of entity IDs the user has the specified relationship with
     */
    fun listEntitiesForUser(
        userId: String,
        entityType: EntityType,
        relation: PermissionRelation
    ): List<String>

    /**
     * Filters a candidate list of entity ids, returning only those the user can access
     * (direct relation OR transitive via the parent namespace) with the given relation.
     *
     * Resolves in a single Cypher round-trip regardless of the input size — the cost
     * scales with the size of `ids` rather than with the namespace cardinality.
     * Designed for batch endpoints (`/by-ids`) that previously paid an N+1 cost via
     * `@PostFilter` per-item evaluation.
     *
     * Caller is expected to short-circuit on `ids.isEmpty()` before invoking the
     * repository — the Cypher query is not run for an empty input.
     *
     * @param userId The ID of the user
     * @param entityType The Neo4j label / type of entities to filter (e.g. `"AgentConfig"`)
     * @param ids The candidate ids to filter
     * @param relation The required relation (`MEMBER` for READ, `ADMIN` for WRITE/DELETE)
     * @return Subset of `ids` that the user can access with the given relation
     */
    fun filterVisibleIds(
        userId: String,
        entityType: EntityType,
        ids: Collection<String>,
        relation: PermissionRelation,
    ): Set<String>

    /**
     * Sets the per-user "starred" (favorite) flag on the user's direct relation to an entity.
     * No-op if the user has no direct ADMIN/MEMBER relation on the entity.
     */
    fun setStarred(userId: String, entityType: EntityType, entityId: String, starred: Boolean)

    /** Ids of entities of the given type that the user has starred (favorited). */
    fun listStarredEntityIds(userId: String, entityType: EntityType): Set<String>
}
