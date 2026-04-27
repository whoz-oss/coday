package io.whozoss.agentos.namespace

import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.permissions.PermissionRelation
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.security.declarative.HideOnAccessDenied
import io.whozoss.agentos.user.UserService
import mu.KLogging
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Dedicated endpoints for managing namespace ADMIN/MEMBER permissions (/2.3).
 *
 * Authorization:
 * - grant/revoke (ADMIN/MEMBER): namespace WRITE — caller must be namespace ADMIN
 * - listNamespaceUsers: namespace READ + `@HideOnAccessDenied` — 404 hides existence
 *
 * Idempotency: the Neo4j MERGE + DELETE primitives are naturally idempotent,
 * so repeated PUTs/DELETEs are safe.
 *
 * Note: MEMBER management. Audit-trail properties (grantedBy / grantedAt)
 * on the relationship are intentionally deferred.
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

    @PutMapping("/{namespaceId}/admins/{targetUserId}")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasPermission(#namespaceId, 'Namespace', 'WRITE')")
    fun grantAdmin(
        @PathVariable namespaceId: UUID,
        @PathVariable targetUserId: UUID,
    ) {
        requireExists(namespaceId, targetUserId)
        permissionService.grantPermission(
            targetUserId.toString(), ENTITY_TYPE, namespaceId.toString(), PermissionRelation.ADMIN,
        )
        logger.info { "User ${currentUserId()} granted ADMIN on namespace $namespaceId to user $targetUserId" }
    }

    @DeleteMapping("/{namespaceId}/admins/{targetUserId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasPermission(#namespaceId, 'Namespace', 'WRITE')")
    fun revokeAdmin(
        @PathVariable namespaceId: UUID,
        @PathVariable targetUserId: UUID,
    ) {
        requireExists(namespaceId, targetUserId)
        permissionService.revokePermission(
            targetUserId.toString(), ENTITY_TYPE, namespaceId.toString(), PermissionRelation.ADMIN,
        )
        logger.info { "User ${currentUserId()} revoked ADMIN on namespace $namespaceId from user $targetUserId" }
    }

    @PutMapping("/{namespaceId}/members/{targetUserId}")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasPermission(#namespaceId, 'Namespace', 'WRITE')")
    fun grantMember(
        @PathVariable namespaceId: UUID,
        @PathVariable targetUserId: UUID,
    ) {
        requireExists(namespaceId, targetUserId)
        permissionService.grantPermission(
            targetUserId.toString(), ENTITY_TYPE, namespaceId.toString(), PermissionRelation.MEMBER,
        )
        logger.info { "User ${currentUserId()} granted MEMBER on namespace $namespaceId to user $targetUserId" }
    }

    /**
     * DELETE — revoke the MEMBER relationship. Only removes [:MEMBER]; any [:ADMIN]
     * relation the user holds on the same namespace is left untouched.
     */
    @DeleteMapping("/{namespaceId}/members/{targetUserId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasPermission(#namespaceId, 'Namespace', 'WRITE')")
    fun revokeMember(
        @PathVariable namespaceId: UUID,
        @PathVariable targetUserId: UUID,
    ) {
        requireExists(namespaceId, targetUserId)
        permissionService.revokePermission(
            targetUserId.toString(), ENTITY_TYPE, namespaceId.toString(), PermissionRelation.MEMBER,
        )
        logger.info { "User ${currentUserId()} revoked MEMBER on namespace $namespaceId from user $targetUserId" }
    }

    /**
     * GET — list users with a direct ADMIN/MEMBER relation on the namespace.
     * Each user appears at most once; ADMIN takes precedence over MEMBER for the
     * returned `role`. Super-admins without a direct relation are NOT listed
     * (this endpoint reflects namespace-level grants, not system-level roles).
     */
    @GetMapping("/{namespaceId}/users")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasPermission(#namespaceId, 'Namespace', 'READ')")
    @HideOnAccessDenied
    fun listNamespaceUsers(@PathVariable namespaceId: UUID): List<NamespaceUserListItem> {
        namespaceService.findById(namespaceId)
            ?: throw ResourceNotFoundException("Namespace not found: $namespaceId")

        val namespaceIdString = namespaceId.toString()
        val adminUserIds = permissionService
            .listUsersWithPermission(ENTITY_TYPE, namespaceIdString, PermissionRelation.ADMIN)
            .toSet()
        val memberUserIds = permissionService
            .listUsersWithPermission(ENTITY_TYPE, namespaceIdString, PermissionRelation.MEMBER)
            .toSet()
        val allUserIds = adminUserIds + memberUserIds
        if (allUserIds.isEmpty()) return emptyList()

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

    private fun requireExists(namespaceId: UUID, targetUserId: UUID) {
        namespaceService.findById(namespaceId)
            ?: throw ResourceNotFoundException("Namespace not found: $namespaceId")
        userService.findById(targetUserId)
            ?: throw ResourceNotFoundException("User not found: $targetUserId")
    }

    private fun currentUserId(): String = userService.getCurrentUser().id.toString()

    companion object : KLogging() {
        private const val ENTITY_TYPE = "Namespace"
        private const val ADMIN = "ADMIN"
        private const val MEMBER = "MEMBER"
    }
}
