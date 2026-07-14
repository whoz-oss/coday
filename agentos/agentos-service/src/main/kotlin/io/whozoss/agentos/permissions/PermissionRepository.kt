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
     *
     * @return true if a direct ADMIN/MEMBER relation was updated, false if the user has
     *   none (the star was not persisted). Lets callers reject the operation instead of
     *   reporting a success that did not happen.
     */
    fun setStarred(userId: String, entityType: EntityType, entityId: String, starred: Boolean): Boolean

    /**
     * The caller's direct relation (and starred flag) for every entity of [entityType]
     * they have a direct ADMIN/MEMBER edge on, keyed by entity id. Resolved in a single
     * round-trip so list endpoints can enrich each resource without a per-row query.
     */
    fun listDirectRelations(userId: String, entityType: EntityType): Map<String, DirectRelation>

    /**
     * Atomically promotes a [:MEMBER] relation to [:ADMIN], preserving all properties
     * (e.g. `starred`) from the old relation.
     *
     * Prefer this over `revokePermission(MEMBER)` + `grantPermission(ADMIN)` when a
     * direct [:MEMBER] relation already exists and must be upgraded — the two-step
     * approach silently drops relation properties.
     *
     * @return true if a [:MEMBER] edge was found and promoted; false if the user had
     *   no MEMBER relation on the entity (the [:ADMIN] edge is still created in that case).
     */
    fun promoteMemberToAdmin(userId: String, entityType: EntityType, entityId: String): Boolean

    /**
     * Atomically demotes a [:ADMIN] relation to [:MEMBER], preserving all properties
     * (e.g. `starred`) from the old relation.
     *
     * Prefer this over `revokePermission(ADMIN)` + `grantPermission(MEMBER)` when a
     * direct [:ADMIN] relation already exists and must be downgraded.
     *
     * @return true if a [:ADMIN] edge was found and demoted; false if the user had
     *   no ADMIN relation on the entity (the [:MEMBER] edge is still created in that case).
     */
    fun demoteAdminToMember(userId: String, entityType: EntityType, entityId: String): Boolean

    /**
     * Batch-apply share entries on an entity. Each entry is a (userId, targetRole) pair:
     * - targetRole = [PermissionRelation.ADMIN] → ensure user has ADMIN (promote from MEMBER
     *   preserving relation properties, or create directly)
     * - targetRole = [PermissionRelation.MEMBER] → ensure user has MEMBER (demote from ADMIN
     *   preserving relation properties, or create directly)
     * - targetRole = null → revoke all relations (ADMIN and MEMBER)
     *
     * Non-existent User nodes are silently skipped by the Cypher MATCH.
     * Returns the list of userIds for which at least one operation was applied.
     */
    fun applyShareBatch(
        entityType: EntityType,
        entityId: String,
        entries: List<Pair<String, PermissionRelation?>>,
    ): List<String>
}
