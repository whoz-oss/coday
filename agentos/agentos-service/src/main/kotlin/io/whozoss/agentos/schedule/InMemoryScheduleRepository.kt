package io.whozoss.agentos.schedule

import io.whozoss.agentos.entity.EntityRepository
import io.whozoss.agentos.entity.InMemoryEntityRepository
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

/**
 * In-memory implementation of [ScheduleRepository].
 *
 * Active when `agentos.persistence.mode` is absent, `in-memory`, or any value
 * other than `neo4j` or `embedded-neo4j`. Default fallback for dev/test runs.
 *
 * Schedules within a namespace are ordered by [nextTriggerAt] (nulls last),
 * then by creation time as a stable tie-breaker.
 *
 * The [delegate] is kept as an explicit field so [findDueSchedules] can call
 * [InMemoryEntityRepository.findAll] across all namespaces without needing a
 * parent id.
 */
@Repository
@ConditionalOnExpression(
    "'\${agentos.persistence.mode:in-memory}' != 'neo4j' " +
        "and '\${agentos.persistence.mode:in-memory}' != 'embedded-neo4j'",
)
class InMemoryScheduleRepository : ScheduleRepository {

    private val delegate = InMemoryEntityRepository<Schedule, UUID>(
        parentIdExtractor = { it.namespaceId },
        comparator =
            compareBy<Schedule, Instant?>(nullsLast()) { it.nextTriggerAt }
                .thenBy { it.metadata.created },
    )

    // ---- EntityRepository delegation ----

    override fun save(entity: Schedule): Schedule = delegate.save(entity)

    override fun findByIds(ids: Collection<UUID>): List<Schedule> = delegate.findByIds(ids)

    override fun findByParent(parentId: UUID): List<Schedule> = delegate.findByParent(parentId)

    override fun delete(id: UUID): Boolean = delegate.delete(id)

    override fun deleteByParent(parentId: UUID): Int = delegate.deleteByParent(parentId)

    // ---- ScheduleRepository extension ----

    override fun findDueSchedules(now: Instant): List<Schedule> =
        delegate.findAll()
            .filter { it.enabled && it.nextTriggerAt != null && !it.nextTriggerAt.isAfter(now) }
            .sortedBy { it.nextTriggerAt }
}
