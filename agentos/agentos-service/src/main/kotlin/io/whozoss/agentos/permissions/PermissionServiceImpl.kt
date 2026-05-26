package io.whozoss.agentos.permissions

import io.whozoss.agentos.user.UserService
import mu.KLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Core implementation of the PermissionService with fail-closed security model.
 *
 * Permission evaluation order:
 * 1. Super-admin check (user.isAdmin flag) - bypasses all other checks
 * 2. Cache lookup - for performance
 * 3. Direct permission check - explicit relationships
 * 4. Transitive permission check - through namespace hierarchy
 *
 * All exceptions result in permission denied (fail-closed).
 */
@Service
@ConditionalOnExpression(
    "'\${agentos.persistence.mode:embedded-neo4j}' == 'neo4j' " +
        "or '\${agentos.persistence.mode:embedded-neo4j}' == 'embedded-neo4j'",
)
class PermissionServiceImpl(
    private val userService: UserService,
    private val permissionRepository: PermissionRepository,
    private val permissionCache: PermissionCache,
) : PermissionService {
    override fun hasPermission(
        userId: String,
        entityType: EntityType,
        entityId: String,
        action: Action,
    ): Boolean {
        return try {
            // 1. Super-admin bypass check
            val user =
                try {
                    userService.findById(UUID.fromString(userId))
                } catch (e: IllegalArgumentException) {
                    logger.debug { "Invalid UUID format for userId: $userId" }
                    null
                }
            if (user?.isAdmin == true) {
                logger.debug { "Super-admin bypass: user=$userId has all permissions" }
                return true
            }

            // 2. Cache lookup
            val cacheKey = PermissionCache.generateKey(userId, entityType, entityId, action)
            permissionCache.get(cacheKey)?.let { cached ->
                logger.debug { "Cache hit for permission check: $cacheKey -> $cached" }
                return cached
            }

            // 3. Evaluate permissions
            val hasPermission = evaluatePermission(userId, entityType, entityId, action)

            // 4. Cache the result
            permissionCache.put(cacheKey, hasPermission)

            hasPermission
        } catch (e: Exception) {
            logger.error(e) { "Error checking permission for user=$userId, entity=$entityType:$entityId, action=$action" }
            false // Fail-closed: any error denies permission
        }
    }

    /**
     * Evaluates permission through direct and (where applicable) transitive checks.
     *
     * Owner-private entity types (currently [EntityType.CASE], FR15 / WZ-32167) are
     * evaluated via **direct permission only**. A user can access a Case only when they
     * hold an explicit ADMIN or MEMBER relation on that Case node — typically auto-granted
     * at creation time. Namespace-level roles (ADMIN or MEMBER) do NOT confer transitive
     * access to Cases owned by other users.
     *
     * All other entity types follow the standard two-step check:
     * 1. Direct permission on the entity
     * 2. Transitive permission through the parent namespace hierarchy
     */
    private fun evaluatePermission(
        userId: String,
        entityType: EntityType,
        entityId: String,
        action: Action,
    ): Boolean {
        val requiredRelation =
            when (action) {
                Action.READ -> PermissionRelation.MEMBER

                // MEMBER or ADMIN can READ
                Action.WRITE, Action.DELETE -> PermissionRelation.ADMIN // Only ADMIN can WRITE/DELETE
            }

        val granted =
            if (entityType in OWNER_PRIVATE_ENTITY_TYPES) {
                // Owner-private entities (Case, FR15 / WZ-32167): only a direct relation on the
                // entity node itself counts. Namespace-level roles do NOT confer transitive access.
                permissionRepository.hasDirectPermission(userId, entityType, entityId, requiredRelation)
            } else {
                // Standard entities: direct permission first, then transitive via namespace hierarchy.

                permissionRepository.hasDirectPermission(
                    userId,
                    entityType,
                    entityId,
                    requiredRelation,
                ) || permissionRepository.hasTransitivePermission(userId, entityType, entityId, requiredRelation)
            }

        logger.debug {
            if (granted) {
                "Permission granted: user=$userId, entity=$entityType:$entityId, action=$action"
            } else {
                "Permission denied: user=$userId, entity=$entityType:$entityId, action=$action"
            }
        }
        return granted
    }

    override fun grantPermission(
        userId: String,
        entityType: EntityType,
        entityId: String,
        relation: PermissionRelation,
    ) {
        try {
            permissionRepository.grantPermission(userId, entityType, entityId, relation)
            // Invalidate entire cache: transitive permissions can affect any user in the namespace
            permissionCache.clear()
            logger.info { "Granted $relation permission to user=$userId on $entityType:$entityId" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to grant permission: user=$userId, entity=$entityType:$entityId, relation=$relation" }
            throw e
        }
    }

    override fun revokePermission(
        userId: String,
        entityType: EntityType,
        entityId: String,
        relation: PermissionRelation,
    ) {
        try {
            permissionRepository.revokePermission(userId, entityType, entityId, relation)
            // Invalidate entire cache: transitive permissions can affect any user in the namespace
            permissionCache.clear()
            logger.info { "Revoked $relation permission from user=$userId on $entityType:$entityId" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to revoke permission: user=$userId, entity=$entityType:$entityId, relation=$relation" }
            throw e
        }
    }

    override fun listUsersWithPermission(
        entityType: EntityType,
        entityId: String,
        relation: PermissionRelation?,
    ): List<String> =
        try {
            permissionRepository.listUsersWithPermission(entityType, entityId, relation)
        } catch (e: Exception) {
            logger.error(e) { "Failed to list users with permission on $entityType:$entityId, relation=$relation" }
            emptyList() // Fail-closed: return empty list on error
        }

    override fun listEntitiesForUser(
        userId: String,
        entityType: EntityType,
        action: Action,
    ): List<String> =
        try {
            val requiredRelation =
                when (action) {
                    Action.READ -> PermissionRelation.MEMBER

                    // MEMBER or ADMIN can READ
                    Action.WRITE, Action.DELETE -> PermissionRelation.ADMIN // Only ADMIN can WRITE/DELETE
                }
            permissionRepository.listEntitiesForUser(userId, entityType, requiredRelation)
        } catch (e: Exception) {
            logger.error(e) { "Failed to list entities for user=$userId, type=$entityType, action=$action" }
            emptyList() // Fail-closed: return empty list on error
        }

    override fun filterVisibleIds(
        userId: String,
        entityType: EntityType,
        ids: Collection<String>,
        action: Action,
    ): Set<String> {
        if (ids.isEmpty()) return emptySet()
        return try {
            val requiredRelation =
                when (action) {
                    Action.READ -> PermissionRelation.MEMBER

                    // MEMBER or ADMIN can READ
                    Action.WRITE, Action.DELETE -> PermissionRelation.ADMIN // Only ADMIN can WRITE/DELETE
                }
            permissionRepository.filterVisibleIds(userId, entityType, ids, requiredRelation)
        } catch (e: Exception) {
            logger.error(e) { "Failed to filter visible ids for user=$userId, type=$entityType, action=$action" }
            emptySet() // Fail-closed: any error denies access
        }
    }

    override fun clearUserCache(userId: String) {
        try {
            permissionCache.invalidateUser(userId)
            logger.info { "Cleared permission cache for user: $userId" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to clear cache for user: $userId" }
            // Don't throw - cache clearing failure shouldn't break the flow
        }
    }

    companion object : KLogging() {
        /**
         * Entity types where access is granted only via a direct relation on the entity
         * itself. Namespace-level roles do NOT confer transitive access (FR15, WZ-32167).
         */
        private val OWNER_PRIVATE_ENTITY_TYPES: Set<EntityType> = setOf(EntityType.CASE)
    }
}
