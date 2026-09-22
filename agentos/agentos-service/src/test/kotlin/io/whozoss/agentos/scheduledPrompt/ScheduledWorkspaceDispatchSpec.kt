package io.whozoss.agentos.scheduledPrompt

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.caseFlow.*
import io.whozoss.agentos.git.*
import java.util.UUID

class ScheduledWorkspaceDispatchSpec : StringSpec({
    "reclaiming an occurrence reuses its durable case even after automatic workspaces are disabled" {
        val rows = mutableMapOf<UUID, Case>()
        val ns = UUID.randomUUID()
        val prompt = UUID.randomUUID()
        val occurrence = UUID.randomUUID()
        val cases = mockk<CaseService> {
            every { findById(any(), true) } answers { rows[firstArg<UUID>()] }
            every { create(any()) } answers { firstArg<Case>().also { rows[it.id] = it } }
        }
        val settings = GitRepositorySettings(UUID.randomUUID(), ns, "https://example.com/repo", "main", UUID.randomUUID(), true, null)
        val associations = mockk<GitRepositoryAssociationService> { every { findSettings(ns) } returns settings }
        val journal = mockk<CaseCommandJournal> { every { hasReceipt(any(), occurrence) } returns false }
        val dispatch = ScheduledWorkspaceDispatch(cases, associations, journal)
        val first = dispatch.caseFor(occurrence, ns, "Original title", prompt)!!
        dispatch.alreadyAccepted(first.id, occurrence) shouldBe false
        every { associations.findSettings(ns) } returns null
        every { journal.hasReceipt(first.id, occurrence) } returns true
        dispatch.caseFor(occurrence, ns, "Edited title", prompt) shouldBe first
        dispatch.alreadyAccepted(first.id, occurrence) shouldBe true
        verify(exactly = 1) { cases.create(any()) }
        rows[first.id] = first.copy(metadata = first.metadata.copy(removed = true))
        shouldThrow<IllegalStateException> { dispatch.caseFor(occurrence, ns, "Again", prompt) }
        dispatch.caseFor(UUID.randomUUID(), ns, "Ordinary namespace", prompt) shouldBe null
    }
})
