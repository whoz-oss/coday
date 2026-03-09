package io.whozoss.agentos.entity

import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.whozoss.agentos.sdk.entity.Entity
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.util.*

/**
 * Abstract base controller for entity-based REST endpoints.
 *
 * Provides standard CRUD operations for entities that implement the Entity interface.
 * Subclasses add @RestController and @RequestMapping, then inherit all endpoints.
 * Any method can be overridden to customize or restrict behaviour (e.g. immutable entities).
 *
 * Standard endpoints provided:
 * - GET    /{id}            — get by ID
 * - GET    ?ids=a,b,c       — get multiple by IDs
 * - GET    ?parentId=xxx    — list all entities belonging to a parent
 * - POST                    — create
 * - PUT    /{id}            — update
 * - DELETE /{id}            — soft-delete
 *
 * Type parameters:
 * @param EntityType The entity type (must implement Entity)
 * @param ParentIdentifier The parent identifier type (typically UUID)
 */
abstract class EntityController<EntityType : Entity, ParentIdentifier>(
    protected val service: EntityService<EntityType, ParentIdentifier>,
) {
    /**
     * GET /{id} — get a single entity by its ID.
     */
    @GetMapping("/{id}", produces = [MediaType.APPLICATION_JSON_VALUE])
    open fun getById(
        @PathVariable id: UUID,
    ): EntityType =
        service.findById(id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Entity not found: $id")

    /**
     * GET ?ids=a,b,c — get multiple entities by their IDs.
     */
    @PostMapping(
        "/by-ids",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    open fun getByIds(
        @RequestBody ids: List<UUID>,
    ): List<EntityType> = service.findByIds(ids)

    /**
     * GET ?parentId=xxx — list all entities belonging to a parent.
     *
     * Subclasses may override to rename the query parameter or add filtering.
     * The ParentIdentifier is passed as a UUID — subclasses with non-UUID parent types
     * must override this method entirely.
     */
    @GetMapping("/by-parentId/{parentId}", produces = [MediaType.APPLICATION_JSON_VALUE])
    open fun listByParent(
        @Parameter(description = "Parent entity ID", schema = Schema(type = "string", format = "uuid"))
        @PathVariable parentId: ParentIdentifier,
    ): List<EntityType> = service.findByParent(parentId)

    /**
     * POST — create a new entity.
     */
    @PostMapping(
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    @ResponseStatus(HttpStatus.CREATED)
    open fun create(
        @RequestBody entity: EntityType,
    ): EntityType = service.create(entity)

    /**
     * PUT /{id} — update an existing entity.
     */
    @PutMapping(
        "/{id}",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    open fun update(
        @PathVariable id: UUID,
        @RequestBody entity: EntityType,
    ): EntityType {
        service.findById(id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Entity not found: $id")
        return service.update(entity)
    }

    /**
     * DELETE /{id} — soft-delete a single entity.
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    open fun delete(
        @PathVariable id: UUID,
    ) {
        val deleted = service.delete(id)
        if (!deleted) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Entity not found: $id")
        }
    }
}
