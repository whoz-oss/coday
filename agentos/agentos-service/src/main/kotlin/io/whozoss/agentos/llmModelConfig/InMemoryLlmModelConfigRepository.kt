package io.whozoss.agentos.llmModelConfig

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
class InMemoryLlmModelConfigRepository :
    LlmModelConfigRepository,
    EntityRepository<LlmModelConfig, UUID> by InMemoryEntityRepository(
        parentIdExtractor = { it.llmConfigId },
        comparator = compareBy { it.apiName },
    ) {
    // Not implemented — in-memory mode is temporary, use Neo4j for real usage.
    override fun findByNamespaceId(namespaceId: UUID): List<LlmModelConfig> = emptyList()
}
