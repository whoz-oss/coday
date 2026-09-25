package io.whozoss.agentos.git

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.CaseRepository
import io.whozoss.agentos.sdk.entity.EntityMetadata
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
})
