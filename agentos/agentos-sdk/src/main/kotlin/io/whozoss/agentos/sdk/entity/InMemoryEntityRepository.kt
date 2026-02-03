package io.whozoss.agentos.sdk.entity

import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Generic in-memory implementation of EntityRepository.
 *
 * Storage strategy:
 * - Entities are stored in a ConcurrentHashMap by entity ID (from metadata) for O(1) access
 * - A secondary index groups entities by parent ID for efficient parent lookups
 * - Entities are maintained in sorted order within each parent (using provided comparator)
 * - Removed entities are excluded from queries
 *
 * Thread-safety:
 * - Uses ConcurrentHashMap for thread-safe access
 * - save() and deleteMany() are synchronized to prevent race conditions during indexing
 *
 * Type parameters:
 * @param T The entity type (must implement Entity)
 * @param P The parent identifier type
 *
 * @param parentIdExtractor Function to extract the parent ID from the entity
 * @param comparator Comparator for ordering entities within a parent
 */
abstract class InMemoryEntityRepository<T : Entity, P>(
    private val parentIdExtractor: (T) -> P,
    private val comparator: Comparator<T>,
) : EntityRepository<T, P> {
    private val logger = LoggerFactory.getLogger(this::class.java)

    /**
     * Primary storage: entity ID -> entity
     */
    private val entitiesById = ConcurrentHashMap<UUID, T>()

    /**
     * Secondary index: parent ID -> list of entity IDs (ordered by comparator)
     */
    private val entityIdsByParentId = ConcurrentHashMap<P, MutableList<UUID>>()

    /**
     * Save an entity (create or update).
     */
    @Synchronized
    override fun save(entity: T): T {
        val entityId = entity.metadata.id
        val parentId = parentIdExtractor(entity)
        val existing = entitiesById[entityId]

        if (existing != null) {
            // Update: remove from old position in parent index
            val oldParentId = parentIdExtractor(existing)
            entityIdsByParentId[oldParentId]?.remove(entityId)
        }

        // Store entity
        entitiesById[entityId] = entity

        // Update parent index: insert in sorted order
        val parentEntityIds = entityIdsByParentId.computeIfAbsent(parentId) { mutableListOf() }

        // Find insertion point using comparator
        val insertIndex =
            parentEntityIds.indexOfFirst { id ->
                val existingEntity = entitiesById[id]
                existingEntity != null && comparator.compare(entity, existingEntity) < 0
            }

        if (insertIndex == -1) {
            // Entity goes at the end
            parentEntityIds.add(entityId)
        } else {
            // Insert at the correct position
            parentEntityIds.add(insertIndex, entityId)
        }

        logger.debug("[Parent $parentId] Entity saved (id=$entityId)")
        return entity
    }

    /**
     * Find multiple entities by their IDs.
     * Excludes removed entities.
     */
    override fun findByIds(ids: Collection<UUID>): List<T> =
        ids
            .mapNotNull { entitiesById[it] }
            .filter { !it.metadata.removed }

    /**
     * Find all entities belonging to a parent, ordered by comparator.
     * Excludes removed entities.
     */
    override fun findByParent(parentId: P): List<T> {
        val entityIds = entityIdsByParentId[parentId] ?: return emptyList()
        return entityIds
            .mapNotNull { entitiesById[it] }
            .filter { !it.metadata.removed }
    }

    /**
     * Soft delete multiple entities by their IDs.
     * Marks entities as removed instead of physically deleting them.
     */
    @Synchronized
    override fun deleteMany(ids: Collection<UUID>): Int {
        var deletedCount = 0

        ids.forEach { id ->
            val entity = entitiesById[id]
            if (entity != null && !entity.metadata.removed) {
                entity.metadata.removed = true
                deletedCount++
                logger.debug("Entity soft deleted (id=$id)")
            }
        }

        return deletedCount
    }
}
