package io.whozoss.agentos.namespace

import io.whozoss.agentos.membership.requireNoDuplicateUserIds
import io.whozoss.agentos.sdk.api.membership.MemberItem
import io.whozoss.agentos.sdk.api.membership.MembershipApi
import io.whozoss.agentos.sdk.api.user.UserMembershipRole
import io.whozoss.agentos.security.declarative.HideOnAccessDenied
import io.whozoss.agentos.user.UserService
import mu.KLogging
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Membership endpoints for [Namespace]: list members and delta-update their roles.
 *
 * Routes:
 * - `GET  /api/namespaces/{entityId}/members` — list users with direct ADMIN/MEMBER relation
 * - `PATCH /api/namespaces/{entityId}/members` — delta update (add / change role / revoke)
 *
 * Authorization:
 * - GET: namespace READ; `@HideOnAccessDenied` converts 403 → 404
 * - PATCH: SUPER_ADMIN or namespace WRITE at the route level; the finer two-tier rule
 *   (adding a new user requires SUPER_ADMIN) is enforced inside
 *   [NamespacePermissionService.updateMembers] — see its KDoc for the rationale.
 */
@RestController
@RequestMapping("/api/namespaces", produces = [MediaType.APPLICATION_JSON_VALUE])
class NamespaceMembershipController(
    private val namespaceService: NamespaceService,
    private val userService: UserService,
    private val namespacePermissionService: NamespacePermissionService,
) : MembershipApi {

    @GetMapping("/{entityId}/members")
    @PreAuthorize("hasPermission(#entityId, 'Namespace', 'READ')")
    @HideOnAccessDenied
    override fun getMembers(
        @PathVariable entityId: UUID,
    ): List<MemberItem> {
        namespaceService.getById(entityId)
        return namespacePermissionService.getMembers(entityId)
    }

    @PatchMapping("/{entityId}/members", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasPermission(#entityId, 'Namespace', 'WRITE')")
    override fun updateMembers(
        @PathVariable entityId: UUID,
        @RequestBody members: List<UserMembershipRole>,
    ): List<MemberItem> {
        requireNoDuplicateUserIds(members)
        val callerIsSuperAdmin = userService.getCurrentUser().isAdmin
        return namespacePermissionService.updateMembers(entityId, members, callerIsSuperAdmin)
    }

    companion object : KLogging()
}
