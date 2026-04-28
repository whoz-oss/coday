package io.whozoss.agentos.user

import io.swagger.v3.oas.annotations.Operation
import io.whozoss.agentos.entity.EntityController
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.entity.EntityMetadata
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
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * REST API for managing Users.
 *
 * Authorization declared via `@PreAuthorize`:
 * - Self-or-super-admin (read/update own profile): `hasRole('SUPER_ADMIN') or #id.toString() == authentication.name`
 *   → the SpEL `#id.toString()` is critical — `#id` is a UUID, `authentication.name` is a String,
 *   so a raw `==` compares two different types and always returns false.
 * - Super-admin only (list, create, delete, batch fetch): `hasRole('SUPER_ADMIN')`
 * - `/me`: any authenticated user — resolves their own record
 *
 * The `update` override preserves [User.externalId] and [User.isAdmin] from the persisted
 * entity (mass-assignment guard — these are server-managed fields).
 */
@RestController
@RequestMapping(
    "/api/users",
    produces = [MediaType.APPLICATION_JSON_VALUE],
)
class UserController(
    userService: UserService,
    permissionService: PermissionService,
) : EntityController<User, String, UserResource>(userService, userService, permissionService) {

    /**
     * Required by [EntityController] but not used here — User access is gated by
     * `hasRole('SUPER_ADMIN')` (or self-or-admin), NOT by the namespace permission
     * graph. [getByIds] is overridden below to bypass [permissionService].
     */
    override val entityType = EntityType.USER

    override fun toResource(entity: User): UserResource =
        UserResource(
            id = entity.metadata.id,
            email = entity.email.ifBlank { null },
            externalId = entity.externalId,
            firstname = entity.firstname,
            lastname = entity.lastname,
            bio = entity.bio,
            isAdmin = entity.isAdmin,
        )

    override fun toDomain(resource: UserResource): User =
        User(
            metadata = EntityMetadata(id = resource.id ?: UUID.randomUUID()),
            externalId = "",  // server-managed — never sourced from the request body
            email = resource.email ?: "",
            firstname = resource.firstname,
            lastname = resource.lastname,
            bio = resource.bio,
            isAdmin = resource.isAdmin,
        )

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN') or #id.toString() == authentication.name")
    override fun getById(@PathVariable id: UUID): UserResource = super.getById(id)

    /**
     * POST /by-ids — super-admin batch fetch.
     *
     * Overrides the base [EntityController.getByIds] (story 5-4) because the User
     * entity is NOT permission-graph governed. Access is `hasRole('SUPER_ADMIN')` only,
     * so the batch authorization path (`filterVisibleIds` → namespace transitive resolution)
     * is irrelevant here. We delegate directly to the service after applying the same
     * empty short-circuit and size cap as the base (defense-in-depth even for super-admin
     * — closes adversarial review P3 of story 5-4).
     */
    @PostMapping("/by-ids")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    override fun getByIds(@RequestBody ids: List<UUID>): List<UserResource> {
        if (ids.size > EntityController.MAX_BATCH_SIZE) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Batch size ${ids.size} exceeds maximum of ${EntityController.MAX_BATCH_SIZE}",
            )
        }
        if (ids.isEmpty()) return emptyList()
        return userService.findByIds(ids).map(::toResource)
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    override fun create(@Valid @RequestBody resource: UserResource): UserResource {
        // Never allow isAdmin promotion from request body — only system/bootstrap can grant super-admin.
        val newUser = toDomain(resource).copy(isAdmin = false)
        val created = service.create(newUser)
        logger.info { "Super-admin created user ${created.id}" }
        return toResource(created)
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN') or #id.toString() == authentication.name")
    override fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody resource: UserResource,
    ): UserResource {
        val existing = userService.findById(id)
            ?: throw ResourceNotFoundException("Entity not found: $id")
        val updated = toDomain(resource).copy(
            metadata = existing.metadata,
            externalId = existing.externalId,        // server-managed (IdP key)
            isAdmin = existing.isAdmin,              // server-managed (only system/bootstrap can set)
        )
        logger.info { "User profile $id updated" }
        return toResource(userService.update(updated))
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    override fun delete(@PathVariable id: UUID) {
        val exists = service.findById(id) != null
        if (!exists) {
            throw ResourceNotFoundException("Entity not found: $id")
        }
        service.delete(id)
        logger.info { "Super-admin deleted user $id" }
    }

    /** GET /api/users — list all users. Super-admin only. */
    @GetMapping
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    fun listAll(): List<UserResource> {
        logger.info { "Super-admin listing all users" }
        return userService.findAll().map { toResource(it) }
    }

    /** GET /api/users/me — return the current caller's user record. */
    @GetMapping("/me")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get the current user's profile")
    fun getMe(): UserResource = toResource(userService.getCurrentUser())

    companion object : KLogging()
}
