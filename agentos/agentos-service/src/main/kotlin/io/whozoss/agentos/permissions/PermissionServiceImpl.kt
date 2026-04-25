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
    "'\${agentos.persistence.mode:in-memory}' == 'neo4j' " +
        "or '\${agentos.persistence.mode:in-memory}' == 'embedded-neo4j'",
)
class PermissionServiceImpl(
    private val userService: UserService,
    private val permissionRepository: PermissionRepository,
    private val permissionCache: PermissionCache
) : PermissionService {

    companion object : KLogging()

    override fun hasPermission(
        userId: String,
        entityType: String,
        entityId: String,
        action: Action
    ): Boolean {
        return try {
            // 1. Super-admin bypass check
            val user = try {
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
     * Evaluates permission through direct and transitive checks.
     */
    private fun evaluatePermission(
        userId: String,
        entityType: String,
        entityId: String,
        action: Action
    ): Boolean {
        // Determine required relation based on action
        val requiredRelation = when (action) {
            Action.READ -> PermissionRelation.MEMBER  // MEMBER or ADMIN can READ
            Action.WRITE, Action.DELETE -> PermissionRelation.ADMIN  // Only ADMIN can WRITE/DELETE
        }

        // Check direct permission first
        if (permissionRepository.hasDirectPermission(userId, entityType, entityId, requiredRelation)) {
            logger.debug { "Direct permission granted: user=$userId, entity=$entityType:$entityId, action=$action" }
            return true
        }

        // Check transitive permission through namespace hierarchy
        if (permissionRepository.hasTransitivePermission(userId, entityType, entityId, requiredRelation)) {
            logger.debug { "Transitive permission granted: user=$userId, entity=$entityType:$entityId, action=$action" }
            return true
        }

        logger.debug { "Permission denied: user=$userId, entity=$entityType:$entityId, action=$action" }
        return false
    }

    override fun grantPermission(
        userId: String,
        entityType: String,
        entityId: String,
        relation: PermissionRelation
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
        entityType: String,
        entityId: String,
        relation: PermissionRelation
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
        entityType: String,
        entityId: String,
        relation: PermissionRelation?
    ): List<String> {
        return try {
            permissionRepository.listUsersWithPermission(entityType, entityId, relation)
        } catch (e: Exception) {
            logger.error(e) { "Failed to list users with permission on $entityType:$entityId, relation=$relation" }
            emptyList() // Fail-closed: return empty list on error
        }
    }

    override fun listEntitiesForUser(
        userId: String,
        entityType: String,
        action: Action
    ): List<String> {
        return try {
            // Super-admin sees all entities
            val user = try {
                userService.findById(UUID.fromString(userId))
            } catch (e: IllegalArgumentException) {
                logger.debug { "Invalid UUID format for userId: $userId" }
                null
            }
            if (user?.isAdmin == true) {
                logger.debug { "Super-admin listing all $entityType entities" }
                // Super-admin bypasses per-entity checks in hasPermission(), so controllers
                // (declarative @PreAuthorize / @PostFilter paths) never reach this code path
                // for super-admins. Return empty list as a safe fallback — callers that need
                // "all entities" should query the entity service directly.
                return emptyList()
            }

            // Determine required relation based on action
            val requiredRelation = when (action) {
                Action.READ -> PermissionRelation.MEMBER  // MEMBER or ADMIN can READ
                Action.WRITE, Action.DELETE -> PermissionRelation.ADMIN  // Only ADMIN can WRITE/DELETE
            }

            permissionRepository.listEntitiesForUser(userId, entityType, requiredRelation)
        } catch (e: Exception) {
            logger.error(e) { "Failed to list entities for user=$userId, type=$entityType, action=$action" }
            emptyList() // Fail-closed: return empty list on error
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
}