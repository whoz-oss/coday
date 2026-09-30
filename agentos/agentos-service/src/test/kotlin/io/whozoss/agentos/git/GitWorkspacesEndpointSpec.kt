package io.whozoss.agentos.git

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import org.springframework.boot.actuate.endpoint.InvalidEndpointRequestException
import java.time.Instant

class GitWorkspacesEndpointSpec : StringSpec({
    fun idleWorker(): CaseWorkspaceWorker =
        mockk { every { activity() } returns CaseWorkspaceWorker.WorkerActivity(false, null, null) }

    "an idle worker reports nothing in progress" {
        val control = GitWorkspacesControl()
        val endpoint = GitWorkspacesEndpoint(control, idleWorker())
        endpoint.status() shouldBe mapOf(
            "provisioningPaused" to false,
            "monitorPaused" to false,
            "sweeping" to false,
            "currentItem" to null,
            "currentSince" to null,
        )
        endpoint.control("provisioning", "pause")["provisioningPaused"] shouldBe true
    }

    "status reports the item in progress and since when" {
        val since = Instant.parse("2026-09-29T10:00:00Z")
        val worker = mockk<CaseWorkspaceWorker> {
            every { activity() } returns CaseWorkspaceWorker.WorkerActivity(true, "checkout ns", since)
        }
        val status = GitWorkspacesEndpoint(GitWorkspacesControl(), worker).status()
        status["sweeping"] shouldBe true
        status["currentItem"] shouldBe "checkout ns"
        status["currentSince"] shouldBe "2026-09-29T10:00:00Z"
    }

    "pause and resume apply to provisioning, alone or through all" {
        val control = GitWorkspacesControl()
        val endpoint = GitWorkspacesEndpoint(control, idleWorker())
        listOf("provisioning", "all").forEach { phase ->
            endpoint.control(phase, "pause")
            control.isProvisioningPaused() shouldBe true
            endpoint.control(phase, "resume")
            control.isProvisioningPaused() shouldBe false
        }
    }

    "the monitor phase pauses status polling alone, and all pauses both" {
        val control = GitWorkspacesControl()
        val endpoint = GitWorkspacesEndpoint(control, idleWorker())
        endpoint.control("monitor", "pause")
        control.isMonitorPaused() shouldBe true
        control.isProvisioningPaused() shouldBe false
        endpoint.control("all", "pause")
        endpoint.status()["provisioningPaused"] shouldBe true
        endpoint.control("all", "resume")
        control.isMonitorPaused() shouldBe false
        control.isProvisioningPaused() shouldBe false
    }

    "an unknown phase or action is rejected without changing anything" {
        val control = GitWorkspacesControl()
        val endpoint = GitWorkspacesEndpoint(control, idleWorker())
        shouldThrow<InvalidEndpointRequestException> { endpoint.control("everything", "pause") }
        shouldThrow<InvalidEndpointRequestException> { endpoint.control("provisioning", "stop") }
        control.isProvisioningPaused() shouldBe false
    }

    "the paused gauge follows the switch" {
        val meters = SimpleMeterRegistry()
        val control = GitWorkspacesControl(meters)
        val gauge = { meters.get(GitWorkspacesControl.PROVISIONING_PAUSED_GAUGE).gauge().value() }
        gauge() shouldBe 0.0
        control.pauseProvisioning()
        gauge() shouldBe 1.0
        control.resumeProvisioning()
        gauge() shouldBe 0.0
    }
})
