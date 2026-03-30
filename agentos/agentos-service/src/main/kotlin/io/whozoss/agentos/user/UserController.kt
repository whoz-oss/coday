package io.whozoss.agentos.user

import io.swagger.v3.oas.annotations.Operation
import io.whozoss.agentos.entity.EntityController
import mu.KLogging
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

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
 *   GET    /api/users/me     — resolve the caller's own user record
 *
 * Identity resolution for [getMe] uses the [X_EXTERNAL_ID_HEADER] header.
 * The upstream proxy (Node.js server.ts) is responsible for populating this
 * header from the authenticated session before forwarding the request.
 */
@RestController
@RequestMapping(
    "/api/users",
    produces = [MediaType.APPLICATION_JSON_VALUE],
)
class UserController(
    private val userService: UserService,
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
     * GET /api/users/me — return the user record matching the caller's external identity.
     *
     * Reads the external ID from [X_EXTERNAL_ID_HEADER]. Returns 404 when no user
     * record exists for that identity — the client should then call POST /api/users
     * to create one.
     */
    @GetMapping("/me")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Get the current user's profile by external identity header")
    fun getMe(
        @RequestHeader(X_EXTERNAL_ID_HEADER, required = false) externalId: String?,
    ): User {
        if (externalId.isNullOrBlank()) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Missing header: $X_EXTERNAL_ID_HEADER",
            )
        }
        return userService.findByExternalId(externalId)
            ?: throw ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "No user found for external id: $externalId",
            )
    }

    companion object : KLogging() {
        /**
         * HTTP header carrying the caller's external identity (e.g. email).
         * Populated by the upstream proxy from the authenticated session;
         * never set by the browser directly.
         */
        const val X_EXTERNAL_ID_HEADER = "X-AgentOS-User-ExternalId"
    }
}
