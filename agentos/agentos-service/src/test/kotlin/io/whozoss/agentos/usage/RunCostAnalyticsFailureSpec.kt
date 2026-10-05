package io.whozoss.agentos.usage

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.caseEvent.CaseEventService
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.CaseRepository
import io.whozoss.agentos.chat.UsageAccumulator
import io.whozoss.agentos.config.UsageConfigProperties
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.sdk.usage.LlmUsage
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletionException

class RunCostAnalyticsFailureSpec : StringSpec({
    "an unlimited run continues collecting but never reports missing history as a complete zero" {
        val fixture = AnalyticsFailureFixture()
        val case = fixture.case()
        every { fixture.records.sumCostByCaseTreeSince(any(), any()) } throws fixture.failure
        val usage = UsageAccumulator()

        val registration = fixture.service.register(case.id, usage)
        registration.beforeCall().join()
        usage.record(LlmUsage(totalTokens = 12, estimatedCostUsd = 3.0))
        shouldThrow<ResponseStatusException> { fixture.service.state(case.id) }
            .statusCode shouldBe HttpStatus.SERVICE_UNAVAILABLE

        // A recovered read cannot make an already incomplete live snapshot trustworthy.
        every { fixture.records.sumCostByCaseTreeSince(any(), any()) } returns UsageCostAggregate(7.0, 0)
        shouldThrow<ResponseStatusException> { fixture.service.state(case.id) }
            .statusCode shouldBe HttpStatus.SERVICE_UNAVAILABLE
        var persisted = false
        registration.finish {
            usage.snapshot().knownCost shouldBe 3.0
            persisted = true
        }
        persisted shouldBe true
        fixture.service.state(case.id).let {
            it.cost shouldBe 7.0
            it.active shouldBe false
        }
    }

    "an unlimited delegated run survives a parent history failure and keeps stop propagation" {
        val fixture = AnalyticsFailureFixture()
        val parent = fixture.case()
        val child = fixture.case(parentId = parent.id)
        every { fixture.records.sumCostByCaseTreeSince(parent.id, any()) } throws fixture.failure
        val usage = UsageAccumulator()
        val registration = fixture.service.register(child.id, usage)

        registration.beforeCall().join()
        shouldThrow<ResponseStatusException> { fixture.service.state(parent.id) }
            .statusCode shouldBe HttpStatus.SERVICE_UNAVAILABLE
        fixture.service.stop(parent.id)
        shouldThrow<CompletionException> { registration.beforeCall().join() }
            .cause!!::class shouldBe CostRunStopped::class
        registration.finish {}
        every { fixture.records.sumCostByCaseTreeSince(any(), any()) } returns null
        fixture.service.state(parent.id).active shouldBe false
        fixture.service.state(child.id).active shouldBe false
    }

    for (threshold in listOf(0.0, 10.0)) {
        for (source in listOf("case", "namespace", "parent")) {
            "$source threshold $threshold prevents bypassing a failed history read" {
                val fixture = AnalyticsFailureFixture()
                val parent = if (source == "parent") fixture.case(threshold = threshold) else null
                val case = fixture.case(
                    threshold = threshold.takeIf { source == "case" },
                    parentId = parent?.id,
                )
                if (source == "namespace") {
                    every { fixture.namespaces.resolveRunCostThreshold(any()) } returns threshold
                }
                every { fixture.records.sumCostByCaseTreeSince(any(), any()) } throws fixture.failure
                shouldThrow<IllegalStateException> {
                    fixture.service.register(case.id, UsageAccumulator())
                } shouldBe fixture.failure
            }
        }
    }

    "a limited ancestor failure leaves no partial child registration" {
        val fixture = AnalyticsFailureFixture()
        val parent = fixture.case(threshold = 10.0)
        val child = fixture.case(parentId = parent.id)
        every { fixture.records.sumCostByCaseTreeSince(parent.id, any()) } throws fixture.failure
        val usage = UsageAccumulator()
        val originalGate = usage.beforeCall

        shouldThrow<IllegalStateException> { fixture.service.register(child.id, usage) } shouldBe fixture.failure
        usage.beforeCall shouldBe originalGate
        every { fixture.records.sumCostByCaseTreeSince(parent.id, any()) } returns null
        fixture.service.state(child.id).active shouldBe false
        fixture.service.state(parent.id).active shouldBe false
    }

    "a running parent's snapshotted limit still prevents bypass after its configured limit changes" {
        val fixture = AnalyticsFailureFixture()
        val parent = fixture.case(threshold = 10.0)
        val parentRegistration = fixture.service.register(parent.id, UsageAccumulator())
        fixture.cases[parent.id] = parent.copy(runCostThreshold = null)
        val child = fixture.case(parentId = parent.id)
        every { fixture.records.sumCostByCaseTreeSince(child.id, any()) } throws fixture.failure

        shouldThrow<IllegalStateException> { fixture.service.register(child.id, UsageAccumulator()) } shouldBe fixture.failure
        fixture.service.state(parent.id).runCostThreshold shouldBe 10.0
        fixture.service.state(parent.id).active shouldBe true
        parentRegistration.finish {}
        fixture.service.state(parent.id).active shouldBe false
    }

    "a running unlimited parent keeps its threshold snapshot and accepts an unlimited child during a read failure" {
        val fixture = AnalyticsFailureFixture()
        val parent = fixture.case()
        val parentRegistration = fixture.service.register(parent.id, UsageAccumulator())
        fixture.cases[parent.id] = parent.copy(runCostThreshold = 10.0)
        val child = fixture.case(parentId = parent.id)
        every { fixture.records.sumCostByCaseTreeSince(child.id, any()) } throws fixture.failure
        val childUsage = UsageAccumulator()

        val childRegistration = fixture.service.register(child.id, childUsage)
        childRegistration.beforeCall().join()
        childUsage.record(LlmUsage(totalTokens = 12, estimatedCostUsd = 3.0))
        fixture.service.state(parent.id).runCostThreshold shouldBe null
        fixture.service.state(parent.id).cost shouldBe 3.0
        childRegistration.finish {}
        parentRegistration.finish {}
    }

    "a limited child cannot join a live lineage whose historical cost is unavailable" {
        val fixture = AnalyticsFailureFixture()
        val parent = fixture.case()
        every { fixture.records.sumCostByCaseTreeSince(parent.id, any()) } throws fixture.failure
        val parentRegistration = fixture.service.register(parent.id, UsageAccumulator())
        val child = fixture.case(threshold = 10.0, parentId = parent.id)

        shouldThrow<ResponseStatusException> { fixture.service.register(child.id, UsageAccumulator()) }
            .statusCode shouldBe HttpStatus.SERVICE_UNAVAILABLE
        fixture.service.state(child.id).active shouldBe false
        parentRegistration.finish {}
    }

    "history reads outside an active run still report their failure" {
        val fixture = AnalyticsFailureFixture()
        val case = fixture.case()
        every { fixture.records.sumCostByCaseTreeSince(any(), any()) } throws fixture.failure
        shouldThrow<IllegalStateException> { fixture.service.state(case.id) } shouldBe fixture.failure
    }

    "event history failures are not treated as optional analytics failures" {
        val fixture = AnalyticsFailureFixture()
        val case = fixture.case()
        every { fixture.events.findByParent(any()) } throws fixture.failure
        shouldThrow<IllegalStateException> { fixture.service.register(case.id, UsageAccumulator()) } shouldBe fixture.failure
        verify(exactly = 0) { fixture.records.sumCostByCaseTreeSince(any(), any()) }
    }

    "cancellation from an analytics read is propagated even without a threshold" {
        val fixture = AnalyticsFailureFixture()
        val case = fixture.case()
        val cancelled = CancellationException("cancelled")
        every { fixture.records.sumCostByCaseTreeSince(any(), any()) } throws cancelled
        shouldThrow<CancellationException> { fixture.service.register(case.id, UsageAccumulator()) } shouldBe cancelled
    }
})

private class AnalyticsFailureFixture {
    val cases = mutableMapOf<UUID, Case>()
    val events = mockk<CaseEventService>()
    val namespaces = mockk<NamespaceService>()
    val records = mockk<UsageRecordService>()
    val failure = IllegalStateException("usage history unavailable")
    val service: RunCostService

    init {
        val repository = mockk<CaseRepository>()
        every { repository.findById(any()) } answers { cases[firstArg()] }
        every { events.findByParent(any()) } returns emptyList()
        every { namespaces.resolveRunCostThreshold(any()) } returns null
        every { records.sumCostByCaseTreeSince(any(), any()) } returns null
        service = RunCostService(repository, events, namespaces, records, UsageConfigProperties(enabled = true))
    }

    fun case(threshold: Double? = null, parentId: UUID? = null): Case =
        Case(namespaceId = UUID.randomUUID(), parentCaseId = parentId, runCostThreshold = threshold)
            .also { cases[it.id] = it }
}
