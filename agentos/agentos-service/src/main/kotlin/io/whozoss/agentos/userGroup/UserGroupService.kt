package io.whozoss.agentos.userGroup

import io.whozoss.agentos.entity.EntityService
import java.util.UUID

interface UserGroupService : EntityService<UserGroup, UUID> {
    fun findByNamespaceId(namespaceId: UUID): List<UserGroupSearchResult>
    fun findByIdWithDetails(id: UUID): UserGroupSearchResult?
    fun createFromRequest(request: UserGroupCreateRequest): UserGroupSearchResult
    fun updateFromRequest(userGroupId: UUID, request: UserGroupUpdateRequest): UserGroupSearchResult
    fun findGroupsByUserExternalIds(externalIds: Collection<String>): Map<String, List<UserGroupSummary>>
    /**
     * Upsert memberships for a batch of users on [userGroupId].
     *
     * Each entry's [role] drives the operation:
     * - ADMIN / MEMBER: revoke the opposite edge, MERGE the desired one (idempotent).
     * - null: remove the user from the group entirely.
     *
     * Unknown [userId]s are silently skipped. The group must exist and be non-removed;
     * throws [io.whozoss.agentos.exception.ResourceNotFoundException] otherwise.
     *
     * Returns the updated group detail.
     */
    fun updateMemberships(userGroupId: UUID, entries: List<UserGroupMembershipEntry>): UserGroupSearchResult
}
