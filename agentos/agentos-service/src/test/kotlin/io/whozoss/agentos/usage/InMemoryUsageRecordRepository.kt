package io.whozoss.agentos.usage

import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Test-only in-memory implementation of [UsageRecordRepository]. */
class InMemoryUsageRecordRepository : UsageRecordRepository {
    private val store = ConcurrentHashMap<UUID, UsageRecord>()

    // ---- parent-child links for tree traversal ----
    // Maps parentId -> set of childIds (mirrors the [:PARENT_OF] graph edges)
    private val parentToChildren = ConcurrentHashMap<UUID, MutableSet<UUID>>()

    override fun save(record: UsageRecord): UsageRecord {
        store[record.id] = record
        return record
    }

    override fun findById(id: UUID): UsageRecord? = store[id]?.takeIf { !it.metadata.removed }

    override fun findByCaseId(caseId: UUID): List<UsageRecord> =
        store.values.filter { it.caseId == caseId && !it.metadata.removed }

    fun findAll(): List<UsageRecord> = store.values.toList()

    /**
     * Register a parent-child relationship for [aggregateByCaseTree] traversal.
     * Call this in tests after creating parent and child cases.
     */
    fun linkParentToChild(parentId: UUID, childId: UUID) {
        parentToChildren.getOrPut(parentId) { mutableSetOf() }.add(childId)
    }

    // =========================================================================
    // Aggregation helpers
    // =========================================================================

    override fun aggregateByCaseId(caseId: UUID): UsageAggregate =
        aggregate(store.values.filter { it.caseId == caseId && !it.metadata.removed })

    override fun aggregateByCaseTree(rootCaseId: UUID): UsageAggregate {
        val caseIds = collectSubtree(rootCaseId)
        return aggregate(store.values.filter { it.caseId in caseIds && !it.metadata.removed })
    }

    override fun aggregateByUser(
        userId: UUID,
        namespaceId: UUID,
        from: Instant,
        to: Instant,
    ): UsageAggregate =
        aggregate(
            store.values.filter {
                !it.metadata.removed &&
                    it.userId == userId &&
                    it.namespaceId == namespaceId &&
                    !it.timestamp.isBefore(from) &&
                    !it.timestamp.isAfter(to)
            },
        )

    override fun aggregateByAgent(
        namespaceId: UUID,
        from: Instant,
        to: Instant,
    ): List<UsageAggregateByKey> =
        store.values
            .filter {
                !it.metadata.removed &&
                    it.namespaceId == namespaceId &&
                    !it.timestamp.isBefore(from) &&
                    !it.timestamp.isAfter(to)
            }
            .groupBy { it.agentName }
            .map { (name, records) -> UsageAggregateByKey(key = name, aggregate = aggregate(records)) }
            .sortedByDescending { it.aggregate.totalTokens }

    override fun aggregateByModel(
        namespaceId: UUID,
        from: Instant,
        to: Instant,
    ): List<UsageAggregateByKey> =
        store.values
            .filter {
                !it.metadata.removed &&
                    it.namespaceId == namespaceId &&
                    !it.timestamp.isBefore(from) &&
                    !it.timestamp.isAfter(to)
            }
            .groupBy { it.apiModelName ?: "unknown" }
            .map { (model, records) -> UsageAggregateByKey(key = model, aggregate = aggregate(records)) }
            .sortedByDescending { it.aggregate.totalTokens }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /** Collect [rootId] and all its descendants via the in-memory parent-child map. */
    private fun collectSubtree(rootId: UUID): Set<UUID> {
        val result = mutableSetOf<UUID>()
        val queue = ArrayDeque<UUID>()
        queue.add(rootId)
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (result.add(id)) {
                parentToChildren[id]?.forEach { queue.add(it) }
            }
        }
        return result
    }

    /**
     * Reduce a flat list of records into a [UsageAggregate].
     *
     * Null-cost contamination: within each currency group, if any record has
     * `cost == null`, the group total is null. Token counts always sum normally.
     */
    private fun aggregate(records: List<UsageRecord>): UsageAggregate {
        if (records.isEmpty()) return UsageAggregate.EMPTY

        // Group costs by currency; null cost contaminates the group total.
        val costByCurrency: Map<String, Double?> = records
            .groupBy { it.currency }
            .mapValues { (_, group) ->
                if (group.any { it.cost == null }) null
                else group.sumOf { it.cost!! }
            }

        return UsageAggregate(
            recordCount = records.size.toLong(),
            inputTokens = records.sumOf { it.inputTokens },
            outputTokens = records.sumOf { it.outputTokens },
            cacheReadTokens = records.sumOf { it.cacheReadTokens },
            cacheWriteTokens = records.sumOf { it.cacheWriteTokens },
            totalTokens = records.sumOf { it.totalTokens },
            costByCurrency = costByCurrency,
        )
    }
}
