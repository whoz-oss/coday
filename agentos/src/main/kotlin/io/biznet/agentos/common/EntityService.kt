package io.biznet.agentos.common

import java.util.UUID

/**
 * Base interface for services managing entities with hierarchical relationships.
 *
 * Provides standard CRUD operations for entities that:
 * - Have a unique identifier (UUID)
 * - May belong to a parent entity
 * - Need persistent storage
 *
 * Type parameters:
 * @param T The entity type managed by this service
 * @param P The parent identifier type (typically UUID for projectId, caseId, etc.)
 */
interface EntityService<T, P> {
    /**
     * Save an entity (create if new, update if exists).
     *
     * @param entity The entity to save
     * @return The saved entity (may include generated fields like ID, timestamps)
     */
    fun save(entity: T): T

    /**
     * Find a single entity by its identifier.
     * Convenience method that delegates to findByIds.
     *
     * @param id The unique identifier
     * @return The entity if found, null otherwise
     */
    fun findById(id: UUID): T? {
        return findByIds(listOf(id)).firstOrNull()
    }

    /**
     * Find multiple entities by their identifiers.
     *
     * @param ids Collection of unique identifiers
     * @return List of found entities (may be smaller than input if some IDs don't exist)
     */
    fun findByIds(ids: Collection<UUID>): List<T>

    /**
     * Find all entities belonging to a parent.
     *
     * @param parentId The parent identifier (e.g., projectId for cases, caseId for events)
     * @return List of entities belonging to the parent
     */
    fun findByParent(parentId: P): List<T>

    /**
     * Delete multiple entities by their identifiers.
     *
     * @param ids Collection of unique identifiers
     * @return Number of entities actually deleted
     */
    fun deleteMany(ids: Collection<UUID>): Int
}
