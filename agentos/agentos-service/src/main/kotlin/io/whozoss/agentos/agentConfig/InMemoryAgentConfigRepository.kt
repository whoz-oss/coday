package io.whozoss.agentos.agentConfig

import io.whozoss.agentos.entity.EntityRepository
import io.whozoss.agentos.entity.InMemoryEntityRepository
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * In-memory implementation of [AgentConfigRepository].
 *
 * Active when `agentos.persistence.mode` is absent, `in-memory`, or any value
 * other than `neo4j` or `embedded-neo4j`. Default fallback for dev/test runs.
 *
 * Agent configs are sorted by name within a namespace.
 */
@Repository
@ConditionalOnExpression(
    "'\${agentos.persistence.mode:in-memory}' != 'neo4j' " +
        "and '\${agentos.persistence.mode:in-memory}' != 'embedded-neo4j'",
)
class InMemoryAgentConfigRepository :
    AgentConfigRepository,
    EntityRepository<AgentConfig, UUID> by InMemoryEntityRepository(
        parentIdExtractor = { it.namespaceId },
        comparator = compareBy { it.name },
    )
