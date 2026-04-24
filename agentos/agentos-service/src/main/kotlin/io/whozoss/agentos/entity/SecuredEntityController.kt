package io.whozoss.agentos.entity

import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.entity.Entity
import io.whozoss.agentos.user.UserService
import jakarta.validation.Valid
import mu.KLogging
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Abstract secured controller for entity endpoints with automatic permission checks.
 *
 * Extends EntityController by adding permission checks on all CRUD operations.
 * Permissions are verified via PermissionService before executing any operation.
 *
 * Security principles:
 * - 404 Not Found for READ operations when the user lacks permission (hides entity existence)
 * - 403 Forbidden for WRITE/DELETE operations when the user lacks permission
 * - Automatic list filtering to return only authorized entities
 * - Super-admin bypass support (handled by PermissionService)
 *
 * @param EntityType The domain entity type (must implement Entity)
 * @param ParentIdentifier The parent identifier type (typically UUID)
 * @param ResourceType The HTTP resource/DTO type returned and consumed by endpoints
 * @property service The entity service for CRUD operations
 * @property userService The user service to resolve the current user
 * @property permissionService The permission service for access checks
 */
abstract class SecuredEntityController<EntityType : Entity, ParentIdentifier, ResourceType>(
    service: EntityService<EntityType, ParentIdentifier>,
    protected val userService: UserService,
    protected val permissionService: PermissionService
) : EntityController<EntityType, ParentIdentifier, ResourceType>(service) {

    companion object : KLogging()

    /**
     * Returns the entity type for permission checks.
     * Must match the Neo4j label of the entity (e.g., "Case", "Namespace", "AgentConfig").
     *
     * @return The entity type name
     */
    abstract fun getEntityType(): String

    /**
     * GET /{id} — get a single entity by its ID.
     * Checks READ permission and returns 404 if the user lacks access.
     */
    override fun getById(@PathVariable id: UUID): ResourceType {
        val entity = service.findById(id)
            ?: throw ResourceNotFoundException("Entity not found: $id")

        val userId = userService.getCurrentUser().id.toString()
        if (!permissionService.hasPermission(userId, getEntityType(), id.toString(), Action.READ)) {
            // Return 404 to hide entity existence
            throw ResourceNotFoundException("Entity not found: $id")
        }

        logger.debug { "User $userId accessed ${getEntityType()} $id" }
        return toResource(entity)
    }

    /**
     * POST /by-ids — get multiple entities by their IDs.
     * Filters results to return only entities with READ permission.
     */
    override fun getByIds(@RequestBody ids: List<UUID>): List<ResourceType> {
        val userId = userService.getCurrentUser().id.toString()
        val entities = service.findByIds(ids)

        return entities
            .filter { entity ->
                val hasPermission = permissionService.hasPermission(
                    userId,
                    getEntityType(),
                    entity.id.toString(),
                    Action.READ
                )
                if (!hasPermission) {
                    logger.debug { "Filtered out ${getEntityType()} ${entity.id} for user $userId - no READ permission" }
                }
                hasPermission
            }
            .map { toResource(it) }
    }

    /**
     * GET /by-parentId/{parentId} — list all entities belonging to a parent.
     * Filters results to return only entities with READ permission.
     */
    override fun listByParent(@PathVariable parentId: ParentIdentifier): List<ResourceType> {
        val userId = userService.getCurrentUser().id.toString()
        val entities = service.findByParent(parentId)

        return entities
            .filter { entity ->
                val hasPermission = permissionService.hasPermission(
                    userId,
                    getEntityType(),
                    entity.id.toString(),
                    Action.READ
                )
                if (!hasPermission) {
                    logger.debug { "Filtered out ${getEntityType()} ${entity.id} for user $userId - no READ permission" }
                }
                hasPermission
            }
            .map { toResource(it) }
    }

    /**
     * POST — create a new entity.
     * Checks WRITE permission on the parent (if applicable).
     */
    override fun create(@Valid @RequestBody resource: ResourceType): ResourceType {
        val userId = userService.getCurrentUser().id.toString()
        val domainEntity = toDomain(resource)

        // For creation, permission is typically checked on the parent entity.
        // Subclasses can override checkCreatePermission for specific logic.
        checkCreatePermission(userId, domainEntity)

        val created = service.create(domainEntity)
        logger.info { "User $userId created ${getEntityType()} ${created.id}" }

        return toResource(created)
    }

    /**
     * PUT /{id} — update an existing entity.
     * Checks WRITE permission on the entity.
     */
    override fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody resource: ResourceType
    ): ResourceType {
        val userId = userService.getCurrentUser().id.toString()

        // Verify the entity exists
        service.findById(id)
            ?: throw ResourceNotFoundException("Entity not found: $id")

        // Check WRITE permission
        if (!permissionService.hasPermission(userId, getEntityType(), id.toString(), Action.WRITE)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied")
        }

        val updated = service.update(toDomain(resource))
        logger.info { "User $userId updated ${getEntityType()} $id" }

        return toResource(updated)
    }

    /**
     * DELETE /{id} — soft-delete a single entity.
     * Checks DELETE permission on the entity.
     */
    override fun delete(@PathVariable id: UUID) {
        val userId = userService.getCurrentUser().id.toString()

        // Verify the entity exists
        val exists = service.findById(id) != null
        if (!exists) {
            throw ResourceNotFoundException("Entity not found: $id")
        }

        // Check DELETE permission
        if (!permissionService.hasPermission(userId, getEntityType(), id.toString(), Action.DELETE)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied")
        }

        val deleted = service.delete(id)
        if (deleted) {
            logger.info { "User $userId deleted ${getEntityType()} $id" }
        }
    }

    /**
     * Checks permissions for entity creation.
     * By default, this method denies creation (fail-closed). Subclasses must override
     * with their own logic (e.g., check WRITE on the parent namespace).
     *
     * @param userId The ID of the user creating the entity
     * @param entity The entity to be created
     * @throws ResponseStatusException with status 403 if the user lacks permission
     */
    protected open fun checkCreatePermission(userId: String, entity: EntityType) {
        // Fail-closed: deny creation by default. Subclasses must explicitly override
        // with their own logic to allow creation (e.g., check WRITE on parent namespace).
        throw ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied")
    }
}
