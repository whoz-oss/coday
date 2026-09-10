package io.whozoss.agentos.usage

import mu.KLogging
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/**
 * Default implementation of [UsageRecordService]. Delegates directly to [UsageRecordRepository].
 */
@Service
class UsageRecordServiceImpl(
    private val repository: UsageRecordRepository,
) : UsageRecordService {
    override fun create(record: UsageRecord): UsageRecord = repository.save(record)

    override fun findById(id: UUID): UsageRecord? = repository.findById(id)

    override fun findByCaseId(caseId: UUID): List<UsageRecord> = repository.findByCaseId(caseId)

    override fun aggregateByCaseId(caseId: UUID): UsageAggregate = repository.aggregateByCaseId(caseId)

    override fun aggregateByCaseTree(rootCaseId: UUID): UsageAggregate = repository.aggregateByCaseTree(rootCaseId)

    override fun aggregateByUser(
        userId: UUID,
        namespaceId: UUID,
        from: Instant,
        to: Instant,
    ): UsageAggregate = repository.aggregateByUser(userId, namespaceId, from, to)

    override fun aggregateByAgent(
        namespaceId: UUID,
        from: Instant,
        to: Instant,
    ): List<UsageAggregateByKey> = repository.aggregateByAgent(namespaceId, from, to)

    override fun aggregateByModel(
        namespaceId: UUID,
        from: Instant,
        to: Instant,
    ): List<UsageAggregateByKey> = repository.aggregateByModel(namespaceId, from, to)

    companion object : KLogging()
}
