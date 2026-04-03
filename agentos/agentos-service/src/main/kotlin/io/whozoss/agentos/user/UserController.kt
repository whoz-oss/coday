package io.whozoss.agentos.user

import io.swagger.v3.oas.annotations.Operation
import io.whozoss.agentos.security.SecurityService
import mu.KLogging
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
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
import org.springframework.http.HttpStatus.NOT_FOUND
import java.util.UUID

/**
 * REST API for managing Users.
 *
 * Exposes [UserResource] as the API contract rather than the domain [User] directly,
 * keeping the HTTP surface decoupled from the internal entity model.
 *
 * Endpoints:
 *   GET    /api/users           — list all users
 *   GET    /api/users/{id}      — get by ID
 *   POST   /api/users/by-ids    — get multiple by IDs
 *   POST   /api/users           — create
 *   PUT    /api/users/{id}      — update
 *   DELETE /api/users/{id}      — soft-delete
 *   GET    /api/users/me        — resolve the caller's own user record
 */
@RestController
@RequestMapping(
    "/api/users",
    produces = [MediaType.APPLICATION_JSON_VALUE],
)
class UserController(
    private val userService: UserService,
    private val securityService: SecurityService,
) {

    @GetMapping
    @ResponseStatus(HttpStatus.OK)
    fun listAll(): List<UserResource> {
        logger.info { "listing all users" }
        return userService.findAll().map { it.toResource() }
    }

    @GetMapping("/{id}")
    @ResponseStatus(HttpStatus.OK)
    fun getById(
        @PathVariable id: UUID,
    ): UserResource =
        userService.findById(id)?.toResource()
            ?: throw ResponseStatusException(NOT_FOUND, "User not found: $id")

    @PostMapping(
        "/by-ids",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
    )
    @ResponseStatus(HttpStatus.OK)
    fun getByIds(
        @RequestBody ids: List<UUID>,
    ): List<UserResource> = userService.findByIds(ids).map { it.toResource() }

    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @RequestBody resource: UserResource,
    ): UserResource {
        logger.info { "creating user: ${resource.email}" }
        return userService.create(resource.toUser()).toResource()
    }

    @PutMapping(
        "/{id}",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
    )
    @ResponseStatus(HttpStatus.OK)
    fun update(
        @PathVariable id: UUID,
        @RequestBody resource: UserResource,
    ): UserResource {
        userService.findById(id)
            ?: throw ResponseStatusException(NOT_FOUND, "User not found: $id")
        // Enforce the path id — the body id is ignored to prevent accidental mismatch.
        val user = resource.copy(id = id).toUser()
        return userService.update(user).toResource()
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @PathVariable id: UUID,
    ) {
        val deleted = userService.delete(id)
        if (!deleted) {
            throw ResponseStatusException(NOT_FOUND, "User not found: $id")
        }
    }

    /**
     * GET /api/users/me — return the user record for the current caller.
     *
     * Delegates to [SecurityService.resolveCurrentUser] so the resolution logic
     * (OS username in local mode, JWT email in auth mode) is centralised.
     */
    @GetMapping("/me")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Get the current user's profile")
    fun getMe(): UserResource {
        logger.info { "resolving current user" }
        return securityService.resolveCurrentUser().toResource()
    }

    companion object : KLogging()
}
