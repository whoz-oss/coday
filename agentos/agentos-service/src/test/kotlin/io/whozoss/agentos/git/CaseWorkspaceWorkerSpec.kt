package io.whozoss.agentos.git

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
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
    "metrics exist before the first sweep so a healthy instance reports zero" {
        val meters = SimpleMeterRegistry()
        CaseWorkspaceWorker(associations, checkouts, provisioner, meterRegistry = meters)
        meters.get(CaseWorkspaceWorker.SWEEP_TIMER).timer().count() shouldBe 0L
        meters.get(CaseWorkspaceWorker.ERROR_COUNTER).tag("operation", "checkout").counter().count() shouldBe 0.0
    }
    "a paused worker leaves the queue untouched and records no sweep" {
        val meters = SimpleMeterRegistry()
        val control = GitWorkspacesControl().also { it.pauseProvisioning() }
        CaseWorkspaceWorker(associations, checkouts, provisioner, meterRegistry = meters, control = control).provisionPending()
        verify(exactly = 0) { checkouts.findByStatusIn(any(), any()) }
        meters.get(CaseWorkspaceWorker.SWEEP_TIMER).timer().count() shouldBe 0L
    }
    "a pause during a batch lets the current item finish and stops before the next one" {
        val control = GitWorkspacesControl()
        val other = settings.copy(namespaceId = UUID.randomUUID())
        val second = checkout.copy(namespaceId = other.namespaceId)
        every { checkouts.findByStatusIn(listOf(RepositoryCheckoutStatus.PREPARING), any()) } returns listOf(checkout, second)
        every { associations.findSettings(other.namespaceId) } returns other
        every { provisioner.ensureReady(settings) } answers { control.pauseProvisioning(); checkout }
        CaseWorkspaceWorker(associations, checkouts, provisioner, control = control).provisionPending()
        verify(exactly = 1) { provisioner.ensureReady(settings) }
        verify(exactly = 0) { provisioner.ensureReady(other) }
    }
    "resuming lets the next sweep pick the queue up again" {
        val control = GitWorkspacesControl().also { it.pauseProvisioning() }
        val worker = CaseWorkspaceWorker(associations, checkouts, provisioner, control = control)
        worker.provisionPending()
        control.resumeProvisioning()
        worker.provisionPending()
        verify(exactly = 1) { provisioner.ensureReady(settings) }
    }
})
