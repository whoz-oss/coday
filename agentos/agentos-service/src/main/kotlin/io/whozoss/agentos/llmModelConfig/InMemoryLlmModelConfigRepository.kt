package io.whozoss.agentos.llmModelConfig

import io.whozoss.agentos.entity.EntityRepository
import io.whozoss.agentos.entity.InMemoryEntityRepository
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * In-memory implementation of [LlmModelConfigRepository].
 *
 * Active when `agentos.persistence.mode` is absent, `in-memory`, or any value
 * other than `neo4j` or `embedded-neo4j`.
 *
 * Entities are sorted by [LlmModelConfig.apiName] within each provider config.
 */
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
    )
