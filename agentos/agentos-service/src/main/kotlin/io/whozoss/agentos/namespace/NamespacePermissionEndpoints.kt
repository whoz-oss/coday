package io.whozoss.agentos.namespace

import io.swagger.v3.oas.annotations.media.Schema
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionRelation
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.security.declarative.HideOnAccessDenied
import io.whozoss.agentos.user.UserService
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import mu.KLogging
import mu.KotlinLogging
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
 * One (userId, role) pair within an [UpdateNamespaceMembersRequest].
 *
 * Unlike [NamespaceRoleEntry] (external-id based, used by the platform-wide sync
 * endpoint), this works in **internal userId** terms — no externalId resolution,
 * no auto-creation of users. See [UpdateNamespaceMembersRequest] for the rationale.
 */
@Schema(name = "NamespaceMemberEntry")
data class NamespaceMemberEntry(
    val userId: UUID,
    @field:Pattern(regexp = "ADMIN|MEMBER", message = "role must be ADMIN or MEMBER")
    val role: String,
)

/**
 * Request body for [NamespacePermissionEndpoints.updateMembers].
 *
 * A single batch call driving the whole namespace-membership form, modeled after
 * `UserGroupUpdateRequest`. Deliberately **internal userId** based: `applyShareBatch`
 * already works natively in internal ids, so there is no externalId resolution layer
 * and no auto-creation of users here (unlike UserGroup, which accepts externalIds and
 * auto-creates missing users). An externalId-based variant may be added later; out of
 * scope for now.
 *
 * Delta semantics (like UserGroup), **not** a declarative replace: [members] are the
 * users to add or whose role to change, with an explicit target role; [userIdsToRemove]
 * are fully revoked (both ADMIN and MEMBER). A userId absent from both lists is left
 * untouched — this is what prevents an accidental mass-revocation of the roster.
 */
@Schema(name = "UpdateNamespaceMembersRequest")
data class UpdateNamespaceMembersRequest(
    val members: List<@Valid NamespaceMemberEntry> = emptyList(),
    val userIdsToRemove: Set<UUID> = emptySet(),
)

/**
 * Dedicated endpoints for managing namespace ADMIN/MEMBER permissions.
 *
 * Authorization:
 * - grant/revoke (ADMIN/MEMBER): namespace WRITE — caller must be namespace ADMIN
 * - listNamespaceUsers: namespace READ + `@HideOnAccessDenied` — 404 hides existence
 * - updateRolesByExternalId: SUPER_ADMIN only
 * - updateMembers: SUPER_ADMIN or namespace WRITE at the route level, with a finer two-tier
 *   check inside [NamespacePermissionService.updateMembers] — see its KDoc
 *
 * Idempotency: the Neo4j MERGE + DELETE primitives are naturally idempotent,
 * so repeated PUTs/DELETEs are safe.
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

    @GetMapping("/{namespaceId}/users")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasPermission(#namespaceId, 'Namespace', 'READ')")
    @HideOnAccessDenied
    fun listNamespaceUsers(
        @PathVariable namespaceId: UUID,
    ): List<NamespaceUserListItem> {
        namespaceService.findById(namespaceId)
            ?: throw ResourceNotFoundException("Namespace not found: $namespaceId")
        return resolveNamespaceUsers(namespaceId, permissionService, userService)
    }

    /**
     * POST /api/namespaces/update-roles-by-external-id — full sync of one user's namespace roles.
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

    /**
     * POST /api/namespaces/{namespaceId}/members — single-call batch membership update,
     * driving a namespace-members form (add / change role / remove) in one round-trip.
     *
     * Authorization is two-tier: the route-level `@PreAuthorize` only checks that the caller
     * is a SUPER_ADMIN or holds namespace WRITE (so a namespace ADMIN can reach this endpoint
     * at all). The finer rule — adding a genuinely new user requires SUPER_ADMIN, while editing
     * or removing an existing member only requires WRITE — is enforced inside
     * [NamespacePermissionService.updateMembers], because it needs to inspect the namespace's
     * *current* membership to tell "new" from "existing", which a SpEL `@PreAuthorize` cannot
     * express. See that method's KDoc for the full rationale (and why: `GET /api/users` is
     * SUPER_ADMIN-only, so a plain namespace admin has no directory to add unknown users from).
     */
    @PostMapping("/{namespaceId}/members", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasPermission(#namespaceId, 'Namespace', 'WRITE')")
    fun updateMembers(
        @PathVariable namespaceId: UUID,
        @Valid @RequestBody request: UpdateNamespaceMembersRequest,
    ): List<NamespaceUserListItem> {
        val callerIsSuperAdmin = userService.getCurrentUser().isAdmin
        return namespacePermissionService.updateMembers(namespaceId, request, callerIsSuperAdmin)
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

// ---------------------------------------------------------------------------
// Shared helpers
// ---------------------------------------------------------------------------

private val resolveLogger = KotlinLogging.logger {}

/**
 * Resolves the users holding a direct ADMIN or MEMBER relation on [namespaceId], with role
 * precedence ADMIN > MEMBER for a user holding both (should not normally happen given the
 * single-relation invariant, but defended anyway).
 *
 * Shared between [NamespacePermissionEndpoints.listNamespaceUsers] (read-only listing) and
 * [NamespacePermissionServiceImpl.updateMembers] (current-state snapshot for the two-tier
 * authorization check, the role delta, and the anti-lockout guard) — a single query shape
 * for "who is on this namespace and with which role".
 */
internal fun resolveNamespaceUsers(
    namespaceId: UUID,
    permissionService: PermissionService,
    userService: UserService,
): List<NamespaceUserListItem> {
    val namespaceIdString = namespaceId.toString()
    val adminUserIds =
        permissionService
            .listUsersWithPermission(EntityType.NAMESPACE, namespaceIdString, PermissionRelation.ADMIN)
            .toSet()
    val memberUserIds =
        permissionService
            .listUsersWithPermission(EntityType.NAMESPACE, namespaceIdString, PermissionRelation.MEMBER)
            .toSet()
    val allUserIds = adminUserIds + memberUserIds
    if (allUserIds.isEmpty()) return emptyList()

    val uuids =
        allUserIds.mapNotNull { raw ->
            runCatching { UUID.fromString(raw) }.getOrNull()
                ?: run {
                    resolveLogger.warn { "Dropping malformed user id from permission listing on namespace $namespaceId: '$raw'" }
                    null
                }
        }
    val users = userService.findByIds(uuids)

    val missingCount = uuids.size - users.size
    if (missingCount > 0) {
        resolveLogger.warn {
            "Namespace $namespaceId has $missingCount permission relation(s) pointing to " +
                "non-existent users — filtered from response"
        }
    }

    return users.map { user ->
        val userIdString = user.metadata.id.toString()
        val role = if (userIdString in adminUserIds) "ADMIN" else "MEMBER"
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
