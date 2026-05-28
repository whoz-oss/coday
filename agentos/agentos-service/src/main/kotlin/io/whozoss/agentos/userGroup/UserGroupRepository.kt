package io.whozoss.agentos.userGroup

import io.whozoss.agentos.entity.EntityRepository
import java.util.UUID

interface UserGroupRepository : EntityRepository<UserGroup, UUID> {
    fun findByNamespaceId(namespaceId: UUID): List<UserGroupSearchResult>
    fun findByIdWithDetails(id: UUID): UserGroupSearchResult?
    fun addAgents(userGroupId: UUID, agentConfigIds: Collection<UUID>)
    fun removeAllAgents(userGroupId: UUID)
    /** Legacy sync path — adds [:MEMBER] edges by externalId. Used by the Whoz sync. */
    fun addUsers(userGroupId: UUID, userExternalIds: Collection<String>)
    /** Legacy sync path — removes [:MEMBER] edges by externalId. Used by the Whoz sync. */
    fun removeUsers(userGroupId: UUID, userExternalIds: Collection<String>)
    fun findGroupsByUserExternalIds(externalIds: Collection<String>): Map<String, List<UserGroupSummary>>
    /**
     * Upsert memberships for a batch of users.
     *
     * For each entry:
     * - role = ADMIN: revoke any [:MEMBER] edge, MERGE a [:ADMIN] edge.
     * - role = MEMBER: revoke any [:ADMIN] edge, MERGE a [:MEMBER] edge.
     * - role = null: revoke both [:ADMIN] and [:MEMBER] edges (remove from group).
     *
     * Unknown [userId]s (not found in the graph) are silently skipped by the Cypher MATCH.
     */
    fun updateMemberships(userGroupId: UUID, entries: List<Pair<UUID, String?>>)
}
