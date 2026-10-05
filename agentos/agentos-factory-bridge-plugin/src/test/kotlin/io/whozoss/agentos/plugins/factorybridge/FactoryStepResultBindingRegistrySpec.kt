package io.whozoss.agentos.plugins.factorybridge

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class FactoryStepResultBindingRegistrySpec : StringSpec({
    "expired binding can only be leased explicitly for server-authorized renewal" {
        val clock = object : java.time.Clock() {
            var now = java.time.Instant.parse("2026-01-01T00:00:00Z")
            override fun getZone() = java.time.ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId) = this
            override fun instant() = now
        }
        val registry = FactoryStepResultBindingRegistry(clock)
        val caseId = java.util.UUID.randomUUID()
        val namespaceId = java.util.UUID.randomUUID()
        registry.bind(
            FactoryStepResultBinding(
                caseId,
                namespaceId,
                "Worker",
                "attempt-1",
                "runtime-1",
                "old-token-value-with-sufficient-length",
                java.time.Instant.parse("2026-01-01T00:00:01Z"),
            ),
        )
        clock.now = java.time.Instant.parse("2026-01-01T00:00:02Z")
        registry.acquire(caseId, namespaceId, "Worker") shouldBe null
        val expiredBinding = registry.acquire(caseId, namespaceId, "Worker", allowExpired = true)
        expiredBinding?.attemptId shouldBe "attempt-1"
        registry.release(expiredBinding!!)
    }

    "binding is case namespace agent scoped, bounded and volatile" {
        val now = Instant.parse("2030-01-01T00:00:00Z")
        val registry = FactoryStepResultBindingRegistry(Clock.fixed(now, ZoneOffset.UTC))
        val caseId = UUID.randomUUID()
        val ns = UUID.randomUUID()
        registry.bind(FactoryStepResultBinding(caseId, ns, "Worker", "attempt", "runtime", "secret-token-value-with-sufficient-length", now.plusSeconds(60)))
        registry.context(caseId, UUID.randomUUID(), "Worker") shouldBe emptyMap()
        registry.contains(caseId) shouldBe true
        val fresh = FactoryStepResultBindingRegistry(Clock.fixed(now, ZoneOffset.UTC))
        fresh.context(caseId, ns, "Worker") shouldBe emptyMap()
    }

    "binding survives initial idle lifecycle and remains available for the bound turn" {
        val now = Instant.parse("2030-01-01T00:00:00Z")
        val registry = FactoryStepResultBindingRegistry(Clock.fixed(now, ZoneOffset.UTC))
        val caseId = UUID.randomUUID()
        val namespaceId = UUID.randomUUID()
        registry.bind(FactoryStepResultBinding(caseId, namespaceId, "Worker", "attempt", "runtime", "secret-token-value-with-sufficient-length", now.plusSeconds(60)))

        registry.contains(caseId) shouldBe true
        registry.context(caseId, namespaceId, "Worker").isEmpty() shouldBe false
        registry.contextForCase(caseId, namespaceId).isEmpty() shouldBe false
        registry.contextForCase(caseId, UUID.randomUUID()).isEmpty() shouldBe true
    }

    "lease validates identity, excludes concurrent acquisition, and acknowledges exactly once" {
        val now = Instant.parse("2030-01-01T00:00:00Z")
        val registry = FactoryStepResultBindingRegistry(Clock.fixed(now, ZoneOffset.UTC))
        val caseId = UUID.randomUUID()
        val namespaceId = UUID.randomUUID()
        val binding = FactoryStepResultBinding(caseId, namespaceId, "Worker", "attempt", "runtime", "secret-token-value-with-sufficient-length", now.plusSeconds(60))
        registry.bind(binding)

        registry.acquire(caseId, UUID.randomUUID(), "Worker") shouldBe null
        registry.contains(caseId) shouldBe true
        registry.acquire(caseId, namespaceId, "Other") shouldBe null
        registry.contains(caseId) shouldBe true
        registry.acquire(caseId, namespaceId, "Worker") shouldBe binding
        registry.acquire(caseId, namespaceId, "Worker") shouldBe null
        registry.contains(caseId) shouldBe true
        registry.release(binding)
        registry.acquire(caseId, namespaceId, "Worker") shouldBe binding
        registry.acknowledge(binding)
        registry.contains(caseId) shouldBe false
        registry.acquire(caseId, namespaceId, "Worker") shouldBe null
    }
})
