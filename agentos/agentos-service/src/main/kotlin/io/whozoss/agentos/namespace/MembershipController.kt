package io.whozoss.agentos.namespace

import io.whozoss.agentos.auth.AccessDeniedException
import io.whozoss.agentos.auth.AuthorizationService
import io.whozoss.agentos.auth.RoleRepository
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.sdk.auth.NamespaceRole
import io.whozoss.agentos.user.UserService
import jakarta.validation.Valid
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

/**
 * REST API for managing namespace membership (role assignments).
 *
 * This is a **standalone** `@RestController` — it does NOT extend `EntityController`
 * because memberships are graph relationships (MEMBER_OF) with properties, not
 * `Entity` objects with `EntityMetadata`.
 *
 * Endpoints:
 *   POST   /api/namespaces/{nsId}/members         — assign a role
 *   GET    /api/namespaces/{nsId}/members          — list members
 *   PUT    /api/namespaces/{nsId}/members/{userId} — update a role
 *   DELETE /api/namespaces/{nsId}/members/{userId} — revoke access
 *
 * Every endpoint enforces ADMIN-level access via [AuthorizationService.requireNamespaceAccess],
 * which throws [io.whozoss.agentos.auth.AccessDeniedException] on failure — handled
 * globally by [io.whozoss.agentos.auth.AccessDeniedExceptionHandler].
 */
