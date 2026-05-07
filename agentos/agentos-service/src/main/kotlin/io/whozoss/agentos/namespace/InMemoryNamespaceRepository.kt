package io.whozoss.agentos.namespace

import io.whozoss.agentos.entity.EntityRepository
import io.whozoss.agentos.entity.InMemoryEntityRepository
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
@ConditionalOnExpression(
    "'\${agentos.persistence.mode:in-memory}' != 'neo4j' " +
        "and '\${agentos.persistence.mode:in-memory}' != 'embedded-neo4j'",
)
class InMemoryNamespaceRepository :
    NamespaceRepository,
    EntityRepository<Namespace, String> by InMemoryEntityRepository(
        parentIdExtractor = { NamespaceRepository.NAMESPACE_PARENT_KEY },
        comparator = compareBy { it.name },
    ) {
    override fun findByExternalId(externalId: String): Namespace? =
        findByParent(NamespaceRepository.NAMESPACE_PARENT_KEY).firstOrNull { it.externalId == externalId }

    init {
        save(
            Namespace(
                metadata = EntityMetadata(id = UUID.fromString("00000000-0000-0000-0000-000000000001")),
                name = "default_namespace",
                description = "Default namespace",
            ),
        )
    }
}
