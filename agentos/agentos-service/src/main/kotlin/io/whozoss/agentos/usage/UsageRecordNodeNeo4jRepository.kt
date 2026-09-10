package io.whozoss.agentos.usage

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Spring Data Neo4j repository for [UsageRecordNode].
 */
interface UsageRecordNodeNeo4jRepository : Neo4jRepository<UsageRecordNode, String> {
    /**
     * Find all non-removed records for a case, ordered by timestamp.
     *
     * Returns `u, r, c` so SDN maps the [UsageRecordNode.case] @Relationship field.
     */
    @Transactional(readOnly = true)
    @Query(
        $$"""MATCH (u:UsageRecord)
            WHERE u.caseId = $caseId AND (u.removed IS NULL OR u.removed = false)
            OPTIONAL MATCH (u)-[r:BELONGS_TO]->(c:Case)
            RETURN u, r, c ORDER BY u.timestamp ASC, u.id ASC
            """,
    )
    fun findActiveByCaseId(caseId: String): List<UsageRecordNode>

    // =========================================================================
    // Aggregation queries
    //
    // SDN cannot map a multi-column RETURN into List<Map<String, Any>>. The
    // workaround is identical to CaseNodeNeo4jRepository.findDirectRelations:
    // wrap the result row in a Cypher map literal and collect all rows into a
    // single list column. SDN then maps the whole result as List<Map<String, Any>>.
    //
    // Null-cost contamination strategy:
    //   Cypher's sum() silently ignores nulls and would produce a silent undercount.
    //   We count records with null cost separately (nullCostCount) and return that count
    //   alongside the partial sum. The Kotlin caller replaces the partial sum with null
    //   when nullCostCount > 0.
    //
    // All costs are in a single implicit currency unit — no GROUP BY currency.
    // Token counts use sum() directly because they are Long (never null).
    // All queries filter (u.removed IS NULL OR u.removed = false).
    // =========================================================================

    /** Aggregate all active records for a single case. */
    @Transactional(readOnly = true)
    @Query(
        $$"""MATCH (u:UsageRecord)
            WHERE u.caseId = $caseId AND (u.removed IS NULL OR u.removed = false)
            WITH count(u) AS recordCount,
                 sum(u.inputTokens) AS inputTokens,
                 sum(u.outputTokens) AS outputTokens,
                 sum(u.cacheReadTokens) AS cacheReadTokens,
                 sum(u.cacheWriteTokens) AS cacheWriteTokens,
                 sum(u.totalTokens) AS totalTokens,
                 sum(CASE WHEN u.cost IS NOT NULL THEN u.cost ELSE 0 END) AS partialCostSum,
                 sum(CASE WHEN u.cost IS NULL THEN 1 ELSE 0 END) AS nullCostCount
            RETURN collect({
                recordCount: recordCount,
                inputTokens: inputTokens,
                outputTokens: outputTokens,
                cacheReadTokens: cacheReadTokens,
                cacheWriteTokens: cacheWriteTokens,
                totalTokens: totalTokens,
                partialCostSum: partialCostSum,
                nullCostCount: nullCostCount
            })
            """,
    )
    fun aggregateByCaseId(caseId: String): List<Map<String, Any>>

