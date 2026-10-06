package io.whozoss.agentos.usage

import io.whozoss.agentos.persistence.Neo4jChildLinkService
import mu.KLogging
import org.springframework.data.repository.findByIdOrNull
import java.time.Instant
import java.util.UUID

/**
 * Neo4j-backed implementation of [UsageRecordRepository].
 *
 * Each [UsageRecord] is stored as a `(:UsageRecord)-[:BELONGS_TO]->(:Case)` edge.
 * The BELONGS_TO relationship is created via [Neo4jChildLinkService.link] AFTER the node
 * is saved — never by setting [UsageRecordNode.case] before save. Setting the field before
 * save causes SDN to write stub CaseNode properties (empty status/title) onto the existing
 * Case node, corrupting it.
 *
 * ## Aggregation and null-cost contamination
 *
 * Aggregations expose the sum of known costs and the number of records with unknown cost.
 * The known sum remains available as a lower bound instead of being replaced by zero or null.
 *
 * @see UsageRecordNodeNeo4jRepository for the Cypher queries.
 */
open class Neo4jUsageRecordRepository(
    private val usageRecordNodeNeo4jRepository: UsageRecordNodeNeo4jRepository,
    private val childLinkService: Neo4jChildLinkService,
) : UsageRecordRepository {
    override fun save(record: UsageRecord): UsageRecord =
        usageRecordNodeNeo4jRepository
            .save(UsageRecordNode.fromDomain(record))
            .also { childLinkService.link("UsageRecord", it.id, "Case", it.caseId) }
            .toDomain()
            .also { logger.debug { "[Neo4jUsageRecordRepository] Saved UsageRecord ${record.id} for case ${record.caseId}" } }

    override fun findById(id: UUID): UsageRecord? =
        usageRecordNodeNeo4jRepository
            .findByIdOrNull(id.toString())
            ?.takeIf { it.removed != true }
            ?.toDomain()

    override fun findByCaseId(caseId: UUID): List<UsageRecord> =
        usageRecordNodeNeo4jRepository
            .findActiveByCaseId(caseId.toString())
            .map { it.toDomain() }

    // =========================================================================
    // Aggregation
    // =========================================================================

    override fun aggregateByCaseId(caseId: UUID): UsageAggregate =
        mapToAggregate(usageRecordNodeNeo4jRepository.aggregateByCaseId(caseId.toString()))

    override fun aggregateByCaseTree(rootCaseId: UUID): UsageAggregate =
        mapToAggregate(usageRecordNodeNeo4jRepository.aggregateByCaseTree(rootCaseId.toString()))

    override fun aggregateByUser(
        userId: UUID,
        namespaceId: UUID,
        from: Instant,
        to: Instant,
    ): UsageAggregate =
        mapToAggregate(
            usageRecordNodeNeo4jRepository.aggregateByUser(
                userId = userId.toString(),
                namespaceId = namespaceId.toString(),
                from = from,
                to = to,
            ),
        )

    override fun aggregateByAgent(
        namespaceId: UUID,
        from: Instant,
        to: Instant,
    ): List<UsageAggregateByKey> =
        mapToAggregateByKey(
            rows = usageRecordNodeNeo4jRepository.aggregateByAgent(namespaceId.toString(), from, to),
            keyField = "agentName",
        )

    override fun aggregateByModel(
        namespaceId: UUID,
        from: Instant,
        to: Instant,
    ): List<UsageAggregateByKey> =
        mapToAggregateByKey(
            rows = usageRecordNodeNeo4jRepository.aggregateByModel(namespaceId.toString(), from, to),
            keyField = "modelName",
        )

    override fun sumCostByCaseTreeSince(
        rootCaseId: UUID,
        since: Instant,
    ): UsageCostAggregate? {
        val row = usageRecordNodeNeo4jRepository
            .sumCostByCaseTreeSince(rootCaseId.toString(), since)
            .firstOrNull()
            ?: return null
        val recordCount = (row["recordCount"] as Number).toLong()
        if (recordCount == 0L) return null
        return UsageCostAggregate(
            cost = (row["partialCostSum"] as Number).toDouble(),
            unknownCostCount = (row["nullCostCount"] as Number).toLong(),
        )
    }

    // =========================================================================
    // Private mapping helpers
    // =========================================================================

    /**
     * Convert the single-row Cypher list envelope into a [UsageAggregate].
     * The list envelope is required by SDN; the row carries both the known-cost lower
     * bound and the unknown-cost count.
     */
    private fun mapToAggregate(rows: List<Map<String, Any>>): UsageAggregate {
        val row = rows.firstOrNull() ?: return UsageAggregate.EMPTY
        val recordCount = (row["recordCount"] as Number).toLong()
        if (recordCount == 0L) return UsageAggregate.EMPTY
        return UsageAggregate(
            recordCount = recordCount,
            inputTokens = (row["inputTokens"] as Number).toLong(),
            outputTokens = (row["outputTokens"] as Number).toLong(),
            cacheReadTokens = (row["cacheReadTokens"] as Number).toLong(),
            cacheWriteTokens = (row["cacheWriteTokens"] as Number).toLong(),
            totalTokens = (row["totalTokens"] as Number).toLong(),
            cost = (row["partialCostSum"] as Number).toDouble(),
            unknownCostCount = (row["nullCostCount"] as Number).toLong(),
        )
    }

    /**
     * Convert a list of Cypher rows (one per key) into a list of [UsageAggregateByKey].
     *
     * [keyField] is the Cypher column holding the group dimension (e.g. "agentName").
     */
    private fun mapToAggregateByKey(
        rows: List<Map<String, Any>>,
        keyField: String,
    ): List<UsageAggregateByKey> =
        rows.map { row ->
            val nullCostCount = (row["nullCostCount"] as Number).toLong()
            val partialCostSum = (row["partialCostSum"] as Number).toDouble()
            UsageAggregateByKey(
                key = row[keyField] as String,
                aggregate = UsageAggregate(
                    recordCount = (row["recordCount"] as Number).toLong(),
                    inputTokens = (row["inputTokens"] as Number).toLong(),
                    outputTokens = (row["outputTokens"] as Number).toLong(),
                    cacheReadTokens = (row["cacheReadTokens"] as Number).toLong(),
                    cacheWriteTokens = (row["cacheWriteTokens"] as Number).toLong(),
                    totalTokens = (row["totalTokens"] as Number).toLong(),
                    cost = partialCostSum,
                    unknownCostCount = nullCostCount,
                ),
            )
        }

    companion object : KLogging()
}
