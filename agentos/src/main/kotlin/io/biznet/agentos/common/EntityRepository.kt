package io.biznet.agentos.common

import java.util.UUID

/**
 * Base interface for repositories managing entity persistence.
 *
 * Repositories are responsible for:
 * - Physical storage and retrieval of entities
 * - Data access optimization
 * - Storage-specific concerns (caching, indexing, etc.)
 *
 * This is a pure persistence abstraction with no business logic.
 *
 * Type parameters:
 * @param T The entity type managed by this repository
 * @param P The parent identifier type (typically UUID for projectId, caseId, etc.)
 */
interface EntityRepository<T, P> {
    /**
     * Save an entity (create if new, update if exists).
     *
     * @param entity The entity to save
     * @return The saved entity
     */
    fun save(entity: T): T

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
     * @param parentId The parent identifier
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
