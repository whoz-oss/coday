package io.whozoss.agentos.usergroup

import io.whozoss.agentos.entity.EntityRepository
import io.whozoss.agentos.entity.InMemoryEntityRepository
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Repository
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Repository
@ConditionalOnExpression(
    "'\${agentos.persistence.mode:in-memory}' != 'neo4j' " +
        "and '\${agentos.persistence.mode:in-memory}' != 'embedded-neo4j'",
)
class InMemoryUserGroupRepository :
    UserGroupRepository,
    EntityRepository<UserGroup, UUID> by InMemoryEntityRepository(
        parentIdExtractor = { it.namespaceId },
        comparator = compareBy { it.name },
    ) {
    private val agentsByGroup = ConcurrentHashMap<UUID, MutableSet<UUID>>()
    private val usersByGroup = ConcurrentHashMap<UUID, MutableSet<UUID>>()

    override fun findAgentIds(userGroupId: UUID): List<UUID> =
        agentsByGroup[userGroupId]?.toList() ?: emptyList()

    override fun countUsers(userGroupId: UUID): Int =
        usersByGroup[userGroupId]?.size ?: 0

    override fun countAgents(userGroupId: UUID): Int =
        agentsByGroup[userGroupId]?.size ?: 0

    override fun addUser(userGroupId: UUID, userId: UUID) {
        usersByGroup.computeIfAbsent(userGroupId) { mutableSetOf() }.add(userId)
    }

    override fun removeUser(userGroupId: UUID, userId: UUID) {
        usersByGroup[userGroupId]?.remove(userId)
    }

    override fun replaceAgents(userGroupId: UUID, agentIds: Set<UUID>) {
        agentsByGroup[userGroupId] = agentIds.toMutableSet()
    }

    override fun softDeleteWithRelationships(userGroupId: UUID) {
        delete(userGroupId)
        agentsByGroup.remove(userGroupId)
        usersByGroup.remove(userGroupId)
    }
}
