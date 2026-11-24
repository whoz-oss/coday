package io.whozoss.agentos.caseEvent

import io.whozoss.agentos.entity.InMemoryEntityRepository
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import java.time.Instant
import java.util.UUID

/** Test-only in-memory implementation of [CaseEventRepository]. */
class InMemoryCaseEventRepository : CaseEventRepository {
    private val delegate = InMemoryEntityRepository<CaseEvent, UUID>(
        parentIdExtractor = { it.caseId },
        comparator = compareBy { it.timestamp },
    )

    override fun save(entity: CaseEvent) = delegate.save(entity)
    override fun findByIds(ids: Collection<UUID>, withRemoved: Boolean) = delegate.findByIds(ids, withRemoved)
    override fun findByParent(parentId: UUID) = delegate.findByParent(parentId)
    override fun delete(id: UUID) = delegate.delete(id)
    override fun deleteByParent(parentId: UUID) = delegate.deleteByParent(parentId)

    override fun deleteFrom(caseId: UUID, eventId: UUID): Int {
        val active = delegate.findByParent(caseId)
        val index = active.indexOfFirst { it.id == eventId }
        if (index < 0) return 0
        return active.drop(index).count { delegate.delete(it.id) }
    }

    /**
     * Returns the timestamp of the most recent [MessageEvent] per case id.
     * Cases with no messages are absent from the result.
     */
    override fun findLastMessageTimestamps(caseIds: Collection<UUID>): Map<UUID, Instant> =
        delegate
            .findAll()
            .filterIsInstance<MessageEvent>()
            .filter { it.caseId in caseIds }
            .groupBy { it.caseId }
            .mapValues { (_, events) -> events.maxOf { it.timestamp } }
}
