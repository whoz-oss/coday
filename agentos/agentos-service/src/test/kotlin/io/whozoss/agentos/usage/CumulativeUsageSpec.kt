package io.whozoss.agentos.usage

import io.kotest.core.spec.style.StringSpec
import io.kotest.assertions.throwables.shouldThrow
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CumulativeUsageSpec : StringSpec({
    class Fixture {
        val root = Case(namespaceId = UUID.randomUUID())
        val records = mockk<UsageRecordService>()
        val service: RunCostService

        init {
            val cases = mockk<CaseRepository>()
            every { cases.findById(root.id) } returns root
            val events = mockk<CaseEventService>()
            every { events.findByParent(any()) } returns emptyList()
            val namespaces = mockk<NamespaceService>()
            every { namespaces.resolveRunCostThreshold(any()) } returns null
            every { records.sumCostByCaseTreeSince(any(), any()) } returns null
            service = RunCostService(cases, events, namespaces, records, UsageConfigProperties(enabled = true))
        }
    }

    "history is read outside the service monitor so a slow database never blocks the cost gates" {
        val f = Fixture()
        val readStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        every { f.records.aggregateByCaseTree(f.root.id) } answers {
            readStarted.countDown()
            release.await(10, TimeUnit.SECONDS)
            UsageAggregate.EMPTY
        }
        val reader = Thread { f.service.cumulativeUsage(listOf(f.root.id)) }.also { it.start() }
        readStarted.await(5, TimeUnit.SECONDS) shouldBe true
        // Every platform gate synchronizes on the service: it must answer while the history read is stuck.
        val gate = CountDownLatch(1)
        Thread {
            f.service.isPaused(f.root.id)
            f.service.stop(f.root.id)
            gate.countDown()
        }.start()
        val answered = gate.await(2, TimeUnit.SECONDS)
        release.countDown()
        reader.join(5_000)
        answered shouldBe true
    }

    "a live to persisted handoff during the history read is retried, never double counted or lost" {
        val f = Fixture()
        val usage = UsageAccumulator()
        val registration = f.service.register(f.root.id, usage)
        usage.record(LlmUsage(inputTokens = 4, outputTokens = 6, totalTokens = 10, estimatedCostUsd = 2.0))
        var persisted = UsageAggregate.EMPTY
        var reads = 0
        every { f.records.aggregateByCaseTree(f.root.id) } answers {
            reads++
            val read = persisted
            if (reads == 1) {
                // finish() persists the accumulator and removes it from the live set while this read is in flight:
                // the stale (empty) read plus the now-gone live snapshot would lose the 10 tokens.
                registration.finish { persisted = UsageAggregate(1, 4, 6, 0, 0, 10, 2.0, 0) }
            }
            read
        }
        val result = f.service.cumulativeUsage(listOf(f.root.id))
        reads shouldBe 2
        result.totalTokens shouldBe 10
        result.cost shouldBe 2.0
        result.liveTokens shouldBe 0
    }

    "a read that never settles ends in an explicit 503 instead of a partial figure" {
        val f = Fixture()
        var reads = 0
        every { f.records.aggregateByCaseTree(f.root.id) } answers {
            reads++
            // A new run finishes during every single read: the handoff generation never holds still.
            val usage = UsageAccumulator()
            f.service.register(f.root.id, usage).finish { }
            UsageAggregate.EMPTY
        }
        val failure = shouldThrow<ResponseStatusException> { f.service.cumulativeUsage(listOf(f.root.id)) }
        failure.statusCode shouldBe HttpStatus.SERVICE_UNAVAILABLE
        reads shouldBe 5
    }
    "lifetime union deduplicates parents children live registrations and survives persistence and retry" {
        val parent = Case(namespaceId = UUID.randomUUID())
        val child = Case(namespaceId = parent.namespaceId, parentCaseId = parent.id)
        val cases = mockk<CaseRepository>()
        every { cases.findById(parent.id) } returns parent
        every { cases.findById(child.id) } returns child
        val events = mockk<CaseEventService>()
        every { events.findByParent(any()) } returns emptyList()
        val namespaces = mockk<NamespaceService>()
        every { namespaces.resolveRunCostThreshold(any()) } returns null
        val records = mockk<UsageRecordService>()
        every { records.sumCostByCaseTreeSince(any(), any()) } returns null
        var persisted = UsageAggregate(1, 10, 5, 3, 2, 20, 1.0, 0)
        every { records.aggregateByCaseTree(parent.id) } answers { persisted }
        val service = RunCostService(cases, events, namespaces, records, UsageConfigProperties(enabled = true))
        val usage = UsageAccumulator()
        val registration = service.register(child.id, usage)
        usage.record(LlmUsage(inputTokens = 10, outputTokens = 5, cacheReadTokens = 3, cacheWriteTokens = 2, totalTokens = 20, estimatedCostUsd = 2.0))
        val live = service.cumulativeUsage(listOf(parent.id, child.id, parent.id))
        live.totalTokens shouldBe 40
        live.inputTokens shouldBe 20
        live.outputTokens shouldBe 10
        live.cacheReadTokens shouldBe 6
        live.cacheWriteTokens shouldBe 4
        live.cost shouldBe 3.0
        registration.finish { persisted = persisted.copy(recordCount = 2, inputTokens = 20, outputTokens = 10, cacheReadTokens = 6, cacheWriteTokens = 4, totalTokens = 40, cost = 3.0) }
        service.cumulativeUsage(listOf(parent.id, child.id)) shouldBe live.copy(liveTokens = 0)
        val retry = UsageAccumulator()
        val retryRegistration = service.register(child.id, retry)
        retry.record(LlmUsage(totalTokens = 7, estimatedCostUsd = null))
        service.cumulativeUsage(listOf(parent.id)).let {
            it.cost shouldBe 3.0
            it.totalTokens shouldBe 47
            it.unknownCostCount shouldBe 1
        }
        retryRegistration.finish { persisted = persisted.copy(totalTokens = 47, unknownCostCount = 1) }
        service.cumulativeUsage(listOf(parent.id)).totalTokens shouldBe 47
        verify(exactly = 0) { records.aggregateByCaseTree(child.id) }
        // Historical read failure is unavailable, not an invented empty aggregate.
        every { records.aggregateByCaseTree(parent.id) } throws IllegalStateException("unavailable")
        shouldThrow<IllegalStateException> { service.cumulativeUsage(listOf(parent.id)) }
    }
})
