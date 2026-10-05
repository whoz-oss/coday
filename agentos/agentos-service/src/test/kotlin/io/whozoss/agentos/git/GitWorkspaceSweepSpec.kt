package io.whozoss.agentos.git

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.CaseRepository
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

class GitWorkspaceSweepSpec : StringSpec({
    fun rows(bindings: InMemoryCaseResourceBindingService, count: Long = 7): List<CaseResourceBinding> = (1L..count).map { id ->
        bindings.create(CaseResourceBinding(
            metadata = EntityMetadata(id = UUID(0, id), created = Instant.parse("2026-01-01T00:00:00Z")),
            rootCaseId = UUID.randomUUID(), namespaceId = UUID.randomUUID(),
            integrationConfigId = UUID.randomUUID(), status = CaseResourceStatus.READY,
        ))
    }

    "cleanup visits the next page after earlier bindings disappear and retries on the next sweep" {
        val bindings = InMemoryCaseResourceBindingService()
        val rows = rows(bindings)
        val visited = mutableListOf<UUID>()
        val cases = mockk<CaseRepository> {
            every { findByIds(any(), true) } answers {
                visited.addAll(firstArg<Collection<UUID>>())
                emptyList<Case>()
            }
        }
        val lifecycle = GitWorkspaceLifecycleService(bindings, cases, mockk(), mockk(), mockk(), mockk(), mockk())
        lifecycle.cleanupDeletedCases()
        visited shouldBe rows.take(5).map { it.rootCaseId }
        rows.take(5).forEach { bindings.delete(it.id) }
        lifecycle.cleanupDeletedCases()
        visited shouldBe rows.map { it.rootCaseId }
        lifecycle.cleanupDeletedCases() // The short final page reset the cursor; retry surviving rows.
        visited shouldBe rows.map { it.rootCaseId } + rows.takeLast(2).map { it.rootCaseId }
    }

    "a full final page starts the next cleanup sweep without losing a worker tick" {
        val bindings = InMemoryCaseResourceBindingService()
        val rows = rows(bindings, 5)
        val visited = mutableListOf<UUID>()
        val cases = mockk<CaseRepository> {
            every { findByIds(any(), true) } answers {
                visited.addAll(firstArg<Collection<UUID>>())
                emptyList<Case>()
            }
        }
        val lifecycle = GitWorkspaceLifecycleService(bindings, cases, mockk(), mockk(), mockk(), mockk(), mockk())
        repeat(2) { lifecycle.cleanupDeletedCases() }
        visited shouldBe rows.map { it.rootCaseId } + rows.map { it.rootCaseId }
    }

    /** Every workspace resolves; only the observation itself differs between the status tests. */
    fun resolver() = mockk<GitExchangeRootResolver> {
        every { resolveGit(any<UUID>()) } returns GitExchangeRoot(Path.of("/tmp/unused-workspace"), null, UUID.randomUUID())
    }

    fun observer(visited: MutableList<UUID>, onVisit: (CaseResourceBinding) -> Unit = {}) =
        mockk<GitWorkspaceStatusService> {
            every { refresh(any(), any()) } answers {
                firstArg<CaseResourceBinding>().also {
                    visited.add(it.rootCaseId)
                    onVisit(it)
                }
            }
        }

    "a full final page starts the next status sweep without losing a scheduled tick" {
        val bindings = InMemoryCaseResourceBindingService()
        val rows = rows(bindings, 5)
        val visited = mutableListOf<UUID>()
        val monitor = GitWorkspaceMonitor(bindings, resolver(), observer(visited))
        repeat(2) { monitor.poll() }
        visited shouldBe rows.map { it.rootCaseId } + rows.map { it.rootCaseId }
    }

    "status polling advances after a failure or removal and restarts after the last page" {
        val bindings = InMemoryCaseResourceBindingService()
        val rows = rows(bindings)
        val visited = mutableListOf<UUID>()
        val statuses = observer(visited) {
            if (it.id == rows.first().id) error("transient observation failure")
        }
        val monitor = GitWorkspaceMonitor(bindings, resolver(), statuses)
        monitor.poll()
        visited shouldBe rows.take(5).map { it.rootCaseId }
        rows.take(5).forEach { bindings.delete(it.id) }
        monitor.poll()
        visited shouldBe rows.map { it.rootCaseId }
        monitor.poll()
        visited shouldBe rows.map { it.rootCaseId } + rows.takeLast(2).map { it.rootCaseId }
    }

    "a paused monitor polls nothing until it is resumed" {
        val bindings = InMemoryCaseResourceBindingService()
        val rows = rows(bindings, 2)
        val visited = mutableListOf<UUID>()
        val control = GitWorkspacesControl().also { it.pauseMonitor() }
        val monitor = GitWorkspaceMonitor(bindings, resolver(), observer(visited), control = control)
        monitor.poll()
        visited shouldBe emptyList()
        control.resumeMonitor()
        monitor.poll()
        visited shouldBe rows.map { it.rootCaseId }
    }

    "pausing mid-page skips the rest of that page until the cursor wraps around" {
        val bindings = InMemoryCaseResourceBindingService()
        val rows = rows(bindings)
        val visited = mutableListOf<UUID>()
        val control = GitWorkspacesControl()
        // An operator pauses while the second workspace of the first page is being observed.
        val statuses = observer(visited) { if (visited.size == 2) control.pauseMonitor() }
        val monitor = GitWorkspaceMonitor(bindings, resolver(), statuses, control = control)

        monitor.poll()

        visited shouldBe rows.take(2).map { it.rootCaseId }
        control.resumeMonitor()
        // The cursor already moved past the full first page, so rows 3 to 5 are not retried now.
        monitor.poll()
        visited shouldBe (rows.take(2) + rows.drop(5)).map { it.rootCaseId }
        // That short last page reset the cursor: the skipped rows come back on the next cycle.
        monitor.poll()
        visited shouldBe (rows.take(2) + rows.drop(5) + rows.take(5)).map { it.rootCaseId }
    }
})
