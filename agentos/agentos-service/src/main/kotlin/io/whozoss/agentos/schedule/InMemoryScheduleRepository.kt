package io.whozoss.agentos.schedule

import io.whozoss.agentos.entity.EntityRepository
import io.whozoss.agentos.entity.InMemoryEntityRepository
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * In-memory implementation of [ScheduleRepository].
 *
 * Active when `agentos.persistence.mode` is absent, `in-memory`, or any value
 * other than `neo4j` or `embedded-neo4j`. Default fallback for dev/test runs.
 *
 * Schedules within a namespace are ordered by [nextTriggerAt] (nulls last),
 * then by creation time as a stable tie-breaker.
 */
@Repository
@ConditionalOnExpression(
    "'\${agentos.persistence.mode:in-memory}' != 'neo4j' " +
        "and '\${agentos.persistence.mode:in-memory}' != 'embedded-neo4j'",
)
class InMemoryScheduleRepository :
    ScheduleRepository,
    EntityRepository<Schedule, UUID> by InMemoryEntityRepository(
        parentIdExtractor = { it.namespaceId },
        comparator =
            compareBy<Schedule, java.time.Instant?>(nullsLast()) { it.nextTriggerAt }
                .thenBy { it.metadata.created },
    )
