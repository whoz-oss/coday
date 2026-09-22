package io.whozoss.agentos.git

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
import java.util.concurrent.Executor
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
            val provisioner: CaseWorktreeProvisioner,
            val caseService: CaseService,
            val worker: CaseWorkspaceWorker,
        )

        fun harness(
            cases: List<Case>,
            resolveSettings: () -> GitRepositorySettings? = { settings() },
            descendants: List<Case> = emptyList(),
            lifecycle: GitWorkspaceLifecycleService? = null,
            executor: Executor = Executor { it.run() },
        ): Harness {
            val bindings = InMemoryCaseResourceBindingService()
            val byId = cases.associateBy { it.id }
            val caseRepository =
                mockk<CaseRepository> {
                    every { findByIds(any(), any()) } answers { firstArg<Collection<UUID>>().mapNotNull { byId[it] } }
                    every { findActiveDescendants(any()) } returns descendants
                }
            val association = mockk<GitRepositoryAssociationService> { every { findSettings(any()) } answers { resolveSettings() } }
            val provisioner = mockk<CaseWorktreeProvisioner>()
            val caseService = mockk<CaseService>(relaxed = true)
            // No namespace checkout is queued in these tests: the checkout sweep is exercised in
            // RepositoryCheckoutProvisionerSpec, against a real repository.
            val checkouts = mockk<RepositoryCheckoutService>(relaxed = true) { every { findByStatusIn(any(), any()) } returns emptyList() }
            val checkoutProvisioner = mockk<RepositoryCheckoutProvisioner>(relaxed = true)
            return Harness(
                bindings,
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
                    com.fasterxml.jackson.module.kotlin.jacksonObjectMapper(),
                    lifecycle,
                    executor,
                ),
            )
        }

        fun request(
            h: Harness,
            case: Case,
            status: CaseResourceStatus = CaseResourceStatus.REQUESTED,
        ): CaseResourceBinding =
            h.bindings.create(
                CaseResourceBinding(
                    rootCaseId = case.id,
                    namespaceId = namespaceId,
                    integrationConfigId = UUID.randomUUID(),
                    status = status,
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

            verify(exactly = 1) { lifecycle.cleanupDeletedCases() }
            verify(exactly = 0) { h.provisioner.ensureReady(any(), any(), any()) }
            verify(exactly = 0) { h.caseService.resumeIfPending(any()) }
            h.bindings.findByRootCaseId(case.id)!!.status shouldBe CaseResourceStatus.REMOVED
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

        "a binding whose namespace lost its association is failed" {
            val case = rootCase()
            val h = harness(listOf(case), resolveSettings = { null })
            request(h, case)

            h.worker.provisionPending()

            h.bindings.findByRootCaseId(case.id)!!.status shouldBe CaseResourceStatus.FAILED
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
    })
