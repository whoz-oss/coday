package io.whozoss.agentos.entity

import io.whozoss.agentos.sdk.entity.Entity
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Generic in-memory implementation of [EntityRepository].
 *
 * Intended for use in unit tests and plugin development — not for production use.
 * There is no Spring wiring here; instantiate directly in tests via delegation:
 *
 * ```kotlin
 * val repo = object : MyRepository,
 *     EntityRepository<MyEntity, UUID> by InMemoryEntityRepository(
 *         parentIdExtractor = { it.parentId },
 *         comparator = compareBy { it.name },
 *     ) {}
 * ```
 *
 * Thread-safe: uses [ConcurrentHashMap] for storage. Write operations on the
 * parent index are synchronized to maintain consistent ordering.
 */
class InMemoryEntityRepository<T : Entity, P>(
    private val parentIdExtractor: (T) -> P,
    private val comparator: Comparator<T>,
) : EntityRepository<T, P> {
    private val entitiesById = ConcurrentHashMap<UUID, T>()

    // ConcurrentHashMap does not support null keys. We use a sentinel object to represent
    // null parent ids (e.g. platform-level AgentConfig with namespaceId = null).
    private val entityIdsByParentId = ConcurrentHashMap<Any, MutableList<UUID>>()

    private fun parentKey(parentId: P): Any = parentId ?: NULL_PARENT_KEY

    @Synchronized
    override fun save(entity: T): T {
        val entityId = entity.metadata.id
        val parentId = parentIdExtractor(entity)
        val existing = entitiesById[entityId]

        if (existing != null) {
            val oldParentId = parentIdExtractor(existing)
            entityIdsByParentId[parentKey(oldParentId)]?.remove(entityId)
        }

        entitiesById[entityId] = entity

        val parentEntityIds = entityIdsByParentId.computeIfAbsent(parentKey(parentId)) { mutableListOf() }
        val insertIndex =
            parentEntityIds.indexOfFirst { id ->
                val existingEntity = entitiesById[id]
                existingEntity != null && comparator.compare(entity, existingEntity) < 0
            }
        if (insertIndex == -1) parentEntityIds.add(entityId) else parentEntityIds.add(insertIndex, entityId)

        return entity
    }

    override fun findByIds(
        ids: Collection<UUID>,
        withRemoved: Boolean,
    ): List<T> = ids.mapNotNull { entitiesById[it] }.filter { withRemoved || !it.metadata.removed }

    override fun findByParent(parentId: P): List<T> =
        (entityIdsByParentId[parentKey(parentId)] ?: emptyList())
            .mapNotNull { entitiesById[it] }
            .filter { !it.metadata.removed }

    /** Returns all non-removed entities across all parents. Useful in tests. */
    fun findAll(): List<T> = entitiesById.values.filter { !it.metadata.removed }.sortedWith(comparator)

    @Synchronized
    override fun delete(id: UUID): Boolean {
        val entity = entitiesById[id]
        return if (entity != null && !entity.metadata.removed) {
            entity.metadata.removed = true
            true
        } else {
            false
        }
    }

    @Synchronized
    override fun deleteByParent(parentId: P): Int {
        val entityIds = entityIdsByParentId[parentKey(parentId)] ?: return 0
        var count = 0
        entityIds.forEach { id -> if (delete(id)) count++ }
        return count
    }

    companion object {
        private val NULL_PARENT_KEY = Any()
    }
}
