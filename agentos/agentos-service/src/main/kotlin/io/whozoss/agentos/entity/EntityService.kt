package io.whozoss.agentos.entity

import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.sdk.entity.Entity
import java.util.*

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
 * @param EntityType The entity type (must implement Entity)
 * @param ParentIdentifier The parent identifier type (typically UUID for projectId, caseId, etc.)
 */
interface EntityService<EntityType : Entity, ParentIdentifier> {
    fun create(entity: EntityType): EntityType

    fun update(entity: EntityType): EntityType

    /**
     * Find a single entity by its identifier.
     * Convenience method that delegates to findByIds.
     *
     * Excludes removed entities by default.
     *
     * @param id The unique identifier
     * @return The entity if found and not removed, null otherwise
     */
    fun findById(id: UUID): EntityType? = findByIds(listOf(id)).firstOrNull()

    /**
     * Find multiple entities by their identifiers.
     *
     * Excludes removed entities by default.
     *
     * @param ids Collection of unique identifiers
     * @return List of found entities (may be smaller than input if some IDs don't exist or are removed)
     */
    fun findByIds(ids: Collection<UUID>): List<EntityType>

    /**
     * Find all entities belonging to a parent.
     *
     * Excludes removed entities by default.
     *
     * @param parentId The parent identifier (e.g., projectId for cases, caseId for events)
     * @return List of entities belonging to the parent
     */
    fun findByParent(parentId: ParentIdentifier): List<EntityType>

    /**
     * Get a single entity by its identifier.
     * Excludes removed entities by default.
     *
     * @param id The unique identifier
     * @return The entity
     * @throws io.whozoss.agentos.exception.ResourceNotFoundException if not found
     */
    fun getById(id: UUID): EntityType = findById(id) ?: throw ResourceNotFoundException("Entity $id not found")

    /**
     * Soft delete a single entity by its identifier.
     * Sets the removed flag to true instead of physically deleting.
     *
     * @param id The unique identifier
     * @return true if the entity was deleted, false if not found or already removed
     */
    fun delete(id: UUID): Boolean

    /**
     * Soft delete all entities belonging to a parent.
     * Sets the removed flag to true instead of physically deleting.
     * Useful for cascade deletion when a parent entity is removed.
     *
     * @param parentId The parent identifier
     * @return Number of entities actually marked as removed
     */
    fun deleteByParent(parentId: ParentIdentifier): Int
}
