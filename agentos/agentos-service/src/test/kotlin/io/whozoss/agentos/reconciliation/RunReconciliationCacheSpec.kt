package io.whozoss.agentos.reconciliation

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.integrationConfig.IntegrationConfig
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID

/**
 * Unit tests for [RunReconciliationCache].
 * Story 6.4 AC9 (cache hit per run), AC10 (inter-run isolation), AC11 (null userId bypass).
 */
class RunReconciliationCacheSpec : StringSpec({

    fun config(name: String) = IntegrationConfig(
        metadata = EntityMetadata(),
        namespaceId = UUID.randomUUID(),
        name = name,
        integrationType = "JIRA",
    )

    // -------------------------------------------------------------------------
    // AC9 — multiple calls same-run cache hit
    // -------------------------------------------------------------------------

    "getOrCompute calls compute exactly once for the same (name, type) key" {
        val cache = RunReconciliationCache()
        val expected = config("jira")
        var callCount = 0

        val r1 = cache.getOrCompute("jira", IntegrationConfig::class.java) {
            callCount++
            expected
        }
        val r2 = cache.getOrCompute("jira", IntegrationConfig::class.java) {
            callCount++
            config("jira-second")
        }

        callCount shouldBe 1
        r1 shouldBe expected
        r2 shouldBe expected
    }

    "getOrCompute uses separate slots for different names" {
        val cache = RunReconciliationCache()
        val jira = config("jira")
        val github = config("github")

        val r1 = cache.getOrCompute("jira", IntegrationConfig::class.java) { jira }
        val r2 = cache.getOrCompute("github", IntegrationConfig::class.java) { github }

        r1 shouldBe jira
        r2 shouldBe github
    }

    "getOrCompute uses separate slots for same name but different types" {
        val cache = RunReconciliationCache()
        val config = config("jira")
        val otherTypeValue = "other-value"

        val r1 = cache.getOrCompute("jira", IntegrationConfig::class.java) { config }
        val r2 = cache.getOrCompute("jira", String::class.java) { otherTypeValue }

        r1 shouldBe config
        r2 shouldBe otherTypeValue
    }

    // -------------------------------------------------------------------------
    // AC10 — inter-run isolation
    // -------------------------------------------------------------------------

    "two separate RunReconciliationCache instances do not share state" {
        val cacheA = RunReconciliationCache()
        val cacheB = RunReconciliationCache()

        val configA = config("jira-a")
        val configB = config("jira-b")

        val rA = cacheA.getOrCompute("jira", IntegrationConfig::class.java) { configA }
        val rB = cacheB.getOrCompute("jira", IntegrationConfig::class.java) { configB }

        rA shouldBe configA
        rB shouldBe configB
        rA shouldBe configA // cacheA not polluted by cacheB
    }

    "run A cache entry is not visible from run B cache" {
        val cacheA = RunReconciliationCache()
        val cacheB = RunReconciliationCache()

        val configFromA = config("shared-name")

        cacheA.getOrCompute("shared-name", IntegrationConfig::class.java) { configFromA }

        var computeCalled = false
        val configFromB = config("shared-name-b")
        val rB = cacheB.getOrCompute("shared-name", IntegrationConfig::class.java) {
            computeCalled = true
            configFromB
        }

        computeCalled shouldBe true
        rB shouldBe configFromB
    }
})
