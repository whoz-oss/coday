package io.whozoss.agentos.permissions

/**
 * Represents the type of permission relationship between a user and an entity.
 * Based on the RBAC model defined in the architecture.
 */
enum class PermissionRelation {
    /**
     * Admin relationship - grants full access (READ, WRITE, DELETE) to the entity
     * and all its child entities through transitive permissions.
     */
    ADMIN,

    /**
     * Member relationship - grants limited access (READ only) to the entity
     * and its child entities through transitive permissions.
     */
    MEMBER,
}
