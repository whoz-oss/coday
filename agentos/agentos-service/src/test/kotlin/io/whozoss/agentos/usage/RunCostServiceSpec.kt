package io.whozoss.agentos.usage

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.caseEvent.CaseEventService
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.CaseRepository
import io.whozoss.agentos.chat.UsageAccumulator
import io.whozoss.agentos.config.UsageConfigProperties
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.usage.LlmUsage
import org.springframework.http.HttpStatus
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
            return Triple(RunCostService(repo, events, namespaces, records, UsageConfigProperties(enabled = true)), case, saved)
        }

        "usage is disabled by default before any data access or registration" {
            val cases = mockk<CaseRepository>()
            val events = mockk<CaseEventService>()
            val namespaces = mockk<NamespaceService>()
            val records = mockk<UsageRecordService>()
            val service = RunCostService(cases, events, namespaces, records)
            val caseId = UUID.randomUUID()
            val usage = UsageAccumulator()
            val beforeCall = usage.beforeCall

            listOf<() -> Any>(
                { service.register(caseId, usage) },
                { service.state(caseId) },
                { service.continueRun(caseId, 10.0) },
            ).forEach { action ->
                val error = shouldThrow<ResponseStatusException> { action() }
                error.statusCode shouldBe HttpStatus.SERVICE_UNAVAILABLE
                error.reason shouldBe "Usage tracking is disabled"
            }
            service.stop(caseId)
            service.isPaused(caseId) shouldBe false
            usage.beforeCall shouldBe beforeCall
            verify { listOf(cases, events, namespaces, records) wasNot Called }
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

        "the child reports its ancestor's pause and resumes only when that ancestor is confirmed" {
            val (service, parent, saved) = fixture()
            val child = Case(namespaceId = parent.namespaceId, parentCaseId = parent.id, runCostThreshold = 100.0)
            saved[child.id] = child
            val usage = UsageAccumulator()
            val registration = service.register(child.id, usage)
            try {
                usage.record(LlmUsage(totalTokens = 1, estimatedCostUsd = 12.0))
                val pending = usage.beforeCall()
                pending.isDone shouldBe false
                val state = service.state(child.id)
                state.paused shouldBe true
                state.runCostThreshold shouldBe 100.0
                state.pausedCases.single().let {
                    it.caseId shouldBe parent.id
                    it.cost shouldBe 12.0
                    it.threshold shouldBe 10.0
                    it.ancestor shouldBe true
                }
                service.continueRun(parent.id, 10.0)
                pending.isDone shouldBe true
                service.state(child.id).paused shouldBe false
                service.state(child.id).pausedCases shouldBe emptyList()
                saved.getValue(child.id).runCostThreshold shouldBe 100.0
            } finally {
                service.stop(parent.id)
                registration.finish {}
            }
        }

        "a nested child reports every blocking ancestor without unrelated sibling limits" {
            val (service, root, saved) = fixture(10.0)
            val parent = Case(namespaceId = root.namespaceId, parentCaseId = root.id, runCostThreshold = 5.0)
            val child = Case(namespaceId = root.namespaceId, parentCaseId = parent.id, runCostThreshold = 100.0)
            val sibling = Case(namespaceId = root.namespaceId, parentCaseId = root.id, runCostThreshold = 1.0)
            listOf(parent, child, sibling).forEach { saved[it.id] = it }
            val childUsage = UsageAccumulator()
            val siblingUsage = UsageAccumulator()
            val childRegistration = service.register(child.id, childUsage)
            val siblingRegistration = service.register(sibling.id, siblingUsage)
            try {
                childUsage.record(LlmUsage(totalTokens = 1, estimatedCostUsd = 12.0))
                siblingUsage.record(LlmUsage(totalTokens = 1, estimatedCostUsd = 2.0))
                val pending = childUsage.beforeCall()
                siblingUsage.beforeCall()
                service.state(child.id).pausedCases.map { it.caseId }.toSet() shouldBe setOf(parent.id, root.id)
                service.state(child.id).pausedCases.all { it.ancestor } shouldBe true
                service.continueRun(root.id, 10.0)
                pending.isDone shouldBe false
                service.state(child.id).pausedCases.map { it.caseId } shouldBe listOf(parent.id)
                service.continueRun(parent.id, 5.0)
                pending.isDone shouldBe false
                service.continueRun(parent.id, 10.0)
                pending.isDone shouldBe true
                service.state(child.id).paused shouldBe false
                service.state(sibling.id).paused shouldBe true
            } finally {
                service.stop(root.id)
                childRegistration.finish {}
                siblingRegistration.finish {}
            }
        }

        "a stopped or finished child does not claim to be waiting on its still paused ancestor" {
            val (service, parent, saved) = fixture()
            val child = Case(namespaceId = parent.namespaceId, parentCaseId = parent.id, runCostThreshold = 100.0)
            saved[child.id] = child
            val parentRegistration = service.register(parent.id, UsageAccumulator())
            val childUsage = UsageAccumulator()
            val childRegistration = service.register(child.id, childUsage)
            try {
                childUsage.record(LlmUsage(totalTokens = 1, estimatedCostUsd = 12.0))
                childUsage.beforeCall()
                service.state(child.id).paused shouldBe true
                service.stop(child.id)
                service.state(child.id).paused shouldBe false
                service.state(child.id).pausedCases shouldBe emptyList()
                childRegistration.finish {}
                service.state(child.id).paused shouldBe false
                service.state(child.id).pausedCases shouldBe emptyList()
                service.state(parent.id).paused shouldBe true
            } finally {
                service.stop(parent.id)
                parentRegistration.finish {}
            }
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

        fun RunCostService.nonPausing(
            caseId: UUID,
            usage: UsageAccumulator,
        ) = registerNonPausing(caseId, usage)

        "a non-pausing registration counts toward the cost but never waits for a confirmation" {
            val (service, case, _) = fixture()
            val side = UsageAccumulator()
            val registered = service.nonPausing(case.id, side).shouldBeInstanceOf<RunCostService.NonPausingRegistration.Registered>()

            side.record(LlmUsage(totalTokens = 1, estimatedCostUsd = 12.0))

            side.beforeCall().isDone shouldBe true
            service.state(case.id).cost shouldBe 12.0
            service.isPaused(case.id) shouldBe false
            // A running turn, on the other hand, pauses at its next gate.
            val turn = UsageAccumulator()
            service.register(case.id, turn)
            turn.beforeCall().isDone shouldBe false
            service.isPaused(case.id) shouldBe true
            registered.registration.finish {}
        }

        "a non-pausing registration is refused, atomically and without pausing, over the threshold" {
            val (service, case, _) = fixture()
            val turn = UsageAccumulator()
            val registration = service.register(case.id, turn)
            turn.record(LlmUsage(totalTokens = 1, estimatedCostUsd = 10.0))

            val refused = service.nonPausing(case.id, UsageAccumulator())

            refused shouldBe RunCostService.NonPausingRegistration.Refused(case.id, RunCostService.NonPausingRegistration.Reason.OVER_THRESHOLD)
            service.isPaused(case.id) shouldBe false
            registration.finish {}
        }

        "a non-pausing registration is refused while the case waits for a confirmation, or is stopped" {
            val (service, case, _) = fixture()
            val turn = UsageAccumulator()
            val registration = service.register(case.id, turn)
            turn.record(LlmUsage(totalTokens = 1, estimatedCostUsd = 12.0))
            turn.beforeCall()

            (service.nonPausing(case.id, UsageAccumulator()) as RunCostService.NonPausingRegistration.Refused).reason shouldBe
                RunCostService.NonPausingRegistration.Reason.PAUSED

            service.stop(case.id)

            (service.nonPausing(case.id, UsageAccumulator()) as RunCostService.NonPausingRegistration.Refused).reason shouldBe
                RunCostService.NonPausingRegistration.Reason.STOPPED
            registration.finish {}
        }

        "a non-pausing registration is refused when an ancestor is over its threshold" {
            val (service, parent, saved) = fixture(5.0)
            val child = Case(namespaceId = parent.namespaceId, parentCaseId = parent.id, runCostThreshold = 100.0)
            saved[child.id] = child
            val turn = UsageAccumulator()
            val registration = service.register(parent.id, turn)
            turn.record(LlmUsage(totalTokens = 1, estimatedCostUsd = 6.0))

            service.nonPausing(child.id, UsageAccumulator()) shouldBe
                RunCostService.NonPausingRegistration.Refused(parent.id, RunCostService.NonPausingRegistration.Reason.OVER_THRESHOLD)
            registration.finish {}
        }

        "a non-pausing registration in flight does not keep a stopped session: the next turn starts clean" {
            val (service, case, _) = fixture()
            val turn = UsageAccumulator()
            val turnRegistration = service.register(case.id, turn)
            val side = UsageAccumulator()
            val sideRegistration =
                (service.nonPausing(case.id, side) as RunCostService.NonPausingRegistration.Registered).registration

            service.stop(case.id)
            turnRegistration.finish {}

            val next = UsageAccumulator()
            val nextRegistration = service.register(case.id, next)
            nextRegistration.isStopped() shouldBe false
            next.beforeCall().isDone shouldBe true
            // The side question still finishes cleanly, and does not touch the new session.
            shouldThrow<CompletionException> { side.beforeCall().join() }
            sideRegistration.finish {}
            nextRegistration.isStopped() shouldBe false
            nextRegistration.finish {}
        }

        "the usage of a side question still in flight when its stopped session ended reaches the session that replaced it" {
            val (service, case, _) = fixture(100.0)
            val side = UsageAccumulator()
            val sideRegistration =
                (service.nonPausing(case.id, side) as RunCostService.NonPausingRegistration.Registered).registration
            service.stop(case.id)
            val next = UsageAccumulator()
            val nextRegistration = service.register(case.id, next)

            side.record(LlmUsage(totalTokens = 1, estimatedCostUsd = 3.0))
            sideRegistration.finish {}

            service.state(case.id).cost shouldBe 3.0
            nextRegistration.finish {}
        }

        "after a stop, no side question is admitted until a new run starts, even once the stopped session is gone" {
            val (service, case, _) = fixture()
            val turn = UsageAccumulator()
            val registration = service.register(case.id, turn)
            service.stop(case.id)
            registration.finish {}

            val refused = service.nonPausing(case.id, UsageAccumulator())

            refused shouldBe RunCostService.NonPausingRegistration.Refused(case.id, RunCostService.NonPausingRegistration.Reason.STOPPED)
            // No fresh session was made for it.
            service.state(case.id).active shouldBe false
        }

        "a new run admits side questions again after a stop" {
            val (service, case, _) = fixture()
            service.stop(case.id)
            val next = service.register(case.id, UsageAccumulator())

            val admitted = service.nonPausing(case.id, UsageAccumulator())

            admitted.shouldBeInstanceOf<RunCostService.NonPausingRegistration.Registered>().registration.finish {}
            next.finish {}
        }

        listOf(CaseStatus.KILLED, CaseStatus.ERROR).forEach { status ->
            "a side question on a case that has ended ($status) is refused, its parent's too" {
                val (service, parent, saved) = fixture()
                saved[parent.id] = parent.copy(status = status)
                val child = Case(namespaceId = parent.namespaceId, parentCaseId = parent.id)
                saved[child.id] = child

                service.nonPausing(parent.id, UsageAccumulator()) shouldBe
                    RunCostService.NonPausingRegistration.Refused(parent.id, RunCostService.NonPausingRegistration.Reason.ENDED)
                service.nonPausing(child.id, UsageAccumulator()) shouldBe
                    RunCostService.NonPausingRegistration.Refused(parent.id, RunCostService.NonPausingRegistration.Reason.ENDED)
                service.state(parent.id).active shouldBe false
            }
        }

        "a stop with only a non-pausing registration left ends the session at once" {
            val (service, case, _) = fixture()
            val side = UsageAccumulator()
            val sideRegistration =
                (service.nonPausing(case.id, side) as RunCostService.NonPausingRegistration.Registered).registration

            service.stop(case.id)

            val next = UsageAccumulator()
            service.register(case.id, next).isStopped() shouldBe false
            sideRegistration.finish {}
        }
    })
