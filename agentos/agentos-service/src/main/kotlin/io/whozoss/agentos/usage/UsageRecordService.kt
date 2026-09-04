package io.whozoss.agentos.usage

import java.time.Instant
import java.util.UUID

/**
 * Business-logic contract for [UsageRecord] management.
 */
interface UsageRecordService {
    /** Persist a new [UsageRecord]. */
    fun create(record: UsageRecord): UsageRecord

    /** Find a single record by its id, or null if not found. */
    fun findById(id: UUID): UsageRecord?

    /** Find all records for a given case. */
    fun findByCaseId(caseId: UUID): List<UsageRecord>

    // =========================================================================
    // Aggregation queries (Lot 3)
    // =========================================================================

    /** Aggregate usage for a single case. */
    fun aggregateByCaseId(caseId: UUID): UsageAggregate

    /** Aggregate usage for a case and all its descendants. */
    fun aggregateByCaseTree(rootCaseId: UUID): UsageAggregate

    /** Aggregate usage for a user in a namespace over a time window. */
    fun aggregateByUser(
        userId: UUID,
        namespaceId: UUID,
        from: Instant,
        to: Instant,
    ): UsageAggregate

    /** Aggregate usage grouped by agent name in a namespace over a time window. */
    fun aggregateByAgent(
        namespaceId: UUID,
        from: Instant,
        to: Instant,
    ): List<UsageAggregateByKey>

    /** Aggregate usage grouped by model name in a namespace over a time window. */
    fun aggregateByModel(
        namespaceId: UUID,
        from: Instant,
        to: Instant,
    ): List<UsageAggregateByKey>
}
