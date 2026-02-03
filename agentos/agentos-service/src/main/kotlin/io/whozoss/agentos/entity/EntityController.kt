package io.whozoss.agentos.entity

import io.whozoss.agentos.sdk.entity.Entity
import io.whozoss.agentos.sdk.entity.EntityService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Abstract base controller for entity-based REST endpoints.
 *
 * Provides standard CRUD operations for entities that:
 * - Implement the Entity interface (have EntityMetadata)
 * - May belong to a parent entity
 * - Are managed by an EntityService
 *
 * Subclasses must:
 * - Add @RestController annotation
 * - Add @RequestMapping annotation with appropriate base path
 * - Provide the EntityService implementation via constructor
 *
 * Example:
 * ```kotlin
 * @RestController
 * @RequestMapping("/api/projects/{projectId}/cases")
 * class CaseController(
 *     service: CaseService
 * ) : EntityController<CaseModel, UUID>(service)
 * ```
 *
 * Type parameters:
 * @param EntityType The entity type (must implement Entity)
 * @param ParentIdentifier The parent identifier type (typically UUID for projectId, caseId, etc.)
 */
abstract class EntityController<EntityType : Entity, ParentIdentifier>(
    protected val service: EntityService<EntityType, ParentIdentifier>,
) {
    /**
     * Get a single entity by its ID.
     *
     * @param id The unique identifier
     * @return The entity if found
     * @throws ResponseStatusException with 404 status if not found
     */
    @GetMapping("/{id}")
    fun getById(
        @PathVariable id: UUID,
    ): EntityType =
        service.findById(id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Entity not found: $id")

    /**
     * Get multiple entities by their IDs.
     *
     * @param ids Comma-separated list of UUIDs
     * @return List of found entities (may be smaller than input if some IDs don't exist or are removed)
     * @throws ResponseStatusException with 400 status if ids parameter is missing or empty
     */
    @GetMapping
    fun getByIds(
        @RequestParam(required = false) ids: List<UUID>?,
    ): List<EntityType> {
        if (ids.isNullOrEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Parameter 'ids' is required")
        }
        return service.findByIds(ids)
    }

    /**
     * List all entities belonging to a parent.
     *
     * Must be overridden by subclasses to extract the parent identifier from path variables.
     *
     * Example:
     * ```kotlin
     * @GetMapping
     * override fun listByParent(@PathVariable projectId: UUID): List<CaseModel> {
     *     return super.listByParent(projectId)
     * }
     * ```
     *
     * @param parentId The parent identifier (e.g., projectId for cases, caseId for events)
     * @return List of entities belonging to the parent
     */
    protected fun listByParent(parentId: ParentIdentifier): List<EntityType> = service.findByParent(parentId)

    /**
     * Create a new entity.
     *
     * @param entity The entity to create
     * @return The created entity (including generated ID and timestamps)
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @RequestBody entity: EntityType,
    ): EntityType = service.save(entity)

    /**
     * Update an existing entity.
     *
     * @param id The unique identifier of the entity to update
     * @param entity The updated entity data
     * @return The updated entity
     * @throws ResponseStatusException with 404 status if not found
     */
    @PutMapping("/{id}")
    fun update(
        @PathVariable id: UUID,
        @RequestBody entity: EntityType,
    ): EntityType {
        // Verify entity exists
        service.findById(id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Entity not found: $id")

        // Save updated entity
        return service.save(entity)
    }

    /**
     * Soft delete a single entity.
     *
     * @param id The unique identifier of the entity to delete
     * @throws ResponseStatusException with 404 status if not found
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @PathVariable id: UUID,
    ) {
        val deletedCount = service.deleteMany(listOf(id))
        if (deletedCount == 0) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Entity not found: $id")
        }
    }

    /**
     * Soft delete multiple entities.
     *
     * @param ids Comma-separated list of UUIDs to delete
     * @return Map with number of entities deleted
     */
    @DeleteMapping
    fun deleteMany(
        @RequestParam ids: List<UUID>,
    ): Map<String, Int> {
        val deletedCount = service.deleteMany(ids)
        return mapOf("deleted" to deletedCount)
    }
}
