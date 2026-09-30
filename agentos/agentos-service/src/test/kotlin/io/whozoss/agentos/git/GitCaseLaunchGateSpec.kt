package io.whozoss.agentos.git

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.InMemoryCaseRepository
import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class GitCaseLaunchGateSpec : StringSpec({
    val root = UUID.randomUUID()
    val child = UUID.randomUUID()
    val binding = CaseResourceBinding(rootCaseId = root, namespaceId = UUID.randomUUID(), integrationConfigId = UUID.randomUUID())
    val roots = mockk<GitExchangeRootResolver>()
    val cases = InMemoryCaseRepository()
    val gate = GitCaseLaunchGate(roots, cases)

    beforeTest {
        cases.save(Case(metadata = EntityMetadata(id = child), namespaceId = binding.namespaceId))
    }

    "an ordinary case has no resource restriction even after a terminal status" {
        every { roots.resolveGit(child) } returns GitExchangeRoot(Path.of("/tmp/case"), null, child)
        for (status in listOf(CaseStatus.PENDING, CaseStatus.KILLED, CaseStatus.ERROR)) {
            cases.save(cases.findById(child)!!.copy(status = status))
            gate.requireAccepting(child)
            gate.keepOpenOnShutdown(child) shouldBe false
            gate.canLaunch(child) shouldBe true
            var admitted = false
            gate.withAdmission(child, onAvailable = { error("No resource should defer this case") }) { admitted = true }
            admitted shouldBe true
        }
    }
    "a workspace lookup failure is reported rather than treated as a workspace still preparing" {
        every { roots.resolveGit(child) } throws IllegalStateException("Neo4j session expired")

        // A pending answer would park the turn forever: nothing resumes a case without a workspace.
        shouldThrow<IllegalStateException> { gate.canLaunch(child) }
    }
    "a child waits for its shared worktree while still accepting input" {
        every { roots.resolveGit(child) } returns GitExchangeRoot(Path.of("/tmp/case"), binding.copy(status = CaseResourceStatus.PREPARING), root)
        gate.requireAccepting(child)
        gate.keepOpenOnShutdown(child) shouldBe true
        gate.canLaunch(child) shouldBe false
        every { roots.resolveGit(child) } returns GitExchangeRoot(Path.of("/tmp/case"), binding.copy(status = CaseResourceStatus.READY), root)
        gate.canLaunch(child) shouldBe true
    }
    "an equipped terminal case refuses fresh input and a previously admitted launch" {
        every { roots.resolveGit(child) } returns GitExchangeRoot(Path.of("/tmp/case"), binding.copy(status = CaseResourceStatus.READY), root)
        for (status in listOf(CaseStatus.KILLED, CaseStatus.ERROR)) {
            cases.save(cases.findById(child)!!.copy(status = status))
            shouldThrow<ConflictException> { gate.requireAccepting(child) }
            gate.canLaunch(child) shouldBe false
        }
    }
    "a deleted workspace refuses input and execution for all family members" {
        for (status in listOf(CaseResourceStatus.DELETING, CaseResourceStatus.REMOVED)) {
            every { roots.resolveGit(child) } returns GitExchangeRoot(Path.of("/tmp/case"), binding.copy(status = status), root)
            shouldThrow<ConflictException> { gate.requireAccepting(child) }
            gate.canLaunch(child) shouldBe false
        }
    }
    "an admission deferred by cleanup rechecks readiness when the resource lock becomes available" {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val attempts = AtomicInteger()
        val admitted = AtomicInteger()
        every { roots.resolveGit(child) } returns GitExchangeRoot(Path.of("/tmp/case"), binding.copy(status = CaseResourceStatus.READY), root)
        val holder = Thread {
            WorkspaceLifecycleLocks.withRoot(root) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                every { roots.resolveGit(child) } returns GitExchangeRoot(Path.of("/tmp/case"), binding.copy(status = CaseResourceStatus.REMOVED), root)
            }
        }
        try {
            holder.start()
            entered.await(5, TimeUnit.SECONDS) shouldBe true
            gate.withAdmission(child, onAvailable = {
                gate.withAdmission(child, onAvailable = { error("Lock already released") }) {
                    attempts.incrementAndGet()
                    if (gate.canLaunch(child)) admitted.incrementAndGet()
                    completed.countDown()
                }
            }) { admitted.incrementAndGet() }
            admitted.get() shouldBe 0
            attempts.get() shouldBe 0
            release.countDown()
            completed.await(5, TimeUnit.SECONDS) shouldBe true
            attempts.get() shouldBe 1
            admitted.get() shouldBe 0
        } finally {
            release.countDown()
            holder.join(5_000)
        }
    }
})
