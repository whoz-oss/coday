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
 * All aggregation queries return raw [Map] rows from Cypher. Each row contains a
 * `nullCostCount` field. When > 0, the cost for that currency group is unknown and is
 * reported as `null` in [UsageAggregate.costByCurrency] — never as the partial sum.
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

    // =========================================================================
    // Private mapping helpers
    // =========================================================================

    /**
     * Convert a list of Cypher aggregate rows (one per currency) into a single [UsageAggregate].
     *
     * Each row contains:
     *   currency, recordCount, inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens,
     *   totalTokens, partialCostSum, nullCostCount
     *
     * Null-cost contamination: when `nullCostCount > 0` for a currency, the cost for that
     * currency is `null` (not `partialCostSum`, which would be a silent undercount).
     * Token counts are summed across all currency rows (tokens are currency-agnostic).
     */
    private fun mapToAggregate(rows: List<Map<String, Any>>): UsageAggregate {
        if (rows.isEmpty()) return UsageAggregate.EMPTY

        var totalRecords = 0L
        var inputTokens = 0L
        var outputTokens = 0L
        var cacheReadTokens = 0L
        var cacheWriteTokens = 0L
        var totalTokens = 0L
        val costByCurrency = mutableMapOf<String, Double?>()

        for (row in rows) {
            val currency = row["currency"] as String
            val nullCostCount = (row["nullCostCount"] as Number).toLong()
            val partialCostSum = (row["partialCostSum"] as Number).toDouble()

            totalRecords += (row["recordCount"] as Number).toLong()
            inputTokens += (row["inputTokens"] as Number).toLong()
            outputTokens += (row["outputTokens"] as Number).toLong()
            cacheReadTokens += (row["cacheReadTokens"] as Number).toLong()
            cacheWriteTokens += (row["cacheWriteTokens"] as Number).toLong()
            totalTokens += (row["totalTokens"] as Number).toLong()

            // Contamination: if any record in this currency group has no pricing, the
            // whole group total is null — never a partial sum.
            costByCurrency[currency] = if (nullCostCount > 0L) null else partialCostSum
        }

        return UsageAggregate(
            recordCount = totalRecords,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            cacheReadTokens = cacheReadTokens,
            cacheWriteTokens = cacheWriteTokens,
            totalTokens = totalTokens,
            costByCurrency = costByCurrency,
        )
    }

    /**
     * Convert a list of Cypher rows (one per key+currency combination) into a list of
     * [UsageAggregateByKey], one per distinct key.
     *
     * [keyField] is the Cypher column that holds the group dimension (e.g. "agentName").
     *
     * Rows for the same key but different currencies are merged: token counts are summed
     * (currency-agnostic) and costs are collected per currency with contamination applied.
     */
    private fun mapToAggregateByKey(
        rows: List<Map<String, Any>>,
        keyField: String,
    ): List<UsageAggregateByKey> {
        // Group rows by key, then fold each group into a UsageAggregate.
        data class Accum(
            var recordCount: Long = 0L,
            var inputTokens: Long = 0L,
            var outputTokens: Long = 0L,
            var cacheReadTokens: Long = 0L,
            var cacheWriteTokens: Long = 0L,
            var totalTokens: Long = 0L,
            val costByCurrency: MutableMap<String, Double?> = mutableMapOf(),
        )

        val accumByKey = linkedMapOf<String, Accum>() // preserve ORDER BY from Cypher
        for (row in rows) {
            val key = row[keyField] as String
            val acc = accumByKey.getOrPut(key) { Accum() }
            val currency = row["currency"] as String
            val nullCostCount = (row["nullCostCount"] as Number).toLong()
            val partialCostSum = (row["partialCostSum"] as Number).toDouble()

            acc.recordCount += (row["recordCount"] as Number).toLong()
            acc.inputTokens += (row["inputTokens"] as Number).toLong()
            acc.outputTokens += (row["outputTokens"] as Number).toLong()
            acc.cacheReadTokens += (row["cacheReadTokens"] as Number).toLong()
            acc.cacheWriteTokens += (row["cacheWriteTokens"] as Number).toLong()
            acc.totalTokens += (row["totalTokens"] as Number).toLong()
            acc.costByCurrency[currency] = if (nullCostCount > 0L) null else partialCostSum
        }

        return accumByKey.map { (key, acc) ->
            UsageAggregateByKey(
                key = key,
                aggregate = UsageAggregate(
                    recordCount = acc.recordCount,
                    inputTokens = acc.inputTokens,
                    outputTokens = acc.outputTokens,
                    cacheReadTokens = acc.cacheReadTokens,
                    cacheWriteTokens = acc.cacheWriteTokens,
                    totalTokens = acc.totalTokens,
                    costByCurrency = acc.costByCurrency,
                ),
            )
        }
    }

    companion object : KLogging()
}
