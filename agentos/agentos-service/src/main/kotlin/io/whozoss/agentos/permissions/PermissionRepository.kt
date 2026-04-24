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
        entityType: String,
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
        entityType: String,
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
        entityType: String,
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
        entityType: String,
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
        entityType: String,
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
        entityType: String,
        relation: PermissionRelation
    ): List<String>
}