package io.whozoss.agentos.user

import io.swagger.v3.oas.annotations.Operation
import io.whozoss.agentos.entity.EntityController
import io.whozoss.agentos.sdk.entity.EntityMetadata
import mu.KLogging
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
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
) : EntityController<User, String, UserResource>(userService) {

    // -------------------------------------------------------------------------
    // Mapping between domain entity and HTTP resource
    // -------------------------------------------------------------------------

    override fun toResource(entity: User): UserResource =
        UserResource(
            id = entity.metadata.id,
            email = entity.email,
            externalId = entity.externalId,
            firstname = entity.firstname,
            lastname = entity.lastname,
            bio = entity.bio,
        )

    override fun toDomain(resource: UserResource): User =
        User(
            metadata = EntityMetadata(id = resource.id ?: UUID.randomUUID()),
            externalId = resource.email,
            email = resource.email,
            firstname = resource.firstname,
            lastname = resource.lastname,
            bio = resource.bio,
        )

    // -------------------------------------------------------------------------
    // Additional endpoints
    // -------------------------------------------------------------------------

    /**
     * GET /api/users — list all users.
     */
    @GetMapping
    @ResponseStatus(HttpStatus.OK)
    fun listAll(): List<UserResource> {
        logger.info { "listing all users" }
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