    /**
     * Aggregate all active records for a case tree (root + descendants up to 10 hops).
     *
     * Traverses [:PARENT_OF] edges written by CaseNodeNeo4jRepository.linkParentToChild.
     */
    @Transactional(readOnly = true)
    @Query(
        $$"""MATCH (root:Case {id: $rootCaseId})
            WITH collect(root.id) + [desc IN [(root)-[:PARENT_OF*1..10]->(d:Case) | d.id] | desc] AS caseIds
            MATCH (u:UsageRecord)
            WHERE u.caseId IN caseIds AND (u.removed IS NULL OR u.removed = false)
            WITH count(u) AS recordCount,
                 sum(u.inputTokens) AS inputTokens,
                 sum(u.outputTokens) AS outputTokens,
                 sum(u.cacheReadTokens) AS cacheReadTokens,
                 sum(u.cacheWriteTokens) AS cacheWriteTokens,
                 sum(u.totalTokens) AS totalTokens,
                 sum(CASE WHEN u.cost IS NOT NULL THEN u.cost ELSE 0 END) AS partialCostSum,
                 sum(CASE WHEN u.cost IS NULL THEN 1 ELSE 0 END) AS nullCostCount
            RETURN collect({
                recordCount: recordCount,
                inputTokens: inputTokens,
                outputTokens: outputTokens,
                cacheReadTokens: cacheReadTokens,
                cacheWriteTokens: cacheWriteTokens,
                totalTokens: totalTokens,
                partialCostSum: partialCostSum,
                nullCostCount: nullCostCount
            })
            """,
    )
    fun aggregateByCaseTree(rootCaseId: String): List<Map<String, Any>>

    /** Aggregate active records for a user in a namespace over a time window. */
    @Transactional(readOnly = true)
    @Query(
        $$"""MATCH (u:UsageRecord)
            WHERE u.userId = $userId
              AND u.namespaceId = $namespaceId
              AND u.timestamp >= $from
              AND u.timestamp <= $to
              AND (u.removed IS NULL OR u.removed = false)
            WITH count(u) AS recordCount,
                 sum(u.inputTokens) AS inputTokens,
                 sum(u.outputTokens) AS outputTokens,
                 sum(u.cacheReadTokens) AS cacheReadTokens,
                 sum(u.cacheWriteTokens) AS cacheWriteTokens,
                 sum(u.totalTokens) AS totalTokens,
                 sum(CASE WHEN u.cost IS NOT NULL THEN u.cost ELSE 0 END) AS partialCostSum,
                 sum(CASE WHEN u.cost IS NULL THEN 1 ELSE 0 END) AS nullCostCount
            RETURN collect({
                recordCount: recordCount,
                inputTokens: inputTokens,
                outputTokens: outputTokens,
                cacheReadTokens: cacheReadTokens,
                cacheWriteTokens: cacheWriteTokens,
                totalTokens: totalTokens,
                partialCostSum: partialCostSum,
                nullCostCount: nullCostCount
            })
            """,
    )
    fun aggregateByUser(
        userId: String,
        namespaceId: String,
        from: Instant,
        to: Instant,
    ): List<Map<String, Any>>

    /** Aggregate active records grouped by agent name in a namespace over a time window. */
    @Transactional(readOnly = true)
    @Query(
        $$"""MATCH (u:UsageRecord)
            WHERE u.namespaceId = $namespaceId
              AND u.timestamp >= $from
              AND u.timestamp <= $to
              AND (u.removed IS NULL OR u.removed = false)
            WITH u.agentName AS agentName,
                 count(u) AS recordCount,
                 sum(u.inputTokens) AS inputTokens,
                 sum(u.outputTokens) AS outputTokens,
                 sum(u.cacheReadTokens) AS cacheReadTokens,
                 sum(u.cacheWriteTokens) AS cacheWriteTokens,
                 sum(u.totalTokens) AS totalTokens,
                 sum(CASE WHEN u.cost IS NOT NULL THEN u.cost ELSE 0 END) AS partialCostSum,
                 sum(CASE WHEN u.cost IS NULL THEN 1 ELSE 0 END) AS nullCostCount
            ORDER BY totalTokens DESC
            RETURN collect({
                agentName: agentName,
                recordCount: recordCount,
                inputTokens: inputTokens,
                outputTokens: outputTokens,
                cacheReadTokens: cacheReadTokens,
                cacheWriteTokens: cacheWriteTokens,
                totalTokens: totalTokens,
                partialCostSum: partialCostSum,
                nullCostCount: nullCostCount
            })
            """,
    )
    fun aggregateByAgent(
        namespaceId: String,
        from: Instant,
        to: Instant,
    ): List<Map<String, Any>>

