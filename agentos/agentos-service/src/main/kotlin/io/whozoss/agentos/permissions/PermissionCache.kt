package io.whozoss.agentos.permissions

import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Cache
import mu.KLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Caffeine-backed cache for permission check results.
 *
 * - TTL: 5 minutes
 * - Maximum size: 10,000 entries
 * - Key format: `perm:{userId}:{entityType}:{entityId}:{action}`
 */
@Component
@ConditionalOnExpression(
    "'\${agentos.persistence.mode:embedded-neo4j}' == 'neo4j' " +
        "or '\${agentos.persistence.mode:embedded-neo4j}' == 'embedded-neo4j'",
)
class PermissionCache {

    companion object : KLogging() {
        private const val MAX_SIZE = 10_000L
        private val TTL = Duration.ofMinutes(5)

        fun generateKey(
            userId: String,
            entityType: EntityType,
            entityId: String,
            action: Action,
        ): String = "perm:$userId:${entityType.label}:$entityId:$action"
    }

    private val cache: Cache<String, Boolean> = Caffeine.newBuilder()
        .maximumSize(MAX_SIZE)
        .expireAfterWrite(TTL)
        .recordStats()
        .build()

    fun get(key: String): Boolean? =
        cache.getIfPresent(key).also { result ->
            if (result != null) {
                logger.debug { "Cache hit for key: $key -> $result" }
            } else {
                logger.debug { "Cache miss for key: $key" }
            }
        }

    fun put(key: String, value: Boolean) {
        cache.put(key, value)
        logger.debug { "Cached permission: $key -> $value" }
    }

    /** Invalidates all cached permissions for a specific user. */
    fun invalidateUser(userId: String) {
        val keysToInvalidate = cache.asMap().keys.filter { it.startsWith("perm:$userId:") }
        keysToInvalidate.forEach { cache.invalidate(it) }
        logger.info { "Invalidated ${keysToInvalidate.size} cache entries for user: $userId" }
    }

    fun clear() {
        val size = cache.estimatedSize()
        cache.invalidateAll()
        logger.info { "Cleared entire permission cache ($size entries)" }
    }
}
