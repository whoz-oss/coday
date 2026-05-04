package io.whozoss.agentos.permissions

/**
 * Core permission service interface for checking access rights in AgentOS.
 * Implements a fail-closed security model where access is denied by default.
 *
 * The permission model supports:
 * - Super-admin users (isAdmin flag) who bypass all permission checks
 * - Direct permissions on entities
 * - Transitive permissions through namespace hierarchy
 * - Caching of permission results for performance
 */
interface PermissionService {
    /**
     * Checks if a user has permission to perform an action on an entity.
     *
     * Permission evaluation order:
     * 1. Super-admin check (user.isAdmin flag)
     * 2. Cache lookup
     * 3. Direct permission check
     * 4. Transitive permission check (namespace → child entities)
     *
     * @param userId The ID of the user to check permissions for
     * @param entityType The type of entity (e.g., "Namespace", "Case", "AgentConfig")
     * @param entityId The ID of the specific entity
     * @param action The action to perform (READ, WRITE, DELETE)
     * @return true if permission is granted, false otherwise (fail-closed)
     */
    fun hasPermission(
        userId: String,
        entityType: EntityType,
        entityId: String,
        action: Action
    ): Boolean

    /**
     * Grants a permission relationship between a user and an entity.
     *
     * @param userId The ID of the user to grant permission to
     * @param entityType The type of entity
     * @param entityId The ID of the entity
     * @param relation The type of relationship (ADMIN or MEMBER)
     */
    fun grantPermission(
        userId: String,
        entityType: EntityType,
        entityId: String,
        relation: PermissionRelation
    )

    /**
     * Revokes a permission relationship between a user and an entity.
     *
     * @param userId The ID of the user to revoke permission from
     * @param entityType The type of entity
     * @param entityId The ID of the entity
     * @param relation The type of relationship to revoke
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
     * @param relation The type of relationship to filter by (optional)
     * @return List of user IDs with the specified relationship
     */
    fun listUsersWithPermission(
        entityType: EntityType,
        entityId: String,
        relation: PermissionRelation? = null
    ): List<String>

    /**
     * Lists all entities of a specific type that a user has permissions for.
     *
     * @param userId The ID of the user
     * @param entityType The type of entities to list
     * @param action The action to check permissions for
     * @return List of entity IDs the user has permission to perform the action on
     */
    fun listEntitiesForUser(
        userId: String,
        entityType: EntityType,
        action: Action
    ): List<String>

    /**
     * Filters a candidate list of entity ids, returning only those the user can access
     * with the given action. Resolves in a single Cypher round-trip (constant cost in
     * the number of ids).
     *
     * Designed for batch endpoints (`POST /by-ids`) that previously paid an N+1 cost
     * via `@PostFilter` per-item evaluation. Replaces the pattern :
     *   `@PostFilter("hasPermission(filterObject.id, 'X', 'READ')")`
     * with an explicit body :
     *   `permissionService.filterVisibleIds(userId, "X", ids, READ)`
     *
     * Caller responsibility :
     * - the **bypass admin** (return all ids if `currentUser.isAdmin`) is handled in
     *   the controller body, NOT here. This method does not consult `User.isAdmin`.
     * - empty `ids` short-circuits to `emptySet()` without touching the repository.
     *
     * The result is **not cached** : a batch Cypher query is already cheap (single
     * round-trip), and caching a `Set<String>` per (user × type × action × hash(ids))
     * would explode memory. See AC4 of story 5-3 for the rationale.
     *
     * @param userId The ID of the user
     * @param entityType The entity type to filter (e.g. `"AgentConfig"`)
     * @param ids The candidate ids
     * @param action The required action (`READ` → MEMBER+ADMIN, `WRITE`/`DELETE` → ADMIN)
     * @return Subset of `ids` the user can access. Fail-closed (empty set) on error.
     */
    fun filterVisibleIds(
        userId: String,
        entityType: EntityType,
        ids: Collection<String>,
        action: Action,
    ): Set<String>

    /**
     * Clears the permission cache for a specific user.
     * Should be called when user permissions change.
     *
     * @param userId The ID of the user to clear cache for
     */
    fun clearUserCache(userId: String)
}