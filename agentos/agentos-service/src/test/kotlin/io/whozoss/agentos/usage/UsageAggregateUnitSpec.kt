package io.whozoss.agentos.usage

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.time.Instant
import java.util.UUID

/**
 * Unit tests for aggregation semantics via [InMemoryUsageRecordRepository].
 *
 * These tests validate:
 * - Multi-currency safety: costs in different currencies are never summed together
 * - Null-cost contamination: one unpriced record makes the group total null
 * - Tree aggregation: root + descendants are all included
 * - Token counts always sum normally regardless of cost nullability
 *
 * Cost values use exact binary-representable doubles (powers of 0.5 / 0.25 / 0.125)
 * to avoid floating-point accumulation errors in assertions.
 */
class UsageAggregateUnitSpec : StringSpec({

    val nsId: UUID = UUID.randomUUID()
    val userId: UUID = UUID.randomUUID()

    fun record(
        caseId: UUID,
        agentName: String = "agent",
        apiModelName: String? = "gpt-4",
        totalTokens: Long = 100L,
        cost: Double? = 0.25,
        currency: String = "USD",
        userId: UUID? = null,
        timestamp: Instant = Instant.now(),
    ) = UsageRecord(
        metadata = EntityMetadata(),
        namespaceId = nsId,
        caseId = caseId,
        userId = userId,
        source = UsageSource.LLM,
        outcome = UsageOutcome.COMPLETED,
        agentName = agentName,
        apiModelName = apiModelName,
        totalTokens = totalTokens,
        inputTokens = totalTokens / 2,
        outputTokens = totalTokens / 2,
        cost = cost,
        currency = currency,
        timestamp = timestamp,
    )

    // =========================================================================
    // aggregateByCaseId
    // =========================================================================

    "aggregateByCaseId returns EMPTY when no records exist" {
        val repo = InMemoryUsageRecordRepository()
        repo.aggregateByCaseId(UUID.randomUUID()) shouldBe UsageAggregate.EMPTY
    }

    "aggregateByCaseId sums tokens across records" {
        val repo = InMemoryUsageRecordRepository()
        val caseId = UUID.randomUUID()
        repo.save(record(caseId, totalTokens = 100L, cost = 0.25))
        repo.save(record(caseId, totalTokens = 200L, cost = 0.50))

        val agg = repo.aggregateByCaseId(caseId)
        agg.totalTokens shouldBe 300L
        agg.recordCount shouldBe 2L
        agg.costByCurrency["USD"] shouldBe 0.75
    }

    "aggregateByCaseId: null cost contaminates the group total" {
        // One unpriced record makes the whole USD total null.
        val repo = InMemoryUsageRecordRepository()
        val caseId = UUID.randomUUID()
        repo.save(record(caseId, totalTokens = 100L, cost = 0.25))
        repo.save(record(caseId, totalTokens = 200L, cost = null)) // unpriced

        val agg = repo.aggregateByCaseId(caseId)
        agg.totalTokens shouldBe 300L // tokens sum normally
        agg.costByCurrency["USD"] shouldBe null // cost is contaminated
    }

    "aggregateByCaseId: multi-currency records are not mixed" {
        // Records in USD and EUR must produce separate entries in costByCurrency.
        val repo = InMemoryUsageRecordRepository()
        val caseId = UUID.randomUUID()
        repo.save(record(caseId, totalTokens = 100L, cost = 0.25, currency = "USD"))
        repo.save(record(caseId, totalTokens = 50L, cost = 0.50, currency = "EUR"))

        val agg = repo.aggregateByCaseId(caseId)
        agg.totalTokens shouldBe 150L
        ("USD" in agg.costByCurrency) shouldBe true
        ("EUR" in agg.costByCurrency) shouldBe true
        agg.costByCurrency["USD"] shouldBe 0.25
        agg.costByCurrency["EUR"] shouldBe 0.50
    }

    "aggregateByCaseId: null cost in one currency does not contaminate another" {
        // USD is unpriced but EUR is priced. EUR total must remain non-null.
        val repo = InMemoryUsageRecordRepository()
        val caseId = UUID.randomUUID()
        repo.save(record(caseId, totalTokens = 100L, cost = null, currency = "USD"))
        repo.save(record(caseId, totalTokens = 50L, cost = 0.50, currency = "EUR"))

        val agg = repo.aggregateByCaseId(caseId)
        agg.costByCurrency["USD"] shouldBe null
        agg.costByCurrency["EUR"] shouldBe 0.50
    }

    // =========================================================================
    // aggregateByCaseTree
    // =========================================================================

    "aggregateByCaseTree includes root case records" {
        val repo = InMemoryUsageRecordRepository()
        val rootId = UUID.randomUUID()
        repo.save(record(rootId, totalTokens = 100L, cost = 0.25))

        val agg = repo.aggregateByCaseTree(rootId)
        agg.totalTokens shouldBe 100L
        agg.recordCount shouldBe 1L
    }

    "aggregateByCaseTree includes direct child records" {
        val repo = InMemoryUsageRecordRepository()
        val rootId = UUID.randomUUID()
        val childId = UUID.randomUUID()
        repo.linkParentToChild(rootId, childId)
        repo.save(record(rootId, totalTokens = 100L, cost = 0.25))
        repo.save(record(childId, totalTokens = 200L, cost = 0.50))

        val agg = repo.aggregateByCaseTree(rootId)
        agg.totalTokens shouldBe 300L
        agg.recordCount shouldBe 2L
        agg.costByCurrency["USD"] shouldBe 0.75
    }

    "aggregateByCaseTree includes multi-level descendants" {
        // root -> child -> grandchild: two levels of delegation.
        // Use exact binary-representable costs: 0.25 + 0.50 + 0.25 = 1.0 exactly.
        val repo = InMemoryUsageRecordRepository()
        val rootId = UUID.randomUUID()
        val childId = UUID.randomUUID()
        val grandchildId = UUID.randomUUID()
        repo.linkParentToChild(rootId, childId)
        repo.linkParentToChild(childId, grandchildId)
        repo.save(record(rootId, totalTokens = 100L, cost = 0.25))
        repo.save(record(childId, totalTokens = 200L, cost = 0.50))
        repo.save(record(grandchildId, totalTokens = 300L, cost = 0.25))

        val agg = repo.aggregateByCaseTree(rootId)
        agg.totalTokens shouldBe 600L
        agg.recordCount shouldBe 3L
        agg.costByCurrency["USD"] shouldBe 1.0
    }

    "aggregateByCaseTree: null cost in descendant contaminates tree total" {
        // Root is priced, child is not. Tree total must be null.
        val repo = InMemoryUsageRecordRepository()
        val rootId = UUID.randomUUID()
        val childId = UUID.randomUUID()
        repo.linkParentToChild(rootId, childId)
        repo.save(record(rootId, totalTokens = 100L, cost = 0.25))
        repo.save(record(childId, totalTokens = 200L, cost = null))

        val agg = repo.aggregateByCaseTree(rootId)
        agg.totalTokens shouldBe 300L // tokens still sum
        agg.costByCurrency["USD"] shouldBe null // contaminated by child
    }

    "aggregateByCaseTree does not include records from unrelated cases" {
        val repo = InMemoryUsageRecordRepository()
        val rootId = UUID.randomUUID()
        val unrelatedId = UUID.randomUUID()
        repo.save(record(rootId, totalTokens = 100L))
        repo.save(record(unrelatedId, totalTokens = 999L))

        val agg = repo.aggregateByCaseTree(rootId)
        agg.totalTokens shouldBe 100L
        agg.recordCount shouldBe 1L
    }

    // =========================================================================
    // aggregateByUser
    // =========================================================================

    "aggregateByUser returns EMPTY when no records match" {
        val repo = InMemoryUsageRecordRepository()
        val from = Instant.parse("2024-01-01T00:00:00Z")
        val to = Instant.parse("2024-12-31T23:59:59Z")
        repo.aggregateByUser(userId, nsId, from, to) shouldBe UsageAggregate.EMPTY
    }

    "aggregateByUser sums records within the time window" {
        val repo = InMemoryUsageRecordRepository()
        val caseId = UUID.randomUUID()
        val t1 = Instant.parse("2024-06-01T10:00:00Z")
        val t2 = Instant.parse("2024-06-15T10:00:00Z")
        repo.save(record(caseId, totalTokens = 100L, cost = 0.25, userId = userId, timestamp = t1))
        repo.save(record(caseId, totalTokens = 200L, cost = 0.50, userId = userId, timestamp = t2))

        val from = Instant.parse("2024-06-01T00:00:00Z")
        val to = Instant.parse("2024-06-30T23:59:59Z")
        val agg = repo.aggregateByUser(userId, nsId, from, to)
        agg.totalTokens shouldBe 300L
        agg.costByCurrency["USD"] shouldBe 0.75
    }

    "aggregateByUser excludes records outside the time window" {
        val repo = InMemoryUsageRecordRepository()
        val caseId = UUID.randomUUID()
        val inside = Instant.parse("2024-06-15T10:00:00Z")
        val outside = Instant.parse("2024-07-15T10:00:00Z")
        repo.save(record(caseId, totalTokens = 100L, userId = userId, timestamp = inside))
        repo.save(record(caseId, totalTokens = 999L, userId = userId, timestamp = outside))

        val from = Instant.parse("2024-06-01T00:00:00Z")
        val to = Instant.parse("2024-06-30T23:59:59Z")
        val agg = repo.aggregateByUser(userId, nsId, from, to)
        agg.totalTokens shouldBe 100L
        agg.recordCount shouldBe 1L
    }

    // =========================================================================
    // aggregateByAgent
    // =========================================================================

    "aggregateByAgent groups by agent name" {
        val repo = InMemoryUsageRecordRepository()
        val caseId = UUID.randomUUID()
        val ts = Instant.now()
        repo.save(record(caseId, agentName = "alpha", totalTokens = 100L, cost = 0.25, timestamp = ts))
        repo.save(record(caseId, agentName = "alpha", totalTokens = 200L, cost = 0.50, timestamp = ts))
        repo.save(record(caseId, agentName = "beta", totalTokens = 50L, cost = 0.125, timestamp = ts))

        val from = ts.minusSeconds(1)
        val to = ts.plusSeconds(1)
        val results = repo.aggregateByAgent(nsId, from, to)
        results shouldHaveSize 2
        val alpha = results.find { it.key == "alpha" }
        alpha shouldNotBe null
        alpha!!.aggregate.totalTokens shouldBe 300L
        alpha.aggregate.costByCurrency["USD"] shouldBe 0.75
        val beta = results.find { it.key == "beta" }
        beta!!.aggregate.totalTokens shouldBe 50L
    }

    "aggregateByAgent: null cost contaminates agent group total" {
        val repo = InMemoryUsageRecordRepository()
        val caseId = UUID.randomUUID()
        val ts = Instant.now()
        repo.save(record(caseId, agentName = "alpha", totalTokens = 100L, cost = 0.25, timestamp = ts))
        repo.save(record(caseId, agentName = "alpha", totalTokens = 200L, cost = null, timestamp = ts))

        val from = ts.minusSeconds(1)
        val to = ts.plusSeconds(1)
        val results = repo.aggregateByAgent(nsId, from, to)
        results shouldHaveSize 1
        val alpha = results.first()
        alpha.aggregate.totalTokens shouldBe 300L // tokens sum normally
        alpha.aggregate.costByCurrency["USD"] shouldBe null // contaminated
    }

    // =========================================================================
    // aggregateByModel
    // =========================================================================

    "aggregateByModel groups by model name and falls back to 'unknown' for null" {
        val repo = InMemoryUsageRecordRepository()
        val caseId = UUID.randomUUID()
        val ts = Instant.now()
        repo.save(record(caseId, apiModelName = "gpt-4", totalTokens = 100L, cost = 0.25, timestamp = ts))
        repo.save(record(caseId, apiModelName = null, totalTokens = 50L, cost = 0.125, timestamp = ts))

        val from = ts.minusSeconds(1)
        val to = ts.plusSeconds(1)
        val results = repo.aggregateByModel(nsId, from, to)
        results shouldHaveSize 2
        results.find { it.key == "gpt-4" } shouldNotBe null
        results.find { it.key == "unknown" } shouldNotBe null
    }
})
