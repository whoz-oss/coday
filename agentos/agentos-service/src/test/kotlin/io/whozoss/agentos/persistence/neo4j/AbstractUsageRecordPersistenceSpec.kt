package io.whozoss.agentos.persistence.neo4j

import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.CaseRepository
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceRepository
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
 * Shared persistence contract tests for [UsageRecordRepository] aggregation queries.
 *
 * Subclasses activate a specific persistence mode (Testcontainers or embedded harness)
 * and inherit all test cases, ensuring both modes satisfy the same contract.
 *
 * Cost values use exact binary-representable doubles (0.25, 0.50, 0.75, 1.0, 0.125)
 * to avoid floating-point accumulation errors in assertions.
 */
abstract class AbstractUsageRecordPersistenceSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    lateinit var repo: UsageRecordRepository

    @Autowired
    lateinit var caseRepo: CaseRepository

    @Autowired
    lateinit var namespaceRepo: NamespaceRepository

    @Autowired
    lateinit var driver: Driver

    private val nsId: UUID = UUID.randomUUID()
    private val userId: UUID = UUID.randomUUID()

    private fun namespace() = Namespace(metadata = EntityMetadata(), name = "test-ns")
    private fun case(namespaceId: UUID) = Case(metadata = EntityMetadata(), namespaceId = namespaceId)

    private fun record(
        caseId: UUID,
        namespaceId: UUID,
        agentName: String = "agent",
        apiModelName: String? = "gpt-4",
        totalTokens: Long = 100L,
        inputTokens: Long = 50L,
        cost: Double? = 0.25,
        currency: String = "USD",
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
        totalTokens = totalTokens,
        inputTokens = inputTokens,
        cost = cost,
        currency = currency,
        timestamp = timestamp,
    )

    init {
        beforeEach { Neo4jContainerSupport.clearDatabase(driver) }

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
            agg.costByCurrency["USD"] shouldBe 0.75
        }

        "aggregateByCaseId: null cost contaminates group total, tokens still sum" {
            val ns = namespaceRepo.save(namespace())
            val case = caseRepo.save(case(ns.id))
            repo.save(record(case.id, ns.id, totalTokens = 100L, cost = 0.25))
            repo.save(record(case.id, ns.id, totalTokens = 200L, cost = null))

            val agg = repo.aggregateByCaseId(case.id)
            agg.totalTokens shouldBe 300L
            agg.costByCurrency["USD"] shouldBe null
        }

        "aggregateByCaseId: multi-currency records produce separate cost entries" {
            val ns = namespaceRepo.save(namespace())
            val case = caseRepo.save(case(ns.id))
            repo.save(record(case.id, ns.id, totalTokens = 100L, cost = 0.25, currency = "USD"))
            repo.save(record(case.id, ns.id, totalTokens = 50L, cost = 0.50, currency = "EUR"))

            val agg = repo.aggregateByCaseId(case.id)
            agg.totalTokens shouldBe 150L
            ("USD" in agg.costByCurrency) shouldBe true
            ("EUR" in agg.costByCurrency) shouldBe true
            agg.costByCurrency["USD"] shouldBe 0.25
            agg.costByCurrency["EUR"] shouldBe 0.50
        }

        // =====================================================================
        // aggregateByCaseTree
        // =====================================================================

        "aggregateByCaseTree includes root case records" {
            val ns = namespaceRepo.save(namespace())
            val root = caseRepo.save(case(ns.id))
            repo.save(record(root.id, ns.id, totalTokens = 100L, cost = 0.25))

            val agg = repo.aggregateByCaseTree(root.id)
            agg.totalTokens shouldBe 100L
            agg.recordCount shouldBe 1L
        }

        "aggregateByCaseTree includes two-level delegation tree" {
            val ns = namespaceRepo.save(namespace())
            val root = caseRepo.save(case(ns.id))
            val child = caseRepo.save(case(ns.id).copy(parentCaseId = root.id))
            val grandchild = caseRepo.save(case(ns.id).copy(parentCaseId = child.id))
            // Create the [:PARENT_OF] edges that the tree query traverses
            caseRepo.linkParentToChild(root.id, child.id)
            caseRepo.linkParentToChild(child.id, grandchild.id)

            // 0.25 + 0.50 + 0.25 = 1.0 exactly (binary-representable)
            repo.save(record(root.id, ns.id, totalTokens = 100L, cost = 0.25))
            repo.save(record(child.id, ns.id, totalTokens = 200L, cost = 0.50))
            repo.save(record(grandchild.id, ns.id, totalTokens = 300L, cost = 0.25))

            val agg = repo.aggregateByCaseTree(root.id)
            agg.totalTokens shouldBe 600L
            agg.recordCount shouldBe 3L
            agg.costByCurrency["USD"] shouldBe 1.0
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
            agg.costByCurrency["USD"] shouldBe null
        }

        "aggregateByCaseTree does not include records from unrelated cases" {
            val ns = namespaceRepo.save(namespace())
            val root = caseRepo.save(case(ns.id))
            val unrelated = caseRepo.save(case(ns.id))
            repo.save(record(root.id, ns.id, totalTokens = 100L))
            repo.save(record(unrelated.id, ns.id, totalTokens = 999L))

            val agg = repo.aggregateByCaseTree(root.id)
            agg.totalTokens shouldBe 100L
            agg.recordCount shouldBe 1L
        }

        // =====================================================================
        // aggregateByUser
        // =====================================================================

        "aggregateByUser sums records within the time window" {
            val ns = namespaceRepo.save(namespace())
            val case = caseRepo.save(case(ns.id))
            val t1 = Instant.parse("2024-06-01T10:00:00Z")
            val t2 = Instant.parse("2024-06-15T10:00:00Z")
            repo.save(record(case.id, ns.id, totalTokens = 100L, cost = 0.25, userId = userId, timestamp = t1))
            repo.save(record(case.id, ns.id, totalTokens = 200L, cost = 0.50, userId = userId, timestamp = t2))

            val from = Instant.parse("2024-06-01T00:00:00Z")
            val to = Instant.parse("2024-06-30T23:59:59Z")
            val agg = repo.aggregateByUser(userId, ns.id, from, to)
            agg.totalTokens shouldBe 300L
            agg.costByCurrency["USD"] shouldBe 0.75
        }

        "aggregateByUser excludes records outside the time window" {
            val ns = namespaceRepo.save(namespace())
            val case = caseRepo.save(case(ns.id))
            val inside = Instant.parse("2024-06-15T10:00:00Z")
            val outside = Instant.parse("2024-07-15T10:00:00Z")
            repo.save(record(case.id, ns.id, totalTokens = 100L, userId = userId, timestamp = inside))
            repo.save(record(case.id, ns.id, totalTokens = 999L, userId = userId, timestamp = outside))

            val from = Instant.parse("2024-06-01T00:00:00Z")
            val to = Instant.parse("2024-06-30T23:59:59Z")
            val agg = repo.aggregateByUser(userId, ns.id, from, to)
            agg.totalTokens shouldBe 100L
            agg.recordCount shouldBe 1L
        }

        // =====================================================================
        // aggregateByAgent
        // =====================================================================

        "aggregateByAgent groups records by agent name" {
            val ns = namespaceRepo.save(namespace())
            val case = caseRepo.save(case(ns.id))
            val ts = Instant.now()
            repo.save(record(case.id, ns.id, agentName = "alpha", totalTokens = 100L, cost = 0.25, timestamp = ts))
            repo.save(record(case.id, ns.id, agentName = "alpha", totalTokens = 200L, cost = 0.50, timestamp = ts))
            repo.save(record(case.id, ns.id, agentName = "beta", totalTokens = 50L, cost = 0.125, timestamp = ts))

            val from = ts.minusSeconds(60)
            val to = ts.plusSeconds(60)
            val results = repo.aggregateByAgent(ns.id, from, to)
            results shouldHaveSize 2
            val alpha = results.find { it.key == "alpha" }
            alpha shouldNotBe null
            alpha!!.aggregate.totalTokens shouldBe 300L
            alpha.aggregate.costByCurrency["USD"] shouldBe 0.75
        }

        "aggregateByAgent: null cost contaminates the agent group total" {
            val ns = namespaceRepo.save(namespace())
            val case = caseRepo.save(case(ns.id))
            val ts = Instant.now()
            repo.save(record(case.id, ns.id, agentName = "alpha", totalTokens = 100L, cost = 0.25, timestamp = ts))
            repo.save(record(case.id, ns.id, agentName = "alpha", totalTokens = 200L, cost = null, timestamp = ts))

            val from = ts.minusSeconds(60)
            val to = ts.plusSeconds(60)
            val results = repo.aggregateByAgent(ns.id, from, to)
            results shouldHaveSize 1
            val alpha = results.first()
            alpha.aggregate.totalTokens shouldBe 300L
            alpha.aggregate.costByCurrency["USD"] shouldBe null
        }

        // =====================================================================
        // aggregateByModel
        // =====================================================================

        "aggregateByModel groups by model name, null model maps to 'unknown'" {
            val ns = namespaceRepo.save(namespace())
            val case = caseRepo.save(case(ns.id))
            val ts = Instant.now()
            repo.save(record(case.id, ns.id, apiModelName = "gpt-4", totalTokens = 100L, timestamp = ts))
            repo.save(record(case.id, ns.id, apiModelName = null, totalTokens = 50L, timestamp = ts))

            val from = ts.minusSeconds(60)
            val to = ts.plusSeconds(60)
            val results = repo.aggregateByModel(ns.id, from, to)
            results shouldHaveSize 2
            results.find { it.key == "gpt-4" } shouldNotBe null
            results.find { it.key == "unknown" } shouldNotBe null
        }
    }
}
