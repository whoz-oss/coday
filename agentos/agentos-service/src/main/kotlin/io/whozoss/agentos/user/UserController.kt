package io.whozoss.agentos.user

import io.swagger.v3.oas.annotations.Operation
import io.whozoss.agentos.entity.EntityController
import io.whozoss.agentos.entity.GetByIdsRequest
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.userGroup.UserGroupService
import io.whozoss.agentos.userGroup.UserGroupSummaryResource
import io.whozoss.agentos.userGroup.toResource
import jakarta.validation.Valid
import mu.KLogging
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.context.SecurityContextHolder
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
 * The `update` method preserves [User.externalId] always (server-managed IdP key).
 * [User.isAdmin] is preserved only in self-edit mode (caller == target). When a
 * super-admin updates another user, [User.isAdmin] from the request body is honored.
 * Self-rule guarantees no API call can ever leave the DB with zero super-admins.
 */
@RestController
@RequestMapping(
    "/api/users",
    produces = [MediaType.APPLICATION_JSON_VALUE],
)
class UserController(
    userService: UserService,
    permissionService: PermissionService,
    private val userGroupService: UserGroupService,
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
            externalId = "", // server-managed — never sourced from the request body
            email = resource.email ?: "",
            firstname = resource.firstname,
            lastname = resource.lastname,
            bio = resource.bio,
            isAdmin = resource.isAdmin,
        )

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN') or #id.toString() == authentication.name")
    override fun getById(
        @PathVariable id: UUID,
    ): UserResource = super.getById(id)

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
    override fun getByIds(
        @RequestBody request: GetByIdsRequest,
    ): List<UserResource> {
        val ids = request.ids
        if (ids.size > EntityController.MAX_BATCH_SIZE) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Batch size ${ids.size} exceeds maximum of ${EntityController.MAX_BATCH_SIZE}",
            )
        }
        if (ids.isEmpty()) return emptyList()
        return userService.findByIds(ids, request.withRemoved).map(::toResource)
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    override fun create(
        @Valid @RequestBody resource: UserResource,
    ): UserResource {
        val created = service.create(toDomain(resource))
        logger.info { "Super-admin created user ${created.id} (isAdmin=${created.isAdmin})" }
        return toResource(created)
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN') or #id.toString() == authentication.name")
    override fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody resource: UserResource,
    ): UserResource {
        val existing =
            userService.findById(id)
                ?: throw ResourceNotFoundException("Entity not found: $id")
        // Self-rule: a user can never change their own isAdmin via the API.
        // Guarantees the DB never reaches 0 super-admins via API (a super-admin
        // needs ANOTHER super-admin to be demoted). auth.name is the User's UUID
        // as a String (cf. AgentOsAuthentication.getName()).
        //
        // PUT is replace-semantic: clients are expected to send the full state.
        // A partial body that omits isAdmin will reset it to `false` (Kotlin default
        // on the DTO) — same convention as other resource DTOs in this API.
        val callerName = SecurityContextHolder.getContext().authentication?.name
        val isSelfEdit = callerName != null && id.toString() == callerName
        val newIsAdmin = if (isSelfEdit) existing.isAdmin else resource.isAdmin
        val updated =
            toDomain(resource).copy(
                metadata = existing.metadata,
                externalId = existing.externalId, // server-managed (IdP key)
                isAdmin = newIsAdmin, // self-rule applied above
            )
        val persisted = userService.update(updated)
        logger.info {
            "User profile $id updated by caller=$callerName " +
                "(isAdmin: ${existing.isAdmin} -> ${persisted.isAdmin}, selfEdit=$isSelfEdit)"
        }
        return toResource(persisted)
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    override fun delete(
        @PathVariable id: UUID,
    ) {
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

    /**
     * POST /api/users/by-external-ids — look up users by a list of external identity-provider keys.
     *
     * External ids that match no active user are silently omitted from the result.
     * The order of results is not guaranteed to match the order of the input ids.
     * Super-admin only — same authorization policy as [listAll] and [getByIds].
     */
    @PostMapping("/by-external-ids")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    fun listByExternalIds(
        @RequestBody externalIds: List<String>,
    ): List<UserResource> {
        if (externalIds.isEmpty()) return emptyList()
        return userService.findByExternalIds(externalIds.toSet()).map(::toResource)
    }

    /** POST /api/users/groups-by-external-ids — return groups per user, filtered to groups visible to the caller. */
    @PostMapping("/groups-by-external-ids")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("isAuthenticated()")
    fun getGroupsByExternalIds(
        @RequestBody externalIds: List<String>,
    ): Map<String, List<UserGroupSummaryResource>> {
        if (externalIds.isEmpty()) return emptyMap()
        val currentUser = userService.getCurrentUser()
        return userGroupService
            .findGroupsByUserExternalIdsVisibleToUser(externalIds, currentUser)
            .mapValues { (_, groups) -> groups.map { it.toResource() } }
    }

    companion object : KLogging()
}
