package io.whozoss.agentos.persistence.neo4j

import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.CaseRepository
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceRepository
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.usage.UsageAggregate
import io.whozoss.agentos.usage.UsageOutcome
import io.whozoss.agentos.usage.UsageRecord
import io.whozoss.agentos.usage.UsageRecordRepository
import io.whozoss.agentos.usage.UsageSource
import org.neo4j.driver.Driver
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import java.util.UUID

/**
 * Shared persistence contract tests for [UsageRecordRepository].
 *
 * Covers:
 * - Basic CRUD: save, findById, findByCaseId
 * - Null-cost contamination in all aggregation paths
 * - Tree traversal ([:PARENT_OF] edges) for aggregateByCaseTree / sumCostByCaseTreeSince
 * - ORDER BY totalTokens DESC for aggregateByAgent and aggregateByModel
 * - The sumCostByCaseTreeSince Cypher fix (aggregations in WITH, not inside collect)
 *
 * A [Case] (and therefore a [Namespace]) must pre-exist before a [UsageRecord] is saved
 * because [io.whozoss.agentos.usage.Neo4jUsageRecordRepository.save] writes a
 * [:BELONGS_TO] edge from the UsageRecord node to the Case node.
 */
abstract class AbstractUsageRecordPersistenceSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var repo: UsageRecordRepository
    @Autowired lateinit var caseRepo: CaseRepository
    @Autowired lateinit var namespaceRepo: NamespaceRepository
    @Autowired lateinit var driver: Driver

    private fun namespace() = Namespace(metadata = EntityMetadata(), name = "test-ns")

    private fun case(namespaceId: UUID) =
        Case(metadata = EntityMetadata(), namespaceId = namespaceId, status = CaseStatus.PENDING)

    private fun record(
        caseId: UUID,
        namespaceId: UUID,
        agentName: String = "agent",
        apiModelName: String? = "gpt-4",
        inputTokens: Long = 50L,
        outputTokens: Long = 50L,
        totalTokens: Long = 100L,
        cost: Double? = 0.25,
        userId: UUID? = null,
        timestamp: Instant = Instant.now(),
    ) = UsageRecord(
        metadata = EntityMetadata(),
        namespaceId = namespaceId,
        caseId = caseId,
        userId = userId,
        source = UsageSource.LLM,
        outcome = UsageOutcome.COMPLETED,
        agentName = agentName,
        apiModelName = apiModelName,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        cacheReadTokens = 0L,
        cacheWriteTokens = 0L,
        totalTokens = totalTokens,
        cost = cost,
        timestamp = timestamp,
    )

    init {
        beforeEach { Neo4jContainerSupport.clearDatabase(driver) }

        // =====================================================================
        // Basic CRUD
        // =====================================================================

        "save and findById returns the same record" {
            val ns = namespaceRepo.save(namespace())
            val case = caseRepo.save(case(ns.id))
            val saved = repo.save(record(case.id, ns.id))

            val found = repo.findById(saved.id)
            found shouldNotBe null
            found!!.id shouldBe saved.id
            found.caseId shouldBe case.id
            found.agentName shouldBe "agent"
        }

        "findById returns null for unknown id" {
            repo.findById(UUID.randomUUID()) shouldBe null
        }

        "findByCaseId returns all records for a case" {
            val ns = namespaceRepo.save(namespace())
            val case1 = caseRepo.save(case(ns.id))
            val case2 = caseRepo.save(case(ns.id))
            repo.save(record(case1.id, ns.id))
            repo.save(record(case1.id, ns.id))
            repo.save(record(case2.id, ns.id))

            repo.findByCaseId(case1.id) shouldHaveSize 2
            repo.findByCaseId(case2.id) shouldHaveSize 1
        }

        "findByCaseId returns empty list when no records exist" {
            repo.findByCaseId(UUID.randomUUID()).shouldBeEmpty()
        }

        // =====================================================================
        // aggregateByCaseId
        // =====================================================================

        "aggregateByCaseId returns EMPTY when no records exist" {
            repo.aggregateByCaseId(UUID.randomUUID()) shouldBe UsageAggregate.EMPTY
        }

        "aggregateByCaseId sums tokens and cost" {
            val ns = namespaceRepo.save(namespace())
            val case = caseRepo.save(case(ns.id))
            repo.save(record(case.id, ns.id, totalTokens = 100L, cost = 0.25))
            repo.save(record(case.id, ns.id, totalTokens = 200L, cost = 0.50))

            val agg = repo.aggregateByCaseId(case.id)
            agg.totalTokens shouldBe 300L
            agg.recordCount shouldBe 2L
            agg.cost!! shouldBe (0.75 plusOrMinus 1e-9)
        }

        "aggregateByCaseId: null cost contaminates the aggregate" {
            val ns = namespaceRepo.save(namespace())
            val case = caseRepo.save(case(ns.id))
            repo.save(record(case.id, ns.id, totalTokens = 100L, cost = 0.25))
            repo.save(record(case.id, ns.id, totalTokens = 200L, cost = null))

            val agg = repo.aggregateByCaseId(case.id)
            agg.totalTokens shouldBe 300L
            agg.cost shouldBe null
        }

        // =====================================================================
        // aggregateByCaseTree
        // =====================================================================

        "aggregateByCaseTree includes root and descendants" {
            val ns = namespaceRepo.save(namespace())
            val root = caseRepo.save(case(ns.id))
            val child = caseRepo.save(case(ns.id).copy(parentCaseId = root.id))
            caseRepo.linkParentToChild(root.id, child.id)
            repo.save(record(root.id, ns.id, totalTokens = 100L, cost = 0.25))
            repo.save(record(child.id, ns.id, totalTokens = 200L, cost = 0.50))

            val agg = repo.aggregateByCaseTree(root.id)
            agg.totalTokens shouldBe 300L
            agg.recordCount shouldBe 2L
            agg.cost!! shouldBe (0.75 plusOrMinus 1e-9)
        }

        "aggregateByCaseTree: null cost in descendant contaminates tree total" {
            val ns = namespaceRepo.save(namespace())
            val root = caseRepo.save(case(ns.id))
            val child = caseRepo.save(case(ns.id).copy(parentCaseId = root.id))
            caseRepo.linkParentToChild(root.id, child.id)
            repo.save(record(root.id, ns.id, totalTokens = 100L, cost = 0.25))
            repo.save(record(child.id, ns.id, totalTokens = 200L, cost = null))

            val agg = repo.aggregateByCaseTree(root.id)
            agg.totalTokens shouldBe 300L
            agg.cost shouldBe null
        }

        "aggregateByCaseTree does not include unrelated cases" {
            val ns = namespaceRepo.save(namespace())
            val root = caseRepo.save(case(ns.id))
            val unrelated = caseRepo.save(case(ns.id))
            repo.save(record(root.id, ns.id, totalTokens = 100L, cost = 0.25))
            repo.save(record(unrelated.id, ns.id, totalTokens = 999L, cost = 9.99))

            val agg = repo.aggregateByCaseTree(root.id)
            agg.totalTokens shouldBe 100L
            agg.recordCount shouldBe 1L
        }

        // =====================================================================
        // aggregateByAgent — including ORDER BY
        // =====================================================================

        "aggregateByAgent groups by agent name" {
            val ns = namespaceRepo.save(namespace())
            val case = caseRepo.save(case(ns.id))
            val ts = Instant.now()
            repo.save(record(case.id, ns.id, agentName = "alpha", totalTokens = 100L, cost = 0.25, timestamp = ts))
            repo.save(record(case.id, ns.id, agentName = "alpha", totalTokens = 200L, cost = 0.50, timestamp = ts))
            repo.save(record(case.id, ns.id, agentName = "beta", totalTokens = 50L, cost = 0.125, timestamp = ts))

            val results = repo.aggregateByAgent(ns.id, ts.minusSeconds(1), ts.plusSeconds(1))
            results shouldHaveSize 2
            results.find { it.key == "alpha" }!!.aggregate.totalTokens shouldBe 300L
            results.find { it.key == "beta" }!!.aggregate.totalTokens shouldBe 50L
        }

        "aggregateByAgent returns groups ordered by totalTokens descending" {
            val ns = namespaceRepo.save(namespace())
            val case = caseRepo.save(case(ns.id))
            val ts = Instant.now()
            repo.save(record(case.id, ns.id, agentName = "low", totalTokens = 50L, timestamp = ts))
            repo.save(record(case.id, ns.id, agentName = "high", totalTokens = 300L, timestamp = ts))
            repo.save(record(case.id, ns.id, agentName = "mid", totalTokens = 150L, timestamp = ts))

            val results = repo.aggregateByAgent(ns.id, ts.minusSeconds(1), ts.plusSeconds(1))
            results.map { it.key } shouldBe listOf("high", "mid", "low")
        }

        "aggregateByAgent: null cost contaminates agent group" {
            val ns = namespaceRepo.save(namespace())
            val case = caseRepo.save(case(ns.id))
            val ts = Instant.now()
            repo.save(record(case.id, ns.id, agentName = "alpha", totalTokens = 100L, cost = 0.25, timestamp = ts))
            repo.save(record(case.id, ns.id, agentName = "alpha", totalTokens = 200L, cost = null, timestamp = ts))

            val results = repo.aggregateByAgent(ns.id, ts.minusSeconds(1), ts.plusSeconds(1))
            results shouldHaveSize 1
            results.first().aggregate.totalTokens shouldBe 300L
            results.first().aggregate.cost shouldBe null
        }

        // =====================================================================
        // aggregateByModel — including ORDER BY
        // =====================================================================

        "aggregateByModel groups by model name with unknown fallback" {
            val ns = namespaceRepo.save(namespace())
            val case = caseRepo.save(case(ns.id))
            val ts = Instant.now()
            repo.save(record(case.id, ns.id, apiModelName = "gpt-4", totalTokens = 100L, timestamp = ts))
            repo.save(record(case.id, ns.id, apiModelName = null, totalTokens = 50L, timestamp = ts))

            val results = repo.aggregateByModel(ns.id, ts.minusSeconds(1), ts.plusSeconds(1))
            results shouldHaveSize 2
            results.find { it.key == "gpt-4" } shouldNotBe null
            results.find { it.key == "unknown" } shouldNotBe null
        }

        "aggregateByModel returns groups ordered by totalTokens descending" {
            val ns = namespaceRepo.save(namespace())
            val case = caseRepo.save(case(ns.id))
            val ts = Instant.now()
            repo.save(record(case.id, ns.id, apiModelName = "gpt-4", totalTokens = 100L, timestamp = ts))
            repo.save(record(case.id, ns.id, apiModelName = "claude-3", totalTokens = 500L, timestamp = ts))
            repo.save(record(case.id, ns.id, apiModelName = null, totalTokens = 50L, timestamp = ts))

            val results = repo.aggregateByModel(ns.id, ts.minusSeconds(1), ts.plusSeconds(1))
            results.map { it.key } shouldBe listOf("claude-3", "gpt-4", "unknown")
        }

        // =====================================================================
        // sumCostByCaseTreeSince
        // =====================================================================

        "sumCostByCaseTreeSince returns null when no records exist" {
            repo.sumCostByCaseTreeSince(UUID.randomUUID(), Instant.now()) shouldBe null
        }

        "sumCostByCaseTreeSince sums costs for root and descendants" {
            val ns = namespaceRepo.save(namespace())
            val root = caseRepo.save(case(ns.id))
            val child = caseRepo.save(case(ns.id).copy(parentCaseId = root.id))
            caseRepo.linkParentToChild(root.id, child.id)
            val since = Instant.parse("2025-01-01T10:00:00Z")
            repo.save(record(root.id, ns.id, totalTokens = 100L, cost = 0.25, timestamp = since))
            repo.save(record(child.id, ns.id, totalTokens = 200L, cost = 0.50, timestamp = since.plusSeconds(300)))

            val result = repo.sumCostByCaseTreeSince(root.id, since)
            result shouldNotBe null
            result!! shouldBe (0.75 plusOrMinus 1e-9)
        }

        "sumCostByCaseTreeSince excludes records before the since instant" {
            val ns = namespaceRepo.save(namespace())
            val root = caseRepo.save(case(ns.id))
            val since = Instant.parse("2025-01-01T10:00:00Z")
            // before since — must be excluded
            repo.save(record(root.id, ns.id, totalTokens = 999L, cost = 9.99, timestamp = since.minusSeconds(1)))
            // at exactly since — must be included (inclusive bound)
            repo.save(record(root.id, ns.id, totalTokens = 100L, cost = 0.25, timestamp = since))

            val result = repo.sumCostByCaseTreeSince(root.id, since)
            result shouldNotBe null
            result!! shouldBe (0.25 plusOrMinus 1e-9)
        }

        "sumCostByCaseTreeSince returns null when any record has null cost" {
            val ns = namespaceRepo.save(namespace())
            val root = caseRepo.save(case(ns.id))
            val since = Instant.parse("2025-01-01T10:00:00Z")
            repo.save(record(root.id, ns.id, totalTokens = 100L, cost = 0.25, timestamp = since))
            repo.save(record(root.id, ns.id, totalTokens = 200L, cost = null, timestamp = since))

            repo.sumCostByCaseTreeSince(root.id, since) shouldBe null
        }

        "sumCostByCaseTreeSince does not include records from unrelated cases" {
            val ns = namespaceRepo.save(namespace())
            val root = caseRepo.save(case(ns.id))
            val unrelated = caseRepo.save(case(ns.id))
            val since = Instant.parse("2025-01-01T10:00:00Z")
            repo.save(record(root.id, ns.id, totalTokens = 100L, cost = 0.25, timestamp = since))
            repo.save(record(unrelated.id, ns.id, totalTokens = 999L, cost = 9.99, timestamp = since))

            val result = repo.sumCostByCaseTreeSince(root.id, since)
            result!! shouldBe (0.25 plusOrMinus 1e-9)
        }
    }
}
