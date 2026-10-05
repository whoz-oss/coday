package io.whozoss.agentos.git

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.CaseRepository
import io.whozoss.agentos.caseFlow.CaseService
import io.whozoss.agentos.git.core.GitCommandException
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The provisioning sweep.
 *
 * Its job is as much about what it leaves alone as about what it provisions: a failed workspace
 * must not be retried on a timer, and one broken workspace must not block the batch behind it.
 */
class CaseWorkspaceWorkerSpec :
    StringSpec({

        val namespaceId = UUID.randomUUID()

        fun settings(): GitRepositorySettings =
            GitRepositorySettings(
                configId = UUID.randomUUID(),
                namespaceId = namespaceId,
                repositoryUrl = "https://forge.example/org/project.git",
                mainBranch = "main",
                serviceAuthSettingId = UUID.randomUUID(),
                autoWorktreeForRootCases = true,
                setupCommand = null,
            )

        fun rootCase(): Case = Case(metadata = EntityMetadata(), namespaceId = namespaceId, title = "Root")

        class Harness(
            val bindings: InMemoryCaseResourceBindingService,
            val association: GitRepositoryAssociationService,
            val provisioner: CaseWorktreeProvisioner,
            val caseService: CaseService,
            val worker: CaseWorkspaceWorker,
            val meters: SimpleMeterRegistry,
        )

        fun harness(
            cases: List<Case>,
            descendants: List<Case> = emptyList(),
            lifecycle: GitWorkspaceLifecycleService? = null,
            executor: GitWorkRunner = GitWorkRunner { it.run() },
            control: GitWorkspacesControl = GitWorkspacesControl(),
        ): Harness {
            val bindings = InMemoryCaseResourceBindingService()
            val byId = cases.associateBy { it.id }
            val caseRepository =
                mockk<CaseRepository> {
                    every { findByIds(any(), any()) } answers { firstArg<Collection<UUID>>().mapNotNull { byId[it] } }
                    every { findActiveDescendants(any()) } returns descendants
                }
            val association = mockk<GitRepositoryAssociationService>()
            val provisioner = mockk<CaseWorktreeProvisioner>()
            val caseService = mockk<CaseService>(relaxed = true)
            // No namespace checkout is queued in these tests: the checkout sweep is exercised in
            // RepositoryCheckoutProvisionerSpec, against a real repository.
            val checkouts = mockk<RepositoryCheckoutService>(relaxed = true) { every { findByStatusIn(any(), any()) } returns emptyList() }
            val checkoutProvisioner = mockk<RepositoryCheckoutProvisioner>(relaxed = true)
            val meters = SimpleMeterRegistry()
            return Harness(
                bindings,
                association,
                provisioner,
                caseService,
                CaseWorkspaceWorker(
                    bindings,
                    association,
                    provisioner,
                    caseRepository,
                    caseService,
                    checkouts,
                    checkoutProvisioner,
                    lifecycle,
                    executor,
                    meters,
                    control,
                ),
                meters,
            )
        }

        fun request(
            h: Harness,
            case: Case,
            status: CaseResourceStatus = CaseResourceStatus.REQUESTED,
            settings: GitRepositorySettings? = settings(),
        ): CaseResourceBinding =
            h.bindings.create(
                CaseResourceBinding(
                    rootCaseId = case.id,
                    namespaceId = namespaceId,
                    integrationConfigId = UUID.randomUUID(),
                    status = status,
                    settings = settings,
                ),
            )

        "a preparation interrupted by a crash is queued again at startup" {
            // ensureReady marks PREPARING before tens of minutes of work. A crash in that window
            // used to strand the binding forever: the sweep only looks at REQUESTED, and nothing
            // else re-drives a binding, so the launch gate deferred every message from then on.
            val case = rootCase()
            val h = harness(listOf(case))
            val binding = request(h, case, status = CaseResourceStatus.PREPARING)

            h.worker.reclaimInterruptedPreparations()

            h.bindings.findByRootCaseId(case.id)?.status shouldBe CaseResourceStatus.REQUESTED
            binding.rootCaseId shouldBe case.id
        }

        "startup reclamation leaves a failed workspace failed" {
            // Recovery from a real failure stays an explicit action; only PREPARING is reclaimed.
            val case = rootCase()
            val h = harness(listOf(case))
            request(h, case, status = CaseResourceStatus.FAILED)

            h.worker.reclaimInterruptedPreparations()

            h.bindings.findByRootCaseId(case.id)?.status shouldBe CaseResourceStatus.FAILED
        }

        "a requested workspace is provisioned" {
            val case = rootCase()
            val h = harness(listOf(case))
            val binding = request(h, case)
            every { h.provisioner.ensureReady(any(), any(), any()) } answers { firstArg() }

            h.worker.provisionPending()

            verify(exactly = 1) { h.provisioner.ensureReady(match { it.id == binding.id }, any(), match { it.id == case.id }) }
        }

        "deletion after the requested batch was read prevents worktree creation" {
            val case = rootCase()
            val lifecycle = mockk<GitWorkspaceLifecycleService>(relaxed = true)
            val h = harness(listOf(case), lifecycle = lifecycle)
            val binding = request(h, case)
            every { lifecycle.cleanupDeleted(case.id) } answers {
                h.bindings.update(binding.copy(status = CaseResourceStatus.REMOVED))
            }

            h.worker.provisionPending()

            verify(exactly = 1) { lifecycle.cleanupDeletedCases(any()) }
            verify(exactly = 0) { h.provisioner.ensureReady(any(), any(), any()) }
            // Held turns go back to the gate, which refuses them on a removed workspace.
            verify(exactly = 1) { h.caseService.resumeIfPending(case.id) }
            h.bindings.findByRootCaseId(case.id)!!.status shouldBe CaseResourceStatus.REMOVED
        }

        "a failed preparation hands the family's held turns back to the gate" {
            // Leaving them PENDING kept the case waiting forever without a word: the gate now
            // refuses them with a warning and returns the cases to IDLE.
            val case = rootCase()
            val child = Case(metadata = EntityMetadata(), namespaceId = namespaceId, title = "Delegated")
            val h = harness(listOf(case), descendants = listOf(child))
            request(h, case)
            every { h.provisioner.ensureReady(any(), any(), any()) } throws GitCommandException("clone refused")

            h.worker.provisionPending()

            verify(exactly = 1) { h.caseService.resumeIfPending(case.id) }
            verify(exactly = 1) { h.caseService.resumeIfPending(child.id) }
        }

        "a workspace failed for missing settings or a vanished case hands its held turns back" {
            val orphan = rootCase()
            val unassociated = rootCase()
            val h = harness(listOf(unassociated))
            request(h, orphan)
            request(h, unassociated, settings = null)

            h.worker.provisionPending()

            verify(exactly = 1) { h.caseService.resumeIfPending(orphan.id) }
            verify(exactly = 1) { h.caseService.resumeIfPending(unassociated.id) }
        }

        "a binding that left REQUESTED after the batch was read releases nothing" {
            val first = rootCase()
            val second = rootCase()
            val h = harness(listOf(first, second))
            request(h, first)
            val moved = request(h, second)
            every { h.provisioner.ensureReady(match { it.rootCaseId == first.id }, any(), any()) } answers {
                h.bindings.update(moved.copy(status = CaseResourceStatus.PREPARING))
                firstArg()
            }

            h.worker.provisionPending()

            verify(exactly = 0) { h.provisioner.ensureReady(match { it.rootCaseId == second.id }, any(), any()) }
            verify(exactly = 0) { h.caseService.resumeIfPending(second.id) }
        }

        "a workspace reaching ready releases the turn that was held back" {
            val case = rootCase()
            val h = harness(listOf(case))
            val binding = request(h, case)
            every { h.provisioner.ensureReady(any(), any(), any()) } answers
                {
                    firstArg<CaseResourceBinding>().copy(status = CaseResourceStatus.READY)
                }

            h.worker.provisionPending()

            // The user's message was persisted while the case waited; nothing else would start it.
            verify(exactly = 1) { h.caseService.resumeIfPending(case.id) }
            binding.rootCaseId shouldBe case.id
        }

        "a workspace reaching ready releases the whole family, not only its root" {
            // The family shares one workspace, so the gate deferred the family. A sub-case created
            // by delegation during preparation kept its message; resuming only the root left the
            // delegating parent waiting on a child that would never run.
            val case = rootCase()
            val child = Case(metadata = EntityMetadata(), namespaceId = namespaceId, title = "Delegated")
            val h = harness(listOf(case), descendants = listOf(child))
            request(h, case)
            every { h.provisioner.ensureReady(any(), any(), any()) } answers
                {
                    firstArg<CaseResourceBinding>().copy(status = CaseResourceStatus.READY)
                }

            h.worker.provisionPending()

            verify(exactly = 1) { h.caseService.resumeIfPending(case.id) }
            verify(exactly = 1) { h.caseService.resumeIfPending(child.id) }
        }

        "a workspace that did not reach ready releases nothing" {
            val case = rootCase()
            val h = harness(listOf(case))
            request(h, case)
            every { h.provisioner.ensureReady(any(), any(), any()) } answers { firstArg() }

            h.worker.provisionPending()

            verify(exactly = 0) { h.caseService.resumeIfPending(any()) }
        }

        "a failed workspace is left alone rather than retried on a timer" {
            val case = rootCase()
            val h = harness(listOf(case))
            request(h, case, status = CaseResourceStatus.FAILED)

            h.worker.provisionPending()

            // Retrying on a schedule would hammer a misconfigured repository and bury the cause.
            verify(exactly = 0) { h.provisioner.ensureReady(any(), any(), any()) }
        }

        "a ready workspace is not picked up again" {
            val case = rootCase()
            val h = harness(listOf(case))
            request(h, case, status = CaseResourceStatus.READY)

            h.worker.provisionPending()

            verify(exactly = 0) { h.provisioner.ensureReady(any(), any(), any()) }
        }

        "a binding whose case vanished is failed instead of retried forever" {
            val case = rootCase()
            val h = harness(cases = emptyList())
            val binding = request(h, case)

            h.worker.provisionPending()

            val updated = h.bindings.findByRootCaseId(case.id)!!
            updated.status shouldBe CaseResourceStatus.FAILED
            updated.failureReason!! shouldContain "no longer exists"
            verify(exactly = 0) { h.provisioner.ensureReady(any(), any(), any()) }
            binding.id shouldBe updated.id
        }

        "a binding without readable settings fails for good, never borrows the current association and blocks no one" {
            val unreadable = rootCase()
            val next = rootCase()
            val h = harness(listOf(unreadable, next))
            request(h, unreadable, settings = null)
            request(h, next)
            every { h.provisioner.ensureReady(match { it.rootCaseId == next.id }, any(), any()) } answers {
                h.bindings.markStatus(firstArg<CaseResourceBinding>().id, CaseResourceStatus.READY)
            }

            h.worker.provisionPending()

            val failed = h.bindings.findByRootCaseId(unreadable.id)!!
            failed.status shouldBe CaseResourceStatus.FAILED
            failed.failureReason shouldBe "No readable settings were recorded for this workspace. Retrying cannot help."
            h.bindings.findByRootCaseId(next.id)!!.status shouldBe CaseResourceStatus.READY
            verify(exactly = 0) { h.provisioner.ensureReady(match { it.rootCaseId == unreadable.id }, any(), any()) }
            verify(exactly = 0) { h.association.findSettings(any()) }
        }

        "each sweep is timed and each failed workspace is counted" {
            val broken = rootCase()
            val h = harness(listOf(broken))
            request(h, broken)
            every { h.provisioner.ensureReady(any(), any(), any()) } throws GitCommandException("clone refused")

            h.worker.provisionPending()

            h.meters.get("agentos.git.worker.sweep").timer().count() shouldBe 1L
            h.meters.get("agentos.git.worker.errors").tag("operation", "worktree").counter().count() shouldBe 1.0
        }

        "one broken workspace does not block the rest of the batch" {
            val broken = rootCase()
            val healthy = rootCase()
            val h = harness(listOf(broken, healthy))
            request(h, broken)
            request(h, healthy)
            every { h.provisioner.ensureReady(match { it.rootCaseId == broken.id }, any(), any()) } throws
                GitCommandException("clone refused")
            every { h.provisioner.ensureReady(match { it.rootCaseId == healthy.id }, any(), any()) } answers { firstArg() }

            h.worker.provisionPending()

            verify(exactly = 1) { h.provisioner.ensureReady(match { it.rootCaseId == healthy.id }, any(), any()) }
        }

        "a long preparation leaves the scheduler free and does not overlap its next sweep" {
            val executor = GitWorkspaceExecutor()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val finished = CountDownLatch(1)
            val case = rootCase()
            val h = harness(listOf(case), executor = executor)
            request(h, case)
            every { h.provisioner.ensureReady(any(), any(), any()) } answers {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                finished.countDown()
                firstArg()
            }
            try {
                h.worker.provisionPending()
                entered.await(5, TimeUnit.SECONDS) shouldBe true
                // The scheduler call returned while preparation remains blocked on another thread.
                h.worker.provisionPending()
                verify(exactly = 1) { h.provisioner.ensureReady(any(), any(), any()) }
                WorkspaceLifecycleLocks.tryWithRoot(case.id, onBusy = { "busy" }) { "acquired" } shouldBe "busy"
                release.countDown()
                finished.await(5, TimeUnit.SECONDS) shouldBe true
            } finally {
                release.countDown()
                executor.shutdown()
            }
        }

        "a sweep that throws does not prevent the next one" {
            val case = rootCase()
            val h = harness(listOf(case))
            request(h, case)
            every { h.provisioner.ensureReady(any(), any(), any()) } throws IllegalStateException("boom")

            h.worker.provisionPending()
            // The in-process guard must have been released, so a second pass still runs.
            h.worker.provisionPending()

            verify(exactly = 2) { h.provisioner.ensureReady(any(), any(), any()) }
        }
        "a paused worker neither cleans up nor provisions, and records no sweep" {
            val case = rootCase()
            val lifecycle = mockk<GitWorkspaceLifecycleService>(relaxed = true)
            val control = GitWorkspacesControl().also { it.pauseProvisioning() }
            val h = harness(listOf(case), lifecycle = lifecycle, control = control)
            request(h, case)

            h.worker.provisionPending()

            verify(exactly = 0) { lifecycle.cleanupDeletedCases(any()) }
            verify(exactly = 0) { h.provisioner.ensureReady(any(), any(), any()) }
            h.meters.get(CaseWorkspaceWorker.SWEEP_TIMER).timer().count() shouldBe 0L
        }

        "a pause during a batch lets the current workspace finish and stops before the next one" {
            val first = rootCase()
            val second = rootCase()
            val control = GitWorkspacesControl()
            val h = harness(listOf(first, second), control = control)
            request(h, first)
            request(h, second)
            every { h.provisioner.ensureReady(any(), any(), any()) } answers {
                control.pauseProvisioning()
                firstArg()
            }

            h.worker.provisionPending()

            verify(exactly = 1) { h.provisioner.ensureReady(any(), any(), any()) }
            h.worker.activity().currentItem shouldBe null
        }

        "the cleanup phase honours a pause between two deleted families" {
            val control = GitWorkspacesControl()
            val lifecycle = mockk<GitWorkspaceLifecycleService>(relaxed = true)
            every { lifecycle.cleanupDeletedCases(any()) } answers {
                control.pauseProvisioning()
                firstArg<() -> Boolean>()() shouldBe true
            }
            val case = rootCase()
            val h = harness(listOf(case), lifecycle = lifecycle, control = control)
            request(h, case)

            h.worker.provisionPending()

            verify(exactly = 0) { h.provisioner.ensureReady(any(), any(), any()) }
        }

        "every error counter exists before the first failure" {
            val h = harness(emptyList())
            listOf("sweep", "checkout", "worktree").forEach {
                h.meters.get(CaseWorkspaceWorker.ERROR_COUNTER).tag("operation", it).counter().count() shouldBe 0.0
            }
        }

    })
