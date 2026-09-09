package io.whozoss.agentos.namespace

import io.swagger.v3.oas.annotations.media.Schema
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionRelation
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.api.user.UserMembershipRole
import io.whozoss.agentos.security.declarative.HideOnAccessDenied
import io.whozoss.agentos.user.NoDuplicateUserIds
import io.whozoss.agentos.user.UserService
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import mu.KLogging
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * One (namespace, role) pair within a [SyncUserRolesRequest].
 */
@Schema(name = "NamespaceRoleEntry")
data class NamespaceRoleEntry(
    @field:NotBlank val namespaceExternalId: String,
    @field:Pattern(regexp = "ADMIN|MEMBER", message = "role must be ADMIN or MEMBER")
    val role: String,
)

/**
 * Request body for [NamespacePermissionEndpoints.updateRolesByExternalId].
 *
 * Describes the **complete** desired role set for [userExternalId] across namespaces.
 * Any namespace relation the user currently holds that is not listed in [namespaceRoles]
 * will be revoked.
 */
@Schema(name = "SyncUserRolesRequest")
data class SyncUserRolesRequest(
    @field:NotBlank val userExternalId: String,
    val namespaceRoles: List<@Valid NamespaceRoleEntry> = emptyList(),
)

/**
 * Fine-grained per-user permission endpoints and the external-id sync endpoint.
 *
 * The batch membership endpoints (GET + PATCH /members) live in [NamespaceMembershipController].
 *
 * Authorization:
 * - grant/revoke (ADMIN/MEMBER): SUPER_ADMIN or namespace WRITE
 * - updateRolesByExternalId: SUPER_ADMIN only
 *
 * Idempotency: the Neo4j MERGE + DELETE primitives are naturally idempotent,
 * so repeated PUTs/DELETEs are safe.
 */
@RestController
@Validated
@RequestMapping(
    "/api/namespaces",
    produces = [MediaType.APPLICATION_JSON_VALUE],
)
class NamespacePermissionEndpoints(
    private val namespaceService: NamespaceService,
    private val userService: UserService,
    private val permissionService: PermissionService,
    private val namespacePermissionService: NamespacePermissionService,
) {
    @PutMapping("/{namespaceId}/admins/{targetUserId}")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasPermission(#namespaceId, 'Namespace', 'WRITE')")
    fun grantAdmin(
        @PathVariable namespaceId: UUID,
        @PathVariable targetUserId: UUID,
    ) {
        requireExists(namespaceId, targetUserId)
        permissionService.grantPermission(
            targetUserId.toString(),
            EntityType.NAMESPACE,
            namespaceId.toString(),
            PermissionRelation.ADMIN,
        )
        logger.info { "User ${currentUserId()} granted ADMIN on namespace $namespaceId to user $targetUserId" }
    }

    @DeleteMapping("/{namespaceId}/admins/{targetUserId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasPermission(#namespaceId, 'Namespace', 'WRITE')")
    fun revokeAdmin(
        @PathVariable namespaceId: UUID,
        @PathVariable targetUserId: UUID,
    ) {
        requireExists(namespaceId, targetUserId)
        permissionService.revokePermission(
            targetUserId.toString(),
            EntityType.NAMESPACE,
            namespaceId.toString(),
            PermissionRelation.ADMIN,
        )
        logger.info { "User ${currentUserId()} revoked ADMIN on namespace $namespaceId from user $targetUserId" }
    }

    @PutMapping("/{namespaceId}/members/{targetUserId}")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasPermission(#namespaceId, 'Namespace', 'WRITE')")
    fun grantMember(
        @PathVariable namespaceId: UUID,
        @PathVariable targetUserId: UUID,
    ) {
        requireExists(namespaceId, targetUserId)
        permissionService.grantPermission(
            targetUserId.toString(),
            EntityType.NAMESPACE,
            namespaceId.toString(),
            PermissionRelation.MEMBER,
        )
        logger.info { "User ${currentUserId()} granted MEMBER on namespace $namespaceId to user $targetUserId" }
    }

    /**
     * DELETE — revoke the MEMBER relationship. Only removes [:MEMBER]; any [:ADMIN]
     * relation the user holds on the same namespace is left untouched.
     */
    @DeleteMapping("/{namespaceId}/members/{targetUserId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasPermission(#namespaceId, 'Namespace', 'WRITE')")
    fun revokeMember(
        @PathVariable namespaceId: UUID,
        @PathVariable targetUserId: UUID,
    ) {
        requireExists(namespaceId, targetUserId)
        permissionService.revokePermission(
            targetUserId.toString(),
            EntityType.NAMESPACE,
            namespaceId.toString(),
            PermissionRelation.MEMBER,
        )
        logger.info { "User ${currentUserId()} revoked MEMBER on namespace $namespaceId from user $targetUserId" }
    }

    /**
     * POST /api/namespaces/update-roles-by-external-id — full sync of one user’s namespace roles.
     *
     * Delegates all business logic to [NamespacePermissionService.syncUserRoles].
     * Authorization: SUPER_ADMIN only.
     */
    @PostMapping("/update-roles-by-external-id", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    fun updateRolesByExternalId(
        @Valid @RequestBody request: SyncUserRolesRequest,
    ): SyncUserRolesRequest {
        namespacePermissionService.syncUserRoles(request)
        return request
    }

    private fun requireExists(
        namespaceId: UUID,
        targetUserId: UUID,
    ) {
        namespaceService.getById(namespaceId)
        userService.getById(targetUserId)
    }

    private fun currentUserId(): String = userService.getCurrentUser().id.toString()

    companion object : KLogging()
}