    /** Aggregate active records grouped by model name in a namespace over a time window. Records with null apiModelName are grouped under 'unknown'. */
    @Transactional(readOnly = true)
    @Query(
        $$"""MATCH (u:UsageRecord)
            WHERE u.namespaceId = $namespaceId
              AND u.timestamp >= $from
              AND u.timestamp <= $to
              AND (u.removed IS NULL OR u.removed = false)
            WITH coalesce(u.apiModelName, 'unknown') AS modelName,
                 count(u) AS recordCount,
                 sum(u.inputTokens) AS inputTokens,
                 sum(u.outputTokens) AS outputTokens,
                 sum(u.cacheReadTokens) AS cacheReadTokens,
                 sum(u.cacheWriteTokens) AS cacheWriteTokens,
                 sum(u.totalTokens) AS totalTokens,
                 sum(CASE WHEN u.cost IS NOT NULL THEN u.cost ELSE 0 END) AS partialCostSum,
                 sum(CASE WHEN u.cost IS NULL THEN 1 ELSE 0 END) AS nullCostCount
            ORDER BY totalTokens DESC
            RETURN collect({
                modelName: modelName,
                recordCount: recordCount,
                inputTokens: inputTokens,
                outputTokens: outputTokens,
                cacheReadTokens: cacheReadTokens,
                cacheWriteTokens: cacheWriteTokens,
                totalTokens: totalTokens,
                partialCostSum: partialCostSum,
                nullCostCount: nullCostCount
            })
            """,
    )
    fun aggregateByModel(
        namespaceId: String,
        from: Instant,
        to: Instant,
    ): List<Map<String, Any>>

    /**
     * Sum the cost of all active [UsageRecordNode]s in the case tree rooted at [rootCaseId],
     * restricted to records with [UsageRecordNode.timestamp] >= [since].
     *
     * Returns a single-element list containing a map with:
     * - `recordCount`    (Long)   — number of matching records (0 = no records yet)
     * - `partialCostSum` (Double) — sum of non-null costs (0.0 when all costs are null)
     * - `nullCostCount`  (Long)   — number of records whose cost is null
     *
     * When `nullCostCount > 0` the total cost is unknown: the caller must return null
     * rather than `partialCostSum`, which would be a silent undercount.
     * When `recordCount == 0` there are no records yet; the caller should also return null.
     *
     * ## Index usage
     * Phase 1 — `MATCH (root:Case {id: $rootCaseId})` hits the UNIQUE constraint on `Case.id`.
     * Phase 2 — `WHERE u.caseId IN caseIds` hits the `usage_record_case_id` B-tree index
     *            with at most 6 seeks (platform delegation depth limit = 5).
     * The [since] predicate filters the already-small per-case result set after the index seek;
     * a composite index on `(caseId, timestamp)` would eliminate that step but is not warranted
     * at the expected cardinality (a few records per case per run).
     */
    @Transactional(readOnly = true)
    @Query(
        $$"""MATCH (root:Case {id: $rootCaseId})
            WITH collect(root.id) + [desc IN [(root)-[:PARENT_OF*1..10]->(d:Case) | d.id] | desc] AS caseIds
            MATCH (u:UsageRecord)
            WHERE u.caseId IN caseIds
              AND u.timestamp >= $since
              AND (u.removed IS NULL OR u.removed = false)
            WITH count(u) AS recordCount,
                 sum(CASE WHEN u.cost IS NOT NULL THEN u.cost ELSE 0 END) AS partialCostSum,
                 sum(CASE WHEN u.cost IS NULL    THEN 1    ELSE 0 END) AS nullCostCount
            RETURN collect({
                recordCount:    recordCount,
                partialCostSum: partialCostSum,
                nullCostCount:  nullCostCount
            })
            """,
    )
    fun sumCostByCaseTreeSince(
        rootCaseId: String,
        since: Instant,
    ): List<Map<String, Any>>
}
