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
    // Aggregation queries (Lot 3)
    //
    // SDN cannot map a multi-column RETURN into List<Map<String, Any>>. It throws
    // "Records with more than one value cannot be converted without a mapper" (the
    // SingleValueMappingFunction error). The workaround is identical to the pattern
    // used by CaseNodeNeo4jRepository.findDirectRelations: wrap the result row in a
    // Cypher map literal and collect all rows into a single list column. SDN then
    // maps the whole result as List<Map<String, Any>> without needing a custom converter.
    //
    // Null-cost contamination strategy:
    //   Cypher's sum() silently ignores nulls and would produce a silent undercount.
    //   We count records with null cost per group separately (nullCostCount) and return
    //   that count alongside the partial sum. The Kotlin caller (Neo4jUsageRecordRepository)
    //   replaces the partial sum with null when nullCostCount > 0.
    //
    // Token counts use sum() directly because they are Long (never null).
    // All queries filter (u.removed IS NULL OR u.removed = false).
    // =========================================================================

    /**
     * Aggregate all active records for a single case, grouped by currency.
     *
     * Returns a single-column list of maps (one per currency) via collect({...}).
     * Each map contains: currency, recordCount, inputTokens, outputTokens,
     * cacheReadTokens, cacheWriteTokens, totalTokens, partialCostSum, nullCostCount.
     */
    @Transactional(readOnly = true)
    @Query(
        $$"""MATCH (u:UsageRecord)
            WHERE u.caseId = $caseId AND (u.removed IS NULL OR u.removed = false)
            WITH u.currency AS currency,
                 count(u) AS recordCount,
                 sum(u.inputTokens) AS inputTokens,
                 sum(u.outputTokens) AS outputTokens,
                 sum(u.cacheReadTokens) AS cacheReadTokens,
                 sum(u.cacheWriteTokens) AS cacheWriteTokens,
                 sum(u.totalTokens) AS totalTokens,
                 sum(CASE WHEN u.cost IS NOT NULL THEN u.cost ELSE 0 END) AS partialCostSum,
                 sum(CASE WHEN u.cost IS NULL THEN 1 ELSE 0 END) AS nullCostCount
            RETURN collect({
                currency: currency,
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
     * Aggregate all active records for a case tree (root + descendants up to 10 hops),
     * grouped by currency.
     *
     * Traverses [:PARENT_OF] edges written by CaseNodeNeo4jRepository.linkParentToChild.
     */
    @Transactional(readOnly = true)
    @Query(
        $$"""MATCH (root:Case {id: $rootCaseId})
            WITH collect(root.id) + [desc IN [(root)-[:PARENT_OF*1..10]->(d:Case) | d.id] | desc] AS caseIds
            MATCH (u:UsageRecord)
            WHERE u.caseId IN caseIds AND (u.removed IS NULL OR u.removed = false)
            WITH u.currency AS currency,
                 count(u) AS recordCount,
                 sum(u.inputTokens) AS inputTokens,
                 sum(u.outputTokens) AS outputTokens,
                 sum(u.cacheReadTokens) AS cacheReadTokens,
                 sum(u.cacheWriteTokens) AS cacheWriteTokens,
                 sum(u.totalTokens) AS totalTokens,
                 sum(CASE WHEN u.cost IS NOT NULL THEN u.cost ELSE 0 END) AS partialCostSum,
                 sum(CASE WHEN u.cost IS NULL THEN 1 ELSE 0 END) AS nullCostCount
            RETURN collect({
                currency: currency,
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

    /**
     * Aggregate active records for a user in a namespace over a time window, grouped by currency.
     */
    @Transactional(readOnly = true)
    @Query(
        $$"""MATCH (u:UsageRecord)
            WHERE u.userId = $userId
              AND u.namespaceId = $namespaceId
              AND u.timestamp >= $from
              AND u.timestamp <= $to
              AND (u.removed IS NULL OR u.removed = false)
            WITH u.currency AS currency,
                 count(u) AS recordCount,
                 sum(u.inputTokens) AS inputTokens,
                 sum(u.outputTokens) AS outputTokens,
                 sum(u.cacheReadTokens) AS cacheReadTokens,
                 sum(u.cacheWriteTokens) AS cacheWriteTokens,
                 sum(u.totalTokens) AS totalTokens,
                 sum(CASE WHEN u.cost IS NOT NULL THEN u.cost ELSE 0 END) AS partialCostSum,
                 sum(CASE WHEN u.cost IS NULL THEN 1 ELSE 0 END) AS nullCostCount
            RETURN collect({
                currency: currency,
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

    /**
     * Aggregate active records grouped by agent name in a namespace over a time window.
     *
     * Returns one map per (agentName, currency) pair. The Kotlin caller folds currency
     * rows into the UsageAggregate.costByCurrency map per agent.
     */
    @Transactional(readOnly = true)
    @Query(
        $$"""MATCH (u:UsageRecord)
            WHERE u.namespaceId = $namespaceId
              AND u.timestamp >= $from
              AND u.timestamp <= $to
              AND (u.removed IS NULL OR u.removed = false)
            WITH u.agentName AS agentName, u.currency AS currency,
                 count(u) AS recordCount,
                 sum(u.inputTokens) AS inputTokens,
                 sum(u.outputTokens) AS outputTokens,
                 sum(u.cacheReadTokens) AS cacheReadTokens,
                 sum(u.cacheWriteTokens) AS cacheWriteTokens,
                 sum(u.totalTokens) AS totalTokens,
                 sum(CASE WHEN u.cost IS NOT NULL THEN u.cost ELSE 0 END) AS partialCostSum,
                 sum(CASE WHEN u.cost IS NULL THEN 1 ELSE 0 END) AS nullCostCount
            RETURN collect({
                agentName: agentName,
                currency: currency,
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

    /**
     * Aggregate active records grouped by model name in a namespace over a time window.
     *
     * Records with null apiModelName are grouped under 'unknown'.
     */
    @Transactional(readOnly = true)
    @Query(
        $$"""MATCH (u:UsageRecord)
            WHERE u.namespaceId = $namespaceId
              AND u.timestamp >= $from
              AND u.timestamp <= $to
              AND (u.removed IS NULL OR u.removed = false)
            WITH coalesce(u.apiModelName, 'unknown') AS modelName, u.currency AS currency,
                 count(u) AS recordCount,
                 sum(u.inputTokens) AS inputTokens,
                 sum(u.outputTokens) AS outputTokens,
                 sum(u.cacheReadTokens) AS cacheReadTokens,
                 sum(u.cacheWriteTokens) AS cacheWriteTokens,
                 sum(u.totalTokens) AS totalTokens,
                 sum(CASE WHEN u.cost IS NOT NULL THEN u.cost ELSE 0 END) AS partialCostSum,
                 sum(CASE WHEN u.cost IS NULL THEN 1 ELSE 0 END) AS nullCostCount
            RETURN collect({
                modelName: modelName,
                currency: currency,
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
}