@RestController
@RequestMapping(
    "/api/namespaces/{nsId}/members",
    produces = [MediaType.APPLICATION_JSON_VALUE],
)
class MembershipController(
    private val roleRepository: RoleRepository,
    private val authorizationService: AuthorizationService,
    private val userService: UserService,
) {

    /**
     * POST /api/namespaces/{nsId}/members — assign a role to a user.
     *
     * Requires ADMIN role in the namespace. The [resource] body must contain
     * a valid `userId` and `role` (one of the [NamespaceRole] enum values).
     *
     * Returns 201 Created with the created membership details.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun assignRole(
        @PathVariable nsId: String,
        @Valid @RequestBody resource: MembershipResource,
    ): MembershipResource {
        val currentUser = userService.getCurrentUser()
        val currentUserId = currentUser.id.toString()
        authorizationService.requireNamespaceAccess(currentUserId, nsId, NamespaceRole.ADMIN)

        val targetUserId = resource.userId
            ?: throw IllegalArgumentException("userId is required")
        val roleString = resource.role
            ?: throw IllegalArgumentException("role is required")
        val namespaceRole = parseRole(roleString)

        enforceRoleCeiling(currentUserId, nsId, namespaceRole)

        logger.info { "assigning role $namespaceRole to user $targetUserId in namespace $nsId" }
        roleRepository.assignNamespaceRole(targetUserId, nsId, namespaceRole, currentUserId)

        return MembershipResource(
            userId = targetUserId,
            role = namespaceRole.name,
        )
    }

    /**
     * GET /api/namespaces/{nsId}/members — list all members of a namespace.
     *
     * Requires ADMIN role in the namespace.
     * Returns 200 OK with a list of all members and their roles.
     */
    @GetMapping
    @ResponseStatus(HttpStatus.OK)
    fun listMembers(
        @PathVariable nsId: String,
    ): List<MembershipResource> {
        val currentUser = userService.getCurrentUser()
        authorizationService.requireNamespaceAccess(currentUser.id.toString(), nsId, NamespaceRole.ADMIN)

        logger.info { "listing members of namespace $nsId" }
        return roleRepository.findMembersOfNamespace(nsId).map { info ->
            MembershipResource(
                userId = info.userId,
                role = info.role.name,
                grantedAt = info.grantedAt.toString(),
                grantedBy = info.grantedBy,
            )
        }
    }

    /**
     * PUT /api/namespaces/{nsId}/members/{userId} — update a user's role.
     *
     * Requires ADMIN role in the namespace. Enforces the last-OWNER protection:
     * demoting the sole OWNER is rejected with 400 Bad Request.
     *
     * Returns 200 OK with the updated membership details.
     */
    @PutMapping("/{userId}")
    @ResponseStatus(HttpStatus.OK)
    fun updateRole(
        @PathVariable nsId: String,
        @PathVariable userId: String,
        @Valid @RequestBody resource: MembershipResource,
    ): MembershipResource {
        val currentUser = userService.getCurrentUser()
        val currentUserId = currentUser.id.toString()
        authorizationService.requireNamespaceAccess(currentUserId, nsId, NamespaceRole.ADMIN)

        val roleString = resource.role
            ?: throw IllegalArgumentException("role is required")
        val newRole = parseRole(roleString)

        roleRepository.findNamespaceRole(userId, nsId)
            ?: throw ResourceNotFoundException("User $userId has no role in namespace $nsId")

        protectLastOwner(userId, nsId, newRole)
        enforceRoleCeiling(currentUserId, nsId, newRole)

        logger.info { "updating role of user $userId to $newRole in namespace $nsId" }
        roleRepository.assignNamespaceRole(userId, nsId, newRole, currentUserId)

        return MembershipResource(
            userId = userId,
            role = newRole.name,
        )
    }

    /**
     * DELETE /api/namespaces/{nsId}/members/{userId} — revoke a user's access.
     *
     * Requires ADMIN role in the namespace. Enforces the last-OWNER protection:
     * revoking the sole OWNER is rejected with 400 Bad Request.
     *
     * Returns 204 No Content on success.
     */
    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun revokeMember(
        @PathVariable nsId: String,
        @PathVariable userId: String,
    ) {
        val currentUser = userService.getCurrentUser()
        authorizationService.requireNamespaceAccess(currentUser.id.toString(), nsId, NamespaceRole.ADMIN)

        val currentRole = roleRepository.findNamespaceRole(userId, nsId)
            ?: throw ResourceNotFoundException("User $userId has no role in namespace $nsId")
        when {
            currentRole == NamespaceRole.OWNER && roleRepository.countOwnersInNamespace(nsId) <= 1 ->
                throw IllegalArgumentException("Cannot remove the last OWNER of a namespace")
        }

        logger.info { "revoking membership of user $userId in namespace $nsId" }
        roleRepository.removeNamespaceRole(userId, nsId)
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Parse a role string into a [NamespaceRole] enum value.
     * Throws [IllegalArgumentException] (→ 400) if the role is not valid.
     */
    private fun parseRole(role: String): NamespaceRole =
        try {
            NamespaceRole.valueOf(role.uppercase())
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException(
                "Invalid role: $role. Valid roles: ${NamespaceRole.entries.joinToString()}",
            )
        }

    /**
     * Prevent demoting the last OWNER of a namespace.
     * Called before updateRole to ensure the namespace always has at least one OWNER.
     */
    private fun protectLastOwner(userId: String, nsId: String, newRole: NamespaceRole) {
        val currentRole = roleRepository.findNamespaceRole(userId, nsId)
        when {
            currentRole == NamespaceRole.OWNER && newRole != NamespaceRole.OWNER &&
                roleRepository.countOwnersInNamespace(nsId) <= 1 ->
                throw IllegalArgumentException("Cannot demote the last OWNER of a namespace")
        }
    }

    /**
     * Enforce that a caller cannot assign a role higher than their own (P-6 escalation fix).
     * Only OWNER or isRoot can assign OWNER. An ADMIN cannot promote to OWNER.
     */
    private fun enforceRoleCeiling(currentUserId: String, nsId: String, assignedRole: NamespaceRole) {
        when {
            authorizationService.isRoot(currentUserId) -> return
        }
        val callerRole = roleRepository.findNamespaceRole(currentUserId, nsId)
            ?: throw AccessDeniedException(
                reason = "No role in this namespace",
                requiredRole = "ADMIN",
            )
        when {
            !callerRole.satisfies(assignedRole) -> throw AccessDeniedException(
                reason = "Cannot assign role ${assignedRole.name} — your role ${callerRole.name} is insufficient",
                requiredRole = assignedRole.name,
            )
        }
    }

    companion object : KLogging()
}
