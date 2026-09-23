package io.whozoss.agentos.usage

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.caseEvent.CaseEventService
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.CaseRepository
import io.whozoss.agentos.chat.UsageAccumulator
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.sdk.usage.LlmUsage
import org.springframework.web.server.ResponseStatusException
import java.util.UUID
import java.util.concurrent.CompletionException

class RunCostServiceSpec :
    StringSpec({
        fun fixture(threshold: Double? = 10.0): Triple<RunCostService, Case, MutableMap<UUID, Case>> {
            val case = Case(namespaceId = UUID.randomUUID(), runCostThreshold = threshold)
            val saved = mutableMapOf(case.id to case)
            val repo = mockk<CaseRepository>()
            every { repo.findById(any()) } answers { saved[firstArg()] }
            every { repo.save(any()) } answers { firstArg<Case>().also { saved[it.id] = it } }
            val events = mockk<CaseEventService>()
            every { events.findByParent(any()) } returns emptyList()
            val namespaces = mockk<NamespaceService>()
            every { namespaces.resolveRunCostThreshold(any()) } returns null
            val records = mockk<UsageRecordService>()
            every { records.sumCostByCaseTreeSince(any(), any()) } returns null
            return Triple(RunCostService(repo, events, namespaces, records), case, saved)
        }

        "threshold pauses next call and explicit continuation doubles it without replay" {
            val (service, case, saved) = fixture()
            val usage = UsageAccumulator()
            val registration = service.register(case.id, usage)
            registration.beforeCall().isDone shouldBe true
            usage.record(LlmUsage(totalTokens = 100, estimatedCostUsd = 12.0))
            val pending = registration.beforeCall()
            pending.isDone shouldBe false
            service.state(case.id).paused shouldBe true
            service.continueRun(case.id, 10.0).runCostThreshold shouldBe 20.0
            pending.join()
            saved.getValue(case.id).runCostThreshold shouldBe 20.0
            registration.beforeCall().isDone shouldBe true
            usage.total.estimatedCostUsd shouldBe 12.0
        }

        "a duplicate confirmation cannot double the threshold twice" {
            val (service, case, saved) = fixture()
            val usage = UsageAccumulator()
            service.register(case.id, usage)
            usage.record(LlmUsage(totalTokens = 1, estimatedCostUsd = 12.0))
            usage.beforeCall()
            service.continueRun(case.id, 10.0)
            shouldThrow<ResponseStatusException> { service.continueRun(case.id, 10.0) }
            saved.getValue(case.id).runCostThreshold shouldBe 20.0
        }

        "large overshoot requires separate confirmations for each doubling" {
            val (service, case, _) = fixture()
            val usage = UsageAccumulator()
            service.register(case.id, usage)
            usage.record(LlmUsage(totalTokens = 1, estimatedCostUsd = 30.0))
            val pending = usage.beforeCall()
            service.continueRun(case.id, 10.0).paused shouldBe true
            pending.isDone shouldBe false
            service.continueRun(case.id, 20.0).paused shouldBe false
            pending.join()
        }

        "stop releases blocked requests and never permits another call" {
            val (service, case, _) = fixture()
            val usage = UsageAccumulator()
            service.register(case.id, usage)
            usage.record(LlmUsage(totalTokens = 1, estimatedCostUsd = 10.0))
            val pending = usage.beforeCall()
            service.stop(case.id)
            shouldThrow<CompletionException> { pending.join() }.cause!!::class shouldBe CostRunStopped::class
            shouldThrow<CompletionException> { usage.beforeCall().join() }
        }

        "priced costs still trigger the guard when another call has unknown pricing" {
            val (service, case, _) = fixture()
            val usage = UsageAccumulator()
            service.register(case.id, usage)
            usage.record(LlmUsage(totalTokens = 1, estimatedCostUsd = null))
            usage.record(LlmUsage(totalTokens = 1, estimatedCostUsd = 12.0))
            usage.beforeCall().isDone shouldBe false
            service.state(case.id).cost shouldBe 12.0
            service.state(case.id).unknownCostCount shouldBe 1
        }

        "delegations contribute to the parent threshold" {
            val (service, parent, saved) = fixture()
            val child = Case(namespaceId = parent.namespaceId, parentCaseId = parent.id, runCostThreshold = 100.0)
            saved[child.id] = child
            val parentUsage = UsageAccumulator()
            val childUsage = UsageAccumulator()
            service.register(parent.id, parentUsage)
            service.register(child.id, childUsage)
            parentUsage.record(LlmUsage(totalTokens = 1, estimatedCostUsd = 6.0))
            childUsage.record(LlmUsage(totalTokens = 1, estimatedCostUsd = 6.0))
            val childPending = childUsage.beforeCall()
            childPending.isDone shouldBe false
            service.state(parent.id).cost shouldBe 12.0
            service.continueRun(parent.id, 10.0)
            childPending.join()
        }

        "an unset threshold does not invent a monetary limit" {
            val (service, case, _) = fixture(null)
            val usage = UsageAccumulator()
            service.register(case.id, usage)
            usage.record(LlmUsage(totalTokens = 1, estimatedCostUsd = 1000.0))
            usage.beforeCall().isDone shouldBe true
        }

        "zero threshold requires an explicit positive edit" {
            val (service, case, saved) = fixture(0.0)
            val usage = UsageAccumulator()
            service.register(case.id, usage)
            val pending = usage.beforeCall()
            pending.isDone shouldBe false
            shouldThrow<ResponseStatusException> { service.continueRun(case.id, 0.0) }
            saved[case.id] = case.copy(runCostThreshold = 5.0)
            service.continueRun(case.id, 0.0)
            pending.join()
        }
        "stopping a child unblocks its ancestor gate without releasing its sibling" {
            val (service, parent, saved) = fixture()
            val child = Case(namespaceId = parent.namespaceId, parentCaseId = parent.id, runCostThreshold = 100.0)
            val sibling =
                child.copy(
                    metadata =
                        io.whozoss.agentos.sdk.entity
                            .EntityMetadata(),
                )
            saved[child.id] = child
            saved[sibling.id] = sibling
            val childUsage = UsageAccumulator()
            val siblingUsage = UsageAccumulator()
            service.register(child.id, childUsage)
            service.register(sibling.id, siblingUsage)
            childUsage.record(LlmUsage(totalTokens = 1, estimatedCostUsd = 12.0))
            val childPending = childUsage.beforeCall()
            val siblingPending = siblingUsage.beforeCall()
            service.stop(child.id)
            childPending.isCompletedExceptionally shouldBe true
            siblingPending.isDone shouldBe false
            service.state(parent.id).paused shouldBe true
            service.continueRun(parent.id, 10.0)
            siblingPending.join()
        }

        "parent stop releases simultaneous parent and child confirmations visible in the parent" {
            val (service, parent, saved) = fixture()
            val child = Case(namespaceId = parent.namespaceId, parentCaseId = parent.id, runCostThreshold = 5.0)
            saved[child.id] = child
            val usage = UsageAccumulator()
            service.register(child.id, usage)
            usage.record(LlmUsage(totalTokens = 1, estimatedCostUsd = 12.0))
            val pending = usage.beforeCall()
            service
                .state(parent.id)
                .pausedCases
                .map { it.caseId }
                .toSet() shouldBe setOf(parent.id, child.id)
            service.stop(parent.id)
            pending.isCompletedExceptionally shouldBe true
            service.state(parent.id).pausedCases shouldBe emptyList()
        }

        "a nested child's confirmation pauses its ancestors' delegation deadlines" {
            val (service, root, saved) = fixture(100.0)
            val parent = Case(namespaceId = root.namespaceId, parentCaseId = root.id, runCostThreshold = 100.0)
            val child = Case(namespaceId = root.namespaceId, parentCaseId = parent.id, runCostThreshold = 1.0)
            saved[parent.id] = parent
            saved[child.id] = child
            val usage = UsageAccumulator()
            service.register(child.id, usage)
            usage.record(LlmUsage(totalTokens = 1, estimatedCostUsd = 2.0))
            usage.beforeCall()
            service.state(root.id).paused shouldBe false
            service.isPaused(parent.id) shouldBe true
            service.isPaused(root.id) shouldBe true
            service.isPaused(child.id) shouldBe true
        }

        "graceful stop is observable without a pending cost confirmation" {
            val (service, case, _) = fixture()
            val usage = UsageAccumulator()
            val registration = service.register(case.id, usage)
            registration.isStopped() shouldBe false
            usage.beforeCall().join()
            service.stop(case.id)
            // A response already in flight can finish and retain its usage.
            usage.record(LlmUsage(totalTokens = 1, estimatedCostUsd = 2.0))
            registration.isStopped() shouldBe true
            usage.total.estimatedCostUsd shouldBe 2.0
            shouldThrow<CompletionException> { usage.beforeCall().join() }
            registration.finish {}
        }
    })
