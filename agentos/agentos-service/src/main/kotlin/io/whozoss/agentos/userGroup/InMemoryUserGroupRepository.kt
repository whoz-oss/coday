package io.whozoss.agentos.userGroup

import io.whozoss.agentos.entity.EntityRepository
import io.whozoss.agentos.entity.InMemoryEntityRepository
import io.whozoss.agentos.namespace.NamespaceRepository
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
@ConditionalOnExpression(
    "'\${agentos.persistence.mode:in-memory}' != 'neo4j' " +
        "and '\${agentos.persistence.mode:in-memory}' != 'embedded-neo4j'",
)
class InMemoryUserGroupRepository(
    private val namespaceRepository: NamespaceRepository,
) :
    UserGroupRepository,
    EntityRepository<UserGroup, UUID> by InMemoryEntityRepository(
        parentIdExtractor = { it.namespaceId },
        comparator = compareBy { it.name },
    ) {
    override fun findByNamespaceExternalId(externalId: String): List<UserGroupSearchResult> {
        val namespace = namespaceRepository.findByExternalId(externalId) ?: return emptyList()
        return findByParent(namespace.id)
            .filter { !it.metadata.removed }
            .map {
                UserGroupSearchResult(
                    userGroupId = it.metadata.id,
                    namespaceId = namespace.id,
                    namespaceExternalId = externalId,
                    name = it.name,
                )
            }
    }
}
