package io.whozoss.agentos.permissions

import mu.KLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Repository

/**
 * In-memory implementation of PermissionRepository for testing.
 *
 * This implementation provides a simple, permissive permission model suitable for
 * local development and testing. In this mode:
 * - All users have full permissions on all entities (super-admin behavior)
 * - No actual permission data is stored or checked
 * - All permission checks return true
 *
 * This allows the application to run without Neo4j while maintaining the same
 * API contracts as the production permission system.
 */
@Repository
@ConditionalOnProperty(name = ["agentos.persistence.mode"], havingValue = "in-memory", matchIfMissing = true)
class InMemoryPermissionRepository : PermissionRepository {

    companion object : KLogging() {
        init {
            logger.info { "[Permissions] InMemoryPermissionRepository active - all permissions granted" }
        }
    }

    override suspend fun hasDirectPermission(
        userId: String,
        entityType: String,
        entityId: String,
        relation: PermissionRelation
    ): Boolean {
        logger.debug { "In-memory mode: granting $relation permission to user $userId on $entityType:$entityId" }
        return true
    }

    override suspend fun hasTransitivePermission(
        userId: String,
        entityType: String,
        entityId: String,
        relation: PermissionRelation
    ): Boolean = true

    override suspend fun grantPermission(
        userId: String,
        entityType: String,
        entityId: String,
        relation: PermissionRelation
    ) {
        logger.debug { "In-memory mode: grant permission called (no-op)" }
    }

    override suspend fun revokePermission(
        userId: String,
        entityType: String,
        entityId: String,
        relation: PermissionRelation
    ) {
        logger.debug { "In-memory mode: revoke permission called (no-op)" }
    }

    override suspend fun listUsersWithPermission(
        entityType: String,
        entityId: String,
        relation: PermissionRelation?
    ): List<String> = emptyList()

    override suspend fun listEntitiesForUser(
        userId: String,
        entityType: String,
        relation: PermissionRelation
    ): List<String> = emptyList()
}