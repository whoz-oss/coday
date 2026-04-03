package io.whozoss.agentos.entity

import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.whozoss.agentos.sdk.entity.Entity
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Abstract base controller for entity-based REST endpoints.
 *
 * Provides standard CRUD operations for entities that implement the Entity interface.
 * Subclasses add @RestController and @RequestMapping, then inherit all endpoints.
 * Any method can be overridden to customize or restrict behaviour (e.g. immutable entities).
 *
 * The [ResourceType] parameter separates the HTTP contract from the domain model.
 * Each concrete controller declares its own resource/DTO class and implements
 * [toResource] and [toDomain] to convert between the two.
 *
 * For entities where no DTO separation is needed yet, extend [SimpleEntityController]
 * instead — it provides identity implementations of [toResource] and [toDomain].
 *
 * Standard endpoints provided:
 * - GET    /{id}                    — get by ID
 * - POST   /by-ids                  — get multiple by IDs
 * - GET    /by-parentId/{parentId}  — list all entities belonging to a parent
 * - POST                            — create
 * - PUT    /{id}                    — update
 * - DELETE /{id}                    — soft-delete
 *
 * Type parameters:
 * @param EntityType The domain entity type (must implement Entity)
 * @param ParentIdentifier The parent identifier type (typically UUID)
 * @param ResourceType The HTTP resource/DTO type returned and consumed by all endpoints
 */
abstract class EntityController<EntityType : Entity, ParentIdentifier, ResourceType>(
    protected val service: EntityService<EntityType, ParentIdentifier>,
) {
    /**
     * Convert a domain entity to its HTTP resource representation.
     * Called before every response is serialised.
     */
    abstract fun toResource(entity: EntityType): ResourceType

    /**
     * Convert an HTTP resource to its domain entity representation.
     * Called after every request body is deserialised.
     */
    abstract fun toDomain(resource: ResourceType): EntityType

    /**
     * GET /{id} — get a single entity by its ID.
     */
    @GetMapping("/{id}", produces = [MediaType.APPLICATION_JSON_VALUE])
    open fun getById(
        @PathVariable id: UUID,
    ): ResourceType =
        service.findById(id)
            ?.let { toResource(it) }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Entity not found: $id")

    /**
     * POST /by-ids — get multiple entities by their IDs.
     */
    @PostMapping(
        "/by-ids",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    open fun getByIds(
        @RequestBody ids: List<UUID>,
    ): List<ResourceType> = service.findByIds(ids).map { toResource(it) }

    /**
     * GET /by-parentId/{parentId} — list all entities belonging to a parent.
     *
     * Subclasses may override to rename the query parameter or add filtering.
     * The ParentIdentifier is passed as a UUID — subclasses with non-UUID parent types
     * must override this method entirely.
     */
    @GetMapping("/by-parentId/{parentId}", produces = [MediaType.APPLICATION_JSON_VALUE])
    open fun listByParent(
        @Parameter(description = "Parent entity ID", schema = Schema(type = "string", format = "uuid"))
        @PathVariable parentId: ParentIdentifier,
    ): List<ResourceType> = service.findByParent(parentId).map { toResource(it) }

    /**
     * POST — create a new entity.
     */
    @PostMapping(
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    @ResponseStatus(HttpStatus.CREATED)
    open fun create(
        @RequestBody resource: ResourceType,
    ): ResourceType = toResource(service.create(toDomain(resource)))

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
        @RequestBody resource: ResourceType,
    ): ResourceType {
        service.findById(id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Entity not found: $id")
        return toResource(service.update(toDomain(resource)))
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
