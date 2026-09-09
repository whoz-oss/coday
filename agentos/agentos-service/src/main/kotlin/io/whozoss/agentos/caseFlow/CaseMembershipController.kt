package io.whozoss.agentos.caseFlow

import io.whozoss.agentos.membership.requireNoDuplicateUserIds
import io.whozoss.agentos.membership.resolveMembers
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionRelation
import io.whozoss.agentos.permissions.PermissionService
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
 * Membership endpoints for [Case]: list members and delta-update their roles.
 *
 * Routes:
 * - `GET  /api/cases/{entityId}/members` — list users with direct ADMIN/MEMBER relation
 * - `PATCH /api/cases/{entityId}/members` — delta update (add / change role / revoke)
 *
 * Authorization:
 * - GET: Case READ; `@HideOnAccessDenied` converts 403 → 404
 * - PATCH: Case WRITE; self-modification entries are silently filtered to prevent
 *   callers from revoking or demoting themselves.
 *
 * Delta semantics: only listed users are affected; users absent from the list are
 * untouched. A null role means revoke. Non-existent userIds are silently skipped
 * (the Cypher MATCH on User filters them at the persistence layer).
 */
@RestController
@RequestMapping("/api/cases", produces = [MediaType.APPLICATION_JSON_VALUE])
class CaseMembershipController(
    private val caseService: CaseService,
    private val userService: UserService,
    private val permissionService: PermissionService,
) : MembershipApi {

    @GetMapping("/{entityId}/members")
    @PreAuthorize("hasPermission(#entityId, 'Case', 'READ')")
    @HideOnAccessDenied
    override fun getMembers(
        @PathVariable entityId: UUID,
    ): List<MemberItem> {
        caseService.getById(entityId)
        return resolveMembers(entityId, EntityType.CASE, permissionService, userService)
    }

    /**
     * PATCH /api/cases/{entityId}/members — delta membership update.
     *
     * Entries targeting the current caller are silently filtered to prevent
     * self-modification (promotion, demotion, or revocation).
     * Non-existent userIds are silently skipped.
     * Returns the resulting membership list after the update.
     */
    @PatchMapping("/{entityId}/members", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#entityId, 'Case', 'WRITE')")
    @HideOnAccessDenied
    override fun updateMembers(
        @PathVariable entityId: UUID,
        @RequestBody members: List<UserMembershipRole>,
    ): List<MemberItem> {
        requireNoDuplicateUserIds(members)
        caseService.getById(entityId)
        val currentUserId = userService.getCurrentUser().id
        val caseIdStr = entityId.toString()

        val filtered = members.filter { it.userId != currentUserId }
        if (filtered.size < members.size) {
            logger.info { "Filtered out self-modification entry for user $currentUserId on case $entityId" }
        }

        val entries = filtered.map { entry ->
            entry.userId.toString() to entry.role?.let { PermissionRelation.valueOf(it) }
        }

        if (entries.isNotEmpty()) {
            permissionService.applyShareBatch(EntityType.CASE, caseIdStr, entries)
            logger.info { "Case $entityId members updated by $currentUserId — ${entries.size} entry(ies) applied" }
        }

        return resolveMembers(entityId, EntityType.CASE, permissionService, userService)
    }

    companion object : KLogging()
}
