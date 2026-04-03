package io.whozoss.agentos.user

import io.swagger.v3.oas.annotations.Operation
import io.whozoss.agentos.entity.EntityController
import io.whozoss.agentos.security.SecurityService
import mu.KLogging
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * REST API for managing Users.
 *
 * Extends [EntityController] for standard CRUD:
 *   GET    /api/users/{id}
 *   POST   /api/users
 *   PUT    /api/users/{id}
 *   DELETE /api/users/{id}
 *
 * Additional endpoints:
 *   GET    /api/users        — list all users
 *   GET    /api/users/me     — resolve the caller's own user record via [SecurityService]
 */
@RestController
@RequestMapping(
    "/api/users",
    produces = [MediaType.APPLICATION_JSON_VALUE],
)
class UserController(
    private val userService: UserService,
    private val securityService: SecurityService,
) : EntityController<User, String>(userService) {

    /**
     * GET /api/users — list all users.
     */
    @GetMapping
    @ResponseStatus(HttpStatus.OK)
    fun listAll(): List<User> {
        logger.info { "listing all users" }
        return userService.findAll()
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
    fun getMe(): User {
        logger.info { "resolving current user" }
        return securityService.resolveCurrentUser()
    }

    companion object : KLogging()
}
