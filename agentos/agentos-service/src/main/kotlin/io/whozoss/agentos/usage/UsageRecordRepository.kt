package io.whozoss.agentos.usage

import java.time.Instant
import java.util.UUID

/**
 * Domain-level persistence contract for [UsageRecord].
 *
 * Parent identifier is [UUID] representing the [UsageRecord.caseId].
 */
interface UsageRecordRepository {
    /** Persist a new record (or update an existing one). */
    fun save(record: UsageRecord): UsageRecord

    /** Find a single record by its id, or null if not found or soft-deleted. */
    fun findById(id: UUID): UsageRecord?

    /** Find all active records belonging to the given case. */
    fun findByCaseId(caseId: UUID): List<UsageRecord>

    // =========================================================================
    // Aggregation queries
    // =========================================================================

    /**
     * Aggregate all active usage records for a single case.
     *
     * Returns [UsageAggregate.EMPTY] when no records exist for [caseId].
     */
    fun aggregateByCaseId(caseId: UUID): UsageAggregate

    /**
     * Aggregate all active usage records for a case tree rooted at [rootCaseId].
     *
     * Includes the root case and all its descendants (up to the platform's
     * delegation depth limit of 5 hops, queried up to 10 hops for safety).
     *
     * Returns [UsageAggregate.EMPTY] when no records exist for the tree.
     */
    fun aggregateByCaseTree(rootCaseId: UUID): UsageAggregate

    /**
     * Aggregate active usage records for a user within a namespace and time window.
     *
     * [from] and [to] are both inclusive bounds on [UsageRecord.timestamp].
     *
     * Returns [UsageAggregate.EMPTY] when no records match.
     */
    fun aggregateByUser(
        userId: UUID,
        namespaceId: UUID,
        from: Instant,
        to: Instant,
    ): UsageAggregate

    /**
     * Aggregate active usage records grouped by agent name within a namespace and time window.
     *
     * Returns one [UsageAggregateByKey] per distinct agent name, ordered by total tokens desc.
     */
    fun aggregateByAgent(
        namespaceId: UUID,
        from: Instant,
        to: Instant,
    ): List<UsageAggregateByKey>

    /**
     * Aggregate active usage records grouped by API model name within a namespace and time window.
     *
     * Records with a null [UsageRecord.apiModelName] are grouped under the key `"unknown"`.
     * Returns one [UsageAggregateByKey] per distinct model name, ordered by total tokens desc.
     */
    fun aggregateByModel(
        namespaceId: UUID,
        from: Instant,
        to: Instant,
    ): List<UsageAggregateByKey>

    /**
     * Sum the cost of all active records in the case tree rooted at [rootCaseId],
     * restricted to records with [UsageRecord.timestamp] >= [since].
     *
     * Returns `null` when at least one record in the matching set has no pricing configured
     * (cost unknown, not zero). Returns `null` also when no records match (no cost incurred
     * yet, indistinguishable from unpriced — callers must treat both as "unknown").
     *
     * Intended use: budget enforcement and cost display at the end of a delegated run,
     * where [since] is the run start timestamp.
     */
    fun sumCostByCaseTreeSince(
        rootCaseId: UUID,
        since: Instant,
    ): Double?
}
