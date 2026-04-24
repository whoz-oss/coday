package io.whozoss.agentos.permissions

import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Cache
import mu.KLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Cache for permission check results using Caffeine.
 *
 * Configuration:
 * - TTL: 5 minutes
 * - Maximum size: 10,000 entries
 * - Key format: "perm:${userId}:${entityType}:${entityId}:${action}"
 *
 * The cache helps reduce database queries for frequently checked permissions
 * while ensuring changes are reflected within the TTL window.
 */
@Component
@ConditionalOnExpression(
    "'\${agentos.persistence.mode:in-memory}' == 'neo4j' " +
        "or '\${agentos.persistence.mode:in-memory}' == 'embedded-neo4j'",
)
class PermissionCache {

    companion object : KLogging() {
        private const val MAX_SIZE = 10_000L
        private val TTL = Duration.ofMinutes(5)

        /**
         * Generates a cache key for permission checks.
         * Format: "perm:${userId}:${entityType}:${entityId}:${action}"
         */
        fun generateKey(
            userId: String,
            entityType: String,
            entityId: String,
            action: Action
        ): String = "perm:$userId:$entityType:$entityId:$action"
    }

    private val cache: Cache<String, Boolean> = Caffeine.newBuilder()
        .maximumSize(MAX_SIZE)
        .expireAfterWrite(TTL)
        .recordStats()
        .build()

    /**
     * Gets a cached permission result.
     *
     * @param key The cache key
     * @return The cached result, or null if not found or expired
     */
    fun get(key: String): Boolean? {
        return cache.getIfPresent(key).also { result ->
            if (result != null) {
                logger.debug { "Cache hit for key: $key -> $result" }
            } else {
                logger.debug { "Cache miss for key: $key" }
            }
        }
    }

    /**
     * Gets a cached permission result using individual parameters.
     */
    fun get(
        userId: String,
        entityType: String,
        entityId: String,
        action: Action
    ): Boolean? {
        val key = generateKey(userId, entityType, entityId, action)
        return get(key)
    }

    /**
     * Stores a permission result in the cache.
     *
     * @param key The cache key
     * @param value The permission result to cache
     */
    fun put(key: String, value: Boolean) {
        cache.put(key, value)
        logger.debug { "Cached permission: $key -> $value" }
    }

    /**
     * Stores a permission result using individual parameters.
     */
    fun put(
        userId: String,
        entityType: String,
        entityId: String,
        action: Action,
        value: Boolean
    ) {
        val key = generateKey(userId, entityType, entityId, action)
        put(key, value)
    }

    /**
     * Invalidates all cached permissions for a specific user.
     *
     * @param userId The ID of the user to clear cache for
     */
    fun invalidateUser(userId: String) {
        val keysToInvalidate = cache.asMap().keys.filter { it.startsWith("perm:$userId:") }
        keysToInvalidate.forEach { cache.invalidate(it) }
        logger.info { "Invalidated ${keysToInvalidate.size} cache entries for user: $userId" }
    }

    /**
     * Invalidates all cached permissions for a specific entity.
     *
     * @param entityType The type of entity
     * @param entityId The ID of the entity
     */
    fun invalidateEntity(entityType: String, entityId: String) {
        val pattern = ":$entityType:$entityId:"
        val keysToInvalidate = cache.asMap().keys.filter { it.contains(pattern) }
        keysToInvalidate.forEach { cache.invalidate(it) }
        logger.info { "Invalidated ${keysToInvalidate.size} cache entries for entity: $entityType:$entityId" }
    }

    /**
     * Clears the entire cache.
     */
    fun clear() {
        val size = cache.estimatedSize()
        cache.invalidateAll()
        logger.info { "Cleared entire permission cache ($size entries)" }
    }

    /**
     * Gets cache statistics for monitoring.
     */
    fun getStats() = cache.stats()
}