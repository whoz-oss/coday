package io.whozoss.agentos.namespace

import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.PermissionRelation
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.user.UserService
import mu.KLogging
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Dedicated endpoints for managing namespace ADMIN permissions (Story 2.2).
 *
 * Kept separate from [NamespaceController] so CRUD and permission-management
 * concerns do not mix. Both controllers share the `/api/namespaces` prefix.
 *
 * Endpoints:
 *   PUT    /api/namespaces/{namespaceId}/admins/{targetUserId}
 *   DELETE /api/namespaces/{namespaceId}/admins/{targetUserId}
 *
 * Authorization: the caller must hold the ADMIN relationship on the namespace
 * (either directly or via the super-admin bypass handled by [PermissionService]).
 *
 * Idempotency: the Neo4j MERGE + DELETE primitives are naturally idempotent,
 * so repeated PUTs/DELETEs are safe and return the same status code.
 *
 * Note: MEMBER management belongs to Story 2.3. Audit-trail properties
 * (grantedBy / grantedAt) on the relationship are intentionally deferred.
 */
@RestController
@RequestMapping(
    "/api/namespaces",
    produces = [MediaType.APPLICATION_JSON_VALUE],
)
class NamespacePermissionEndpoints(
    private val namespaceService: NamespaceService,
    private val userService: UserService,
    private val permissionService: PermissionService,
) {

    /**
     * PUT — grant ADMIN on the namespace to [targetUserId].
     */
    @PutMapping("/{namespaceId}/admins/{targetUserId}")
    @ResponseStatus(HttpStatus.OK)
    fun grantAdmin(
        @PathVariable namespaceId: UUID,
        @PathVariable targetUserId: UUID,
    ) {
        val currentUserId = requireNamespaceAdmin(namespaceId, targetUserId)

        permissionService.grantPermission(
            targetUserId.toString(),
            ENTITY_TYPE,
            namespaceId.toString(),
            PermissionRelation.ADMIN,
        )
        logger.info {
            "User $currentUserId granted ADMIN on namespace $namespaceId to user $targetUserId"
        }
    }

    /**
     * DELETE — revoke the ADMIN relationship between [targetUserId] and the namespace.
     */
    @DeleteMapping("/{namespaceId}/admins/{targetUserId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun revokeAdmin(
        @PathVariable namespaceId: UUID,
        @PathVariable targetUserId: UUID,
    ) {
        val currentUserId = requireNamespaceAdmin(namespaceId, targetUserId)

        permissionService.revokePermission(
            targetUserId.toString(),
            ENTITY_TYPE,
            namespaceId.toString(),
            PermissionRelation.ADMIN,
        )
        logger.info {
            "User $currentUserId revoked ADMIN on namespace $namespaceId from user $targetUserId"
        }
    }

    /**
     * PUT — grant MEMBER on the namespace to [targetUserId] (Story 2.3).
     *
     * Authorization is identical to [grantAdmin]: the caller must hold the ADMIN
     * relationship on the namespace (directly or via super-admin bypass). MEMBER
     * gives the target user READ access to the namespace and transitive READ on
     * its child entities.
     */
    @PutMapping("/{namespaceId}/members/{targetUserId}")
    @ResponseStatus(HttpStatus.OK)
    fun grantMember(
        @PathVariable namespaceId: UUID,
        @PathVariable targetUserId: UUID,
    ) {
        val currentUserId = requireNamespaceAdmin(namespaceId, targetUserId)

        permissionService.grantPermission(
            targetUserId.toString(),
            ENTITY_TYPE,
            namespaceId.toString(),
            PermissionRelation.MEMBER,
        )
        logger.info {
            "User $currentUserId granted MEMBER on namespace $namespaceId to user $targetUserId"
        }
    }

    /**
     * DELETE — revoke the MEMBER relationship between [targetUserId] and the namespace (Story 2.3).
     *
     * Only removes the [:MEMBER] relationship. Any [:ADMIN] relationship the same
     * user holds on the same namespace is left untouched — users who are both ADMIN
     * and MEMBER keep their higher privilege after this call.
     */
    @DeleteMapping("/{namespaceId}/members/{targetUserId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun revokeMember(
        @PathVariable namespaceId: UUID,
        @PathVariable targetUserId: UUID,
    ) {
        val currentUserId = requireNamespaceAdmin(namespaceId, targetUserId)

        permissionService.revokePermission(
            targetUserId.toString(),
            ENTITY_TYPE,
            namespaceId.toString(),
            PermissionRelation.MEMBER,
        )
        logger.info {
            "User $currentUserId revoked MEMBER on namespace $namespaceId from user $targetUserId"
        }
    }

    /**
     * GET — list all users with a direct `[:ADMIN]` or `[:MEMBER]` relation on the
     * namespace (Story 2.5).
     *
     * Authorization: caller must have READ on the namespace (MEMBER or ADMIN, or
     * super-admin via bypass). Callers without access receive 404 to hide the
     * namespace's existence.
     *
     * Response: each user appears at most once. Users with both ADMIN and MEMBER
     * relations are returned with the higher role ("ADMIN"). Super-admins without
     * a direct relation are NOT listed — this endpoint reflects namespace-level
     * grants, not system-level roles.
     *
     * Permission relations pointing to users that no longer exist (orphans from a
     * user deletion) are silently filtered out; a WARN log records the count.
     */
    @GetMapping("/{namespaceId}/users")
    @ResponseStatus(HttpStatus.OK)
    fun listNamespaceUsers(@PathVariable namespaceId: UUID): List<NamespaceUserListItem> {
        requireNamespaceRead(namespaceId)

        val namespaceIdString = namespaceId.toString()
        val adminUserIds = permissionService
            .listUsersWithPermission(ENTITY_TYPE, namespaceIdString, PermissionRelation.ADMIN)
            .toSet()
        val memberUserIds = permissionService
            .listUsersWithPermission(ENTITY_TYPE, namespaceIdString, PermissionRelation.MEMBER)
            .toSet()
        val allUserIds = adminUserIds + memberUserIds
        if (allUserIds.isEmpty()) return emptyList()

        // Defensive: filter malformed UUID strings (should never happen in practice
        // since IDs come from Neo4j; UUID.fromString throws otherwise).
        // Log each malformed id as WARN so data corruption surfaces in ops.
        val uuids = allUserIds.mapNotNull { raw ->
            runCatching { UUID.fromString(raw) }.getOrNull()
                ?: run {
                    logger.warn {
                        "Dropping malformed user id from permission listing on namespace $namespaceId: '$raw'"
                    }
                    null
                }
        }
        val users = userService.findByIds(uuids)

        val missingCount = uuids.size - users.size
        if (missingCount > 0) {
            logger.warn {
                "Namespace $namespaceId has $missingCount permission relation(s) pointing to " +
                    "non-existent users — filtered from response"
            }
        }

        return users.map { user ->
            val userIdString = user.metadata.id.toString()
            val role = if (userIdString in adminUserIds) ADMIN else MEMBER
            NamespaceUserListItem(
                id = user.metadata.id,
                externalId = user.externalId,
                email = user.email,
                firstname = user.firstname,
                lastname = user.lastname,
                role = role,
            )
        }
    }

    /**
     * Validate that the namespace exists and the caller has READ on it.
     *
     * 404 is returned both when the namespace does not exist and when the caller
     * lacks READ — callers without access should not be able to distinguish the
     * two cases.
     */
    private fun requireNamespaceRead(namespaceId: UUID): String {
        namespaceService.findById(namespaceId)
            ?: throw ResourceNotFoundException("Namespace not found: $namespaceId")

        val currentUserId = userService.getCurrentUser().id.toString()
        if (!permissionService.hasPermission(currentUserId, ENTITY_TYPE, namespaceId.toString(), Action.READ)) {
            throw ResourceNotFoundException("Namespace not found: $namespaceId")
        }
        return currentUserId
    }

    /**
     * Validate that the namespace and target user exist, and that the caller has
     * ADMIN rights on the namespace. Returns the caller's userId for logging.
     *
     * Order of checks (aligned with the "always 404 if no READ" rule in the
     * architecture doc to avoid leaking namespace existence to non-members):
     *   1. Namespace existence → 404 if missing.
     *   2. Caller's READ permission on the namespace → 404 if denied
     *      (hides the existence from callers with no access at all).
     *   3. Target user existence → 404 if missing (caller has READ so disclosure is fine).
     *   4. Caller's WRITE permission on the namespace → 403 if denied
     *      (caller has READ and knows the namespace exists).
     */
    private fun requireNamespaceAdmin(namespaceId: UUID, targetUserId: UUID): String {
        namespaceService.findById(namespaceId)
            ?: throw ResourceNotFoundException("Namespace not found: $namespaceId")

        val currentUserId = userService.getCurrentUser().id.toString()
        val namespaceIdString = namespaceId.toString()

        if (!permissionService.hasPermission(currentUserId, ENTITY_TYPE, namespaceIdString, Action.READ)) {
            throw ResourceNotFoundException("Namespace not found: $namespaceId")
        }

        userService.findById(targetUserId)
            ?: throw ResourceNotFoundException("User not found: $targetUserId")

        if (!permissionService.hasPermission(currentUserId, ENTITY_TYPE, namespaceIdString, Action.WRITE)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Namespace ADMIN role required")
        }
        return currentUserId
    }

    companion object : KLogging() {
        private const val ENTITY_TYPE = "Namespace"
        private const val ADMIN = "ADMIN"
        private const val MEMBER = "MEMBER"
    }
}
