package io.whozoss.agentos.git

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.data.forAll
import io.kotest.data.row
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

    "each phase pauses exactly what it names, and resumes it" {
        forAll(
            row("provisioning", true, false),
            row("monitor", false, true),
            row("all", true, true),
        ) { phase, provisioning, monitor ->
            val control = GitWorkspacesControl()
            val endpoint = GitWorkspacesEndpoint(control, idleWorker())

            endpoint.control(phase, "pause")
            control.isProvisioningPaused() shouldBe provisioning
            control.isMonitorPaused() shouldBe monitor
            endpoint.control(phase, "resume")
            control.isProvisioningPaused() shouldBe false
            control.isMonitorPaused() shouldBe false
        }
    }

    "an unknown phase or action is rejected without changing anything" {
        val control = GitWorkspacesControl()
        val endpoint = GitWorkspacesEndpoint(control, idleWorker())
        shouldThrow<InvalidEndpointRequestException> { endpoint.control("everything", "pause") }
        shouldThrow<InvalidEndpointRequestException> { endpoint.control("provisioning", "stop") }
        control.isProvisioningPaused() shouldBe false
    }

    "each paused gauge follows its switch" {
        forAll(
            row(
                GitWorkspacesControl.PROVISIONING_PAUSED_GAUGE,
                GitWorkspacesControl::pauseProvisioning,
                GitWorkspacesControl::resumeProvisioning,
            ),
            row(
                GitWorkspacesControl.MONITOR_PAUSED_GAUGE,
                GitWorkspacesControl::pauseMonitor,
                GitWorkspacesControl::resumeMonitor,
            ),
        ) { name, pause, resume ->
            val meters = SimpleMeterRegistry()
            val control = GitWorkspacesControl(meters)
            val gauge = { meters.get(name).gauge().value() }
            gauge() shouldBe 0.0
            pause(control)
            gauge() shouldBe 1.0
            resume(control)
            gauge() shouldBe 0.0
        }
    }
})
