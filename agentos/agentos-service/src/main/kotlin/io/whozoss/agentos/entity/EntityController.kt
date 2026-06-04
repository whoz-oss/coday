package io.whozoss.agentos.entity

import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.entity.Entity
import io.whozoss.agentos.user.UserService
import jakarta.validation.Valid
import mu.KLogging
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
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
 * Standard endpoints provided:
 * - GET    /{id}                    — get by ID
 * - POST   /by-ids                  — get multiple by IDs (permission-filtered batch)
 * - GET    /by-parentId/{parentId}  — list all entities belonging to a parent
 * - POST                            — create
 * - PUT    /{id}                    — update
 * - DELETE /{id}                    — soft-delete
 *
 * **Batch authorization** ([getByIds]) follows the pattern introduced by story 5-3 and
 * factorised here by story 5-4 :
 * - empty input short-circuit
 * - super-admin bypass via [io.whozoss.agentos.user.User.isAdmin]
 * - regular caller : [PermissionService.filterVisibleIds] resolves visible ids in
 *   ≤ 2 Cypher queries (UNION direct + transitif) regardless of input size
 * - input order and duplicates are preserved through a `Map<UUID, ResourceType>` lookup
 * - input is bounded by [MAX_BATCH_SIZE] (runtime check throwing HTTP 400) to
 *   prevent DoS via huge batches
 * - malformed ids returned by [PermissionService] (should not happen — corruption /
 *   schema drift) are logged at WARN level rather than silently dropped
 *
 * Subclasses with a different authorization model (e.g. parent-id resolution like
 * [io.whozoss.agentos.caseEvent.CaseEventRestController]) MUST override [getByIds]
 * and document the divergence.
 *
 * Type parameters:
 * @param E The domain entity type (must implement Entity)
 * @param ParentIdentifier The parent identifier type (typically UUID)
 * @param ResourceType The HTTP resource/DTO type returned and consumed by all endpoints
 */
abstract class EntityController<E : Entity, ParentIdentifier, ResourceType>(
    protected val service: EntityService<E, ParentIdentifier>,
    protected val userService: UserService,
    protected val permissionService: PermissionService,
) {
    /**
     * Typed entity discriminator used by [getByIds] to call
     * [PermissionService.filterVisibleIds]. Each subclass declares its
     * matching [EntityType] constant (e.g. `EntityType.AGENT_CONFIG`).
     */
    protected abstract val entityType: EntityType

    /**
     * Convert a domain entity to its HTTP resource representation.
     * Called before every response is serialised.
     */
    abstract fun toResource(entity: E): ResourceType

    /**
     * Convert an HTTP resource to its domain entity representation.
     * Called after every request body is deserialised.
     */
    abstract fun toDomain(resource: ResourceType): E

    /**
     * GET /{id} — get a single entity by its ID.
     *
     * Passes [withRemoved]=true so that a direct lookup by ID resolves the entity
     * regardless of its removal state. This mirrors [EntityService.getById] semantics
     * and allows callers to inspect or audit a soft-deleted entity via REST.
     */
    @GetMapping("/{id}", produces = [MediaType.APPLICATION_JSON_VALUE])
    open fun getById(
        @PathVariable id: UUID,
    ): ResourceType =
        service.findById(id, withRemoved = true)
            ?.let { toResource(it) }
            ?: throw ResourceNotFoundException("Entity not found: $id")

    /**
     * POST /by-ids — get multiple entities by their IDs, permission-filtered in batch.
     *
     * Authorization is resolved in a single Cypher round-trip (≤ 2 queries via UNION)
     * regardless of input size — see [PermissionService.filterVisibleIds]. Output preserves
     * the input order and duplicates so clients that index on request position keep working.
     *
     * Capped at [MAX_BATCH_SIZE] ids to prevent DoS via unbounded requests.
     *
     * @param request Wrapper containing the list of IDs and an optional [GetByIdsRequest.withRemoved]
     *   flag. When false (default), soft-deleted entities are excluded from the result.
     */
    @PostMapping(
        "/by-ids",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    @PreAuthorize("isAuthenticated()")
    open fun getByIds(
        @RequestBody request: GetByIdsRequest,
    ): List<ResourceType> {
        val ids = request.ids
        // Runtime size cap. We deliberately do NOT use Bean Validation `@Size` here :
        // `@Size` on a `@RequestBody` parameter only fires when the controller class
        // is annotated `@Validated` (Spring), and its violation maps by default to
        // HTTP 500 rather than 400. A runtime check is explicit, framework-independent,
        // and produces the right HTTP status (close adversarial review P1 of story 5-4).
        if (ids.size > MAX_BATCH_SIZE) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Batch size ${ids.size} exceeds maximum of $MAX_BATCH_SIZE",
            )
        }
        if (ids.isEmpty()) return emptyList()

        val currentUser = userService.getCurrentUser()
        val visibleIds: Set<UUID> = if (currentUser.isAdmin) {
            ids.toSet()
        } else {
            val rawVisible = permissionService.filterVisibleIds(
                userId = currentUser.id.toString(),
                entityType = entityType,
                ids = ids.map(UUID::toString),
                action = Action.READ,
            )
            val parsed = rawVisible.mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
            if (parsed.size != rawVisible.size) {
                logger.warn {
                    "[EntityController:$entityType] PermissionService returned ${rawVisible.size - parsed.size} non-UUID id(s); dropping. " +
                        "This indicates a data corruption or schema drift — investigate."
                }
            }
            parsed.toSet()
        }
        if (visibleIds.isEmpty()) return emptyList()

        // Preserve input order and duplicates : look up each input id in the entity map.
        val entityById = service.findByIds(visibleIds, request.withRemoved).associateBy { it.metadata.id }
        return ids.mapNotNull { id -> entityById[id]?.let(::toResource) }
    }

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
        @Valid @RequestBody resource: ResourceType,
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
        @Valid @RequestBody resource: ResourceType,
    ): ResourceType {
        service.findById(id)
            ?: throw ResourceNotFoundException("Entity not found: $id")
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
            throw ResourceNotFoundException("Entity not found: $id")
        }
    }

    companion object : KLogging() {
        /**
         * Maximum number of ids accepted in a single `POST /by-ids` request.
         * Above this, the request is rejected at the validation layer to prevent DoS
         * via unbounded payloads (Cypher message size, memory pressure, etc.).
         */
        const val MAX_BATCH_SIZE = 1000
    }
}

/**
 * Request body for `POST /by-ids`.
 *
 * @param ids List of entity UUIDs to fetch.
 * @param withRemoved when true, soft-deleted entities are included in the result.
 */
data class GetByIdsRequest(
    val ids: List<UUID>,
    val withRemoved: Boolean = false,
)
