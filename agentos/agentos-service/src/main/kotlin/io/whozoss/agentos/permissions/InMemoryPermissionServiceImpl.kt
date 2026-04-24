package io.whozoss.agentos.permissions

import mu.KLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Service

/**
 * In-memory implementation of PermissionService for testing.
 *
 * This implementation provides a permissive permission model where all users
 * have full permissions on all entities. This is suitable for local development
 * and testing scenarios where security is not a concern.
 */
@Service
@Primary
@ConditionalOnProperty(name = ["agentos.persistence.mode"], havingValue = "in-memory", matchIfMissing = true)
class InMemoryPermissionServiceImpl : PermissionService {

    companion object : KLogging() {
        init {
            logger.info { "[Permissions] InMemoryPermissionServiceImpl active - all permissions granted" }
        }
    }

    override suspend fun hasPermission(
        userId: String,
        entityType: String,
        entityId: String,
        action: Action
    ): Boolean {
        logger.debug { "In-memory mode: granting $action permission to user $userId on $entityType:$entityId" }
        return true
    }

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
        action: Action
    ): List<String> = emptyList()

    override suspend fun clearUserCache(userId: String) {
        // No-op in in-memory mode
    }
}