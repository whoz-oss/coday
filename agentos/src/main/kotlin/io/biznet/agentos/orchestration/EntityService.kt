package io.biznet.agentos.orchestration

import java.util.UUID

/**
 * Base interface for services managing entities with hierarchical relationships.
 *
 * Provides standard CRUD operations for entities that:
 * - Implement the Entity interface (have EntityMetadata)
 * - May belong to a parent entity
 * - Need persistent storage
 *
 * All delete operations are soft deletes (set removed flag).
 *
 * Type parameters:
 * @param T The entity type (must implement Entity)
 * @param P The parent identifier type (typically UUID for projectId, caseId, etc.)
 */
interface EntityService<T : Entity, P> {
    /**
     * Save an entity (create if new, update if exists).
     *
     * Spring Data will handle automatic timestamp updates (modified field).
     *
     * @param entity The entity to save
     * @return The saved entity (may include generated fields like ID, timestamps)
     */
    fun save(entity: T): T

    /**
     * Find a single entity by its identifier.
     * Convenience method that delegates to findByIds.
     *
     * Excludes removed entities by default.
     *
     * @param id The unique identifier
     * @return The entity if found and not removed, null otherwise
     */
    fun findById(id: UUID): T? = findByIds(listOf(id)).firstOrNull()

    /**
     * Find multiple entities by their identifiers.
     *
     * Excludes removed entities by default.
     *
     * @param ids Collection of unique identifiers
     * @return List of found entities (may be smaller than input if some IDs don't exist or are removed)
     */
    fun findByIds(ids: Collection<UUID>): List<T>

    /**
     * Find all entities belonging to a parent.
     *
     * Excludes removed entities by default.
     *
     * @param parentId The parent identifier (e.g., projectId for cases, caseId for events)
     * @return List of entities belonging to the parent
     */
    fun findByParent(parentId: P): List<T>

    /**
     * Soft delete multiple entities by their identifiers.
     * Sets the removed flag to true instead of physically deleting.
     *
     * @param ids Collection of unique identifiers
     * @return Number of entities actually marked as removed
     */
    fun deleteMany(ids: Collection<UUID>): Int
}
