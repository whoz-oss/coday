package io.whozoss.agentos.caseEvent

import io.whozoss.agentos.entity.EntityRepository
import io.whozoss.agentos.entity.InMemoryEntityRepository
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * In-memory implementation of [CaseEventRepository].
 *
 * Active when `agentos.persistence.mode` is absent, `in-memory`, or any value
 * other than `neo4j` or `embedded-neo4j`. This is the default fallback used by
 * the openapi spec generation task and lightweight local runs.
 */
@Repository
@ConditionalOnExpression(
    "'\${agentos.persistence.mode:in-memory}' != 'neo4j' " +
        "and '\${agentos.persistence.mode:in-memory}' != 'embedded-neo4j'",
)
class InMemoryCaseEventRepository :
    CaseEventRepository,
    EntityRepository<CaseEvent, UUID> by InMemoryEntityRepository(
        parentIdExtractor = { it.caseId },
        comparator = compareBy { it.timestamp },
    )
