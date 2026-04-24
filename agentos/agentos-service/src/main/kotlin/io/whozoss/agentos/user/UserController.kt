package io.whozoss.agentos.user

import io.swagger.v3.oas.annotations.Operation
import io.whozoss.agentos.entity.EntityController
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.BlockingPermissionService
import io.whozoss.agentos.sdk.entity.EntityMetadata
import jakarta.validation.Valid
import mu.KLogging
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * REST API for managing Users.
 *
 * Extends [EntityController] with [UserResource] as the HTTP DTO, keeping the
 * [User] domain entity decoupled from the API contract.
 *
 * Standard CRUD endpoints (inherited):
 *   GET    /api/users/{id}
 *   POST   /api/users/by-ids
 *   GET    /api/users/by-parentId/{parentId}
 *   POST   /api/users
 *   PUT    /api/users/{id}
 *   DELETE /api/users/{id}
 *
 * Additional endpoints:
 *   GET    /api/users    — list all users
 *   GET    /api/users/me — resolve the caller's own user record
 */
@RestController
@RequestMapping(
    "/api/users",
    produces = [MediaType.APPLICATION_JSON_VALUE],
)
class UserController(
    private val userService: UserService,
    private val permissionService: BlockingPermissionService,
) : EntityController<User, String, UserResource>(userService) {

    // -------------------------------------------------------------------------
    // Mapping between domain entity and HTTP resource
    // -------------------------------------------------------------------------

    override fun toResource(entity: User): UserResource =
        UserResource(
            id = entity.metadata.id,
            email = entity.email.ifBlank { null },  // blank = no email known (local mode)
            externalId = entity.externalId,
            firstname = entity.firstname,
            lastname = entity.lastname,
            bio = entity.bio,
            isAdmin = entity.isAdmin,
        )

    override fun toDomain(resource: UserResource): User =
        User(
            metadata = EntityMetadata(id = resource.id ?: UUID.randomUUID()),
            externalId = "",           // server-managed — never sourced from the request body
            email = resource.email ?: "",
            firstname = resource.firstname,
            lastname = resource.lastname,
            bio = resource.bio,
            isAdmin = resource.isAdmin,
        )

    /**
     * PUT /{id} — update an existing user.
     *
     * Overrides [EntityController.update] to preserve [User.externalId] from the
     * persisted entity. The externalId is an IdP key that is set once at creation
     * and must never be overwritten by a client-supplied value.
     *
     * Only super-admins or the user themselves can update profiles.
     */
    override fun update(
        id: UUID,
        resource: UserResource,
    ): UserResource {
        val currentUser = userService.getCurrentUser()
        val isOwnProfile = currentUser.id == id

        if (!isOwnProfile && !currentUser.isAdmin) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied")
        }

        val existing =
            userService.findById(id)
                ?: throw ResourceNotFoundException("Entity not found: $id")
        val updated =
            toDomain(resource).copy(
                metadata = existing.metadata,
                externalId = existing.externalId,
                isAdmin = existing.isAdmin,  // preserve isAdmin - only system/bootstrap can set this
            )

        logger.info { "User ${currentUser.id} updated user profile $id" }
        return toResource(userService.update(updated))
    }

    // -------------------------------------------------------------------------
    // Override base CRUD methods to add permission checks
    // -------------------------------------------------------------------------

    /**
     * GET /{id} — get a user by ID.
     * Only super-admins or the user themselves can access.
     */
    override fun getById(@PathVariable id: UUID): UserResource {
        val currentUser = userService.getCurrentUser()
        val isOwnProfile = currentUser.id == id

        if (!isOwnProfile && !currentUser.isAdmin) {
            throw ResourceNotFoundException("Entity not found: $id")
        }

        val entity = service.findById(id)
            ?: throw ResourceNotFoundException("Entity not found: $id")

        logger.debug { "User ${currentUser.id} accessed user profile $id" }
        return toResource(entity)
    }

    /**
     * POST /by-ids — get multiple users by IDs.
     * Only super-admins can access.
     */
    override fun getByIds(@RequestBody ids: List<UUID>): List<UserResource> {
        val currentUser = userService.getCurrentUser()

        if (!currentUser.isAdmin) {
            logger.warn { "Non-admin user ${currentUser.id} attempted to batch fetch users" }
            return emptyList()
        }

        return service.findByIds(ids).map { toResource(it) }
    }

    /**
     * POST — create a new user.
     * Only super-admins can create users (normal users are auto-created via auth).
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    override fun create(@Valid @RequestBody resource: UserResource): UserResource {
        val currentUser = userService.getCurrentUser()

        if (!currentUser.isAdmin) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied")
        }

        val newUser = toDomain(resource).copy(isAdmin = false)  // Never allow isAdmin from request body
        val created = service.create(newUser)
        logger.info { "Super-admin ${currentUser.id} created user ${created.id}" }
        return toResource(created)
    }

    /**
     * DELETE /{id} — delete a user.
     * Only super-admins can delete users.
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    override fun delete(@PathVariable id: UUID) {
        val currentUser = userService.getCurrentUser()

        if (!currentUser.isAdmin) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied")
        }

        val exists = service.findById(id) != null
        if (!exists) {
            throw ResourceNotFoundException("Entity not found: $id")
        }

        val deleted = service.delete(id)
        if (deleted) {
            logger.info { "Super-admin ${currentUser.id} deleted user $id" }
        }
    }

    // -------------------------------------------------------------------------
    // Additional endpoints
    // -------------------------------------------------------------------------

    /**
     * GET /api/users — list all users.
     * Only super-admins can list all users.
     */
    @GetMapping
    @ResponseStatus(HttpStatus.OK)
    fun listAll(): List<UserResource> {
        val currentUser = userService.getCurrentUser()

        if (!currentUser.isAdmin) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied")
        }

        logger.info { "Super-admin ${currentUser.id} listing all users" }
        return userService.findAll().map { toResource(it) }
    }

    /**
     * GET /api/users/me — return the user record for the current caller.
     *
     * Identity resolution is fully encapsulated in [UserService.getCurrentUser].
     */
    @GetMapping("/me")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Get the current user's profile")
    fun getMe(): UserResource {
        logger.info { "resolving current user" }
        return toResource(userService.getCurrentUser())
    }

    companion object : KLogging()
}
