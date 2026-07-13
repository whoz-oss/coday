package io.whozoss.agentos.entity

import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.entity.Entity
import io.whozoss.agentos.user.UserService
import mu.KLogging
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Holder for the five standard CRUD operations shared across all entity controllers.
 *
 * Controllers instantiate this delegate inline and call it explicitly by name:
 *
 * ```kotlin
 * class FooController(
 *     private val fooService: FooService,
 *     private val userService: UserService,
 *     private val permissionService: PermissionService,
 * ) : FooApi {
 *
 *     private val crudDelegate = EntityCrudDelegate(
 *         service = fooService,
 *         userService = userService,
 *         permissions = permissionService,
 *         entityType = EntityType.FOO,
 *         toResource = { toDto(it as Foo) },
 *     )
 *
 *     @PostMapping("/by-ids", ...)
 *     override fun getByIds(request: SdkGetByIdsRequest) =
 *         crudDelegate.getByIds(GetByIdsRequest(request.ids, request.withRemoved))
 *
 *     @DeleteMapping("/{id}")
 *     override fun delete(@PathVariable id: UUID) = crudDelegate.delete(id)
 * }
 * ```
 *
 * The delegate is **not** implemented as an interface on the controller. Every method
 * is called via the named field, so there is no ambiguity between the controller's own
 * override and the delegate's implementation.
 *
 * No Spring MVC annotations live here. Each controller owns every routing and
 * authorization annotation on every method it exposes.
 */
class EntityCrudDelegate<ResourceType>(
    private val service: EntityService<*, *>,
    private val userService: UserService,
    private val permissions: PermissionService,
    private val entityType: EntityType,
    private val toResource: (Entity) -> ResourceType,
    private val toDomain: ((ResourceType) -> Entity)? = null,
) {
    /**
     * Fetch a single entity by ID. Includes soft-deleted entities so that callers
     * can inspect or audit a removed record via REST.
     */
    fun getById(id: UUID): ResourceType =
        service.findById(id, withRemoved = true)
            ?.let(toResource)
            ?: throw ResourceNotFoundException("Entity not found: $id")

    /**
     * Batch fetch by IDs, permission-filtered in a single Cypher round-trip.
     *
     * - Super-admins bypass permission filtering.
     * - Regular callers: [PermissionService.filterVisibleIds] resolves visible IDs
     *   in ≤2 queries regardless of input size.
     * - Input order and duplicates are preserved.
     * - Capped at [MAX_BATCH_SIZE] to prevent DoS via unbounded requests.
     *
     * @param extraVisibility Optional predicate `(entity, callerId) -> Boolean` that can
     *   grant visibility for entities that fall outside the standard permission graph.
     *   Used by overlay entity types (AiProvider, IntegrationConfig) where user-owned
     *   records are always visible to their owner regardless of permission edges.
     *   Ignored for super-admins (they already see everything).
     */
    fun getByIds(
        request: GetByIdsRequest,
        extraVisibility: ((Entity, UUID) -> Boolean)? = null,
    ): List<ResourceType> {
        val ids = request.ids
        if (ids.size > MAX_BATCH_SIZE) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Batch size ${ids.size} exceeds maximum of $MAX_BATCH_SIZE",
            )
        }
        if (ids.isEmpty()) return emptyList()

        val currentUser = userService.getCurrentUser()

        if (currentUser.isAdmin) {
            val entityById = service.findByIds(ids.toSet(), request.withRemoved).associateBy { it.metadata.id }
            return ids.mapNotNull { id -> entityById[id]?.let(toResource) }
        }

        val rawVisible = permissions.filterVisibleIds(
            userId = currentUser.id.toString(),
            entityType = entityType,
            ids = ids.map(UUID::toString),
            action = Action.READ,
        )
        val parsed = rawVisible.mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
        if (parsed.size != rawVisible.size) {
            logger.warn {
                "[$entityType] PermissionService returned ${rawVisible.size - parsed.size} non-UUID id(s); dropping. " +
                    "This indicates data corruption or schema drift — investigate."
            }
        }
        val visibleIds = parsed.toSet()

        // Fetch all requested entities once; filter by permission edge or extra predicate.
        val rows = service.findByIds(ids.toSet(), request.withRemoved)
        val callerId = currentUser.id
        val byId = rows
            .filter { it.metadata.id in visibleIds || extraVisibility?.invoke(it, callerId) == true }
            .associateBy { it.metadata.id }

        if (byId.isEmpty()) return emptyList()
        return ids.mapNotNull { id -> byId[id]?.let(toResource) }
    }

    /**
     * List all entities belonging to the given parent.
     *
     * The cast is safe for all current callers: every entity whose controller exposes
     * `listByParent` via the SDK interface has `ParentIdentifier = UUID`. Controllers
     * whose `ParentIdentifier` is `String` (Namespace, User) do not expose this method
     * in their SDK interface and therefore never call it through the delegate.
     */
    fun listByParent(parentId: UUID): List<ResourceType> {
        @Suppress("UNCHECKED_CAST")
        val service = this.service as EntityService<*, UUID>
        return service.findByParent(parentId).map(toResource)
    }

    /**
     * Create a new entity from its resource representation.
     * Requires [toDomain] to have been provided at construction time;
     * throws [IllegalStateException] if omitted (e.g. when the controller
     * delegates to [ScopedOwnershipCrudDelegate] instead).
     */
    fun create(resource: ResourceType): ResourceType {
        val mapper = toDomain
            ?: error("crud.create() called but toDomain was not provided for $entityType")
        return createEntity(mapper(resource))
    }

    /**
     * Create a new entity from an already-constructed domain object.
     * Used by [ScopedOwnershipCrudDelegate], which builds the entity itself
     * (after scope resolution) and needs to persist it without going through
     * the [toDomain] conversion a second time.
     *
     * The cast to `EntityService<Entity, *>` is safe: all concrete service
     * implementations accept any [Entity] subtype. The star projection on
     * [EntityService] stored in this delegate prohibits calling `create`
     * directly, so we pin the first type parameter to its upper bound.
     */
    internal fun createEntity(entity: Entity): ResourceType {
        @Suppress("UNCHECKED_CAST")
        val svc = service as EntityService<Entity, *>
        return toResource(svc.create(entity))
    }

    /** Soft-delete an entity by ID. Throws 404 if not found. */
    fun delete(id: UUID) {
        if (!service.delete(id)) throw ResourceNotFoundException("Entity not found: $id")
    }

    companion object : KLogging() {
        /**
         * Maximum number of IDs accepted in a single `POST /by-ids` request.
         * Requests above this limit are rejected with HTTP 400.
         */
        const val MAX_BATCH_SIZE = 1000
    }
}

