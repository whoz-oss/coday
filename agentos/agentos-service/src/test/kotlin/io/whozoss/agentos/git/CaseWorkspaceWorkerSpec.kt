package io.whozoss.agentos.git

import io.kotest.core.spec.style.StringSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.UUID

class CaseWorkspaceWorkerSpec : StringSpec({
    val settings = GitRepositorySettings(UUID.randomUUID(), UUID.randomUUID(), "https://example.com/repo.git", "main", UUID.randomUUID())
    val checkout = RepositoryCheckout(namespaceId = settings.namespaceId, integrationConfigId = settings.configId,
        repositoryUrl = settings.repositoryUrl, mainBranch = settings.mainBranch)
    val associations = mockk<GitRepositoryAssociationService>()
    val checkouts = mockk<RepositoryCheckoutService>(relaxed = true)
    val provisioner = mockk<RepositoryCheckoutProvisioner>(relaxed = true)
    val worker = CaseWorkspaceWorker(associations, checkouts, provisioner)
    beforeTest {
        io.mockk.clearMocks(associations, checkouts, provisioner)
        every { checkouts.findByStatusIn(listOf(RepositoryCheckoutStatus.PREPARING), any()) } returns listOf(checkout)
        every { associations.findSettings(settings.namespaceId) } returns settings
    }
    "a requested namespace clone is prepared without any case" {
        worker.provisionPending()
        verify(exactly = 1) { provisioner.ensureReady(settings) }
    }
    "a removed association fails its queued preparation" {
        every { associations.findSettings(settings.namespaceId) } returns null
        worker.provisionPending()
        verify(exactly = 0) { provisioner.ensureReady(any()) }
        verify(exactly = 1) { checkouts.markStatus(checkout.id, RepositoryCheckoutStatus.FAILED, any()) }
    }
    "a failed sweep releases its guard for the next pass" {
        every { checkouts.findByStatusIn(any(), any()) } throws IllegalStateException("unavailable")
        worker.provisionPending()
        every { checkouts.findByStatusIn(any(), any()) } returns listOf(checkout)
        worker.provisionPending()
        verify(exactly = 1) { provisioner.ensureReady(settings) }
    }
})
