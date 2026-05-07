package io.whozoss.agentos.userGroup

import io.whozoss.agentos.entity.EntityRepository
import io.whozoss.agentos.entity.InMemoryEntityRepository
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Repository
import java.util.UUID

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
    override fun findByNamespaceExternalId(externalId: String): List<UserGroupSearchResult> = emptyList()
    override fun findByIdWithDetails(id: UUID): UserGroupSearchResult? = null
    override fun addAgents(userGroupId: UUID, agentConfigIds: Collection<UUID>) = Unit
    override fun removeAllAgents(userGroupId: UUID) = Unit
    override fun addUsers(userGroupId: UUID, userExternalIds: Collection<String>) = Unit
    override fun removeUsers(userGroupId: UUID, userExternalIds: Collection<String>) = Unit
}
