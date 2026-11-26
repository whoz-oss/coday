package io.whozoss.factory.workflow

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.agentattempt.service.OutboxDrainService
import io.whozoss.factory.capability.CapabilityExecution
import io.whozoss.factory.capability.CapabilityExecutionService
import io.whozoss.factory.capability.CapabilityOutcome
import io.whozoss.factory.workflow.config.ExternalPollingProperties
import io.whozoss.factory.workflow.domain.ControllerExecutionInput
import io.whozoss.factory.workflow.domain.WorkflowDefinitionRecord
import io.whozoss.factory.workflow.domain.WorkflowDefinitionValidation
import io.whozoss.factory.workflow.domain.WorkflowDefinitionValidator
import io.whozoss.factory.workflow.domain.WorkflowStartCommand
import io.whozoss.factory.workflow.domain.WorkflowStatuses
import io.whozoss.factory.workflow.domain.hashWorkflowDefinition
import io.whozoss.factory.workflow.persistence.HumanInteractionRepository
import io.whozoss.factory.workflow.persistence.WorkflowEvidenceRepository
import io.whozoss.factory.workflow.persistence.WorkflowRepository
import io.whozoss.factory.workflow.service.SessionRunService
import io.whozoss.factory.workflow.service.WorkflowService
import io.whozoss.factory.workflow.sse.WorkflowSseHub
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired

/**
 * Sequencer-level tests of the external polling of a `waiting_external` step: it is parked (not failed, no worker
 * held), re-polled when due, finished by a verdict, and failed with `POLL_TIMEOUT_EXCEEDED` after its window.
 * The capability is stubbed and the clock is controlled; the persistence is the real embedded Neo4j.
 */
class ExternalPollingSessionTest : Neo4jDomainIntegrationTest() {

    @Autowired private lateinit var workflowService: WorkflowService
    @Autowired private lateinit var workflowRepository: WorkflowRepository
    @Autowired private lateinit var evidenceRepository: WorkflowEvidenceRepository
    @Autowired private lateinit var interactionRepository: HumanInteractionRepository
    @Autowired private lateinit var sseHub: WorkflowSseHub

    @TempDir lateinit var repoRoot: Path

    private val namespace = "2b6f8d2f-8d1a-4f0e-9b6e-external-polling"

    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now
    }

    private fun step(id: String, name: String, dependsOn: List<String>) = linkedMapOf<String, Any?>(
        "id" to id, "name" to "Step $id", "responsibility" to mapOf("kind" to "code", "name" to name), "dependsOn" to dependsOn,
    )

    private fun start(workflowId: String) {
        val raw = linkedMapOf<String, Any?>(
            "schemaVersion" to "1", "workflowType" to workflowId, "version" to "1.0.0", "title" to workflowId,
            "steps" to listOf(step("ci", "github-pr-checks", emptyList()), step("after", "check", listOf("ci"))),
        )
        val valid = WorkflowDefinitionValidator.validate(raw) as WorkflowDefinitionValidation.Valid
        workflowService.registerDefinition(
            scope,
            WorkflowDefinitionRecord(workflowId, "1.0.0", hashWorkflowDefinition(valid.definition), valid.definition),
        )
        val result = workflowService.start(
            scope, namespace, WorkflowStartCommand(workflowId, workflowId, workflowId),
            ControllerExecutionInput(runtimeId = "test", kind = "agentos", agentId = "runner", namespaceId = namespace),
        )
        assertThat(result.status).isEqualTo(201)
    }

    private fun pending(reason: String = "CI_CHECKS_PENDING", retryAfter: Int? = null) = CapabilityExecution(
        CapabilityOutcome.ExternalPending("ci", "ci-checks", reason, "2 checks running", retryAfter),
    )

    private fun green(stepId: String) = CapabilityExecution(
        CapabilityOutcome.CiChecksCompleted(stepId, true, 7, "https://github.com/o/r/pull/7", "a".repeat(40), "feat", emptyList(), emptyList()),
    )

    private class Fixture(val service: SessionRunService, val capability: CapabilityExecutionService, val drain: OutboxDrainService, val clock: MutableClock)

    private fun fixture(properties: ExternalPollingProperties = ExternalPollingProperties()): Fixture {
        val capability = mockk<CapabilityExecutionService>()
        val drain = mockk<OutboxDrainService>(relaxed = true)
        val clock = MutableClock(Instant.parse("2026-06-01T10:00:00Z"))
        val service = SessionRunService(
            workflowRepository, evidenceRepository, interactionRepository, capability, sseHub,
            outboxDrainService = drain, pollingProperties = properties, clock = clock,
        )
        return Fixture(service, capability, drain, clock)
    }

    private fun statusOf(workflowId: String, stepId: String) =
        workflowRepository.findStepStates(scope, namespace, workflowId).first { it.stepId == stepId }.status

    private fun pollOf(workflowId: String, stepId: String): Map<*, *> =
        workflowRepository.findStepStates(scope, namespace, workflowId).first { it.stepId == stepId }.payload["poll"] as Map<*, *>

    @Test
    fun `a pending step is parked in waiting_external and a wake is scheduled 60 seconds later`() {
        val id = "poll-park"
        start(id)
        val f = fixture()
        every { f.capability.resolveAndRecord(any(), any(), any(), any(), any(), any(), any()) } returns pending()
        val notBefore = slot<Instant>()
        every { f.drain.scheduleWake(any(), any(), any(), any(), any(), capture(notBefore)) } returns Unit

        val result = f.service.runSession(scope, namespace, id, repoRoot)

        assertThat(result.status).isEqualTo(WorkflowStatuses.WAITING_EXTERNAL)
        assertThat(statusOf(id, "ci")).isEqualTo(WorkflowStatuses.WAITING_EXTERNAL)
        assertThat(statusOf(id, "after")).isEqualTo(WorkflowStatuses.PENDING)
        assertThat(notBefore.captured).isEqualTo(f.clock.now.plusSeconds(60))
        val poll = pollOf(id, "ci")
        assertThat(poll["pollCount"]).isEqualTo(1)
        assertThat(poll["intervalSeconds"]).isEqualTo(60)
        assertThat(poll["source"]).isEqualTo("ci-checks")
        assertThat(poll["firstPollStartedAt"]).isEqualTo(f.clock.now.toString())
        assertThat(poll["deadlineAt"]).isEqualTo(f.clock.now.plusSeconds(1800).toString())
        // Never a failure: the run is not sealed and no failed evidence exists.
        assertThat(workflowRepository.findInstance(scope, namespace, id)!!.projection["status"]).isEqualTo(WorkflowStatuses.WAITING_EXTERNAL)
        assertThat(evidenceRepository.list(scope, namespace, id).filter { it.outcome == WorkflowStatuses.FAILED }).isEmpty()
        verify(exactly = 1) { f.capability.resolveAndRecord(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a run before the next poll is due does not execute the capability again`() {
        val id = "poll-not-due"
        start(id)
        val f = fixture()
        every { f.capability.resolveAndRecord(any(), any(), any(), any(), any(), any(), any()) } returns pending()
        f.service.runSession(scope, namespace, id, repoRoot)

        f.clock.now = f.clock.now.plusSeconds(30)
        val result = f.service.runSession(scope, namespace, id, repoRoot)

        assertThat(result.status).isEqualTo(WorkflowStatuses.WAITING_EXTERNAL)
        verify(exactly = 1) { f.capability.resolveAndRecord(any(), any(), any(), any(), any(), any(), any()) }
        assertThat(pollOf(id, "ci")["pollCount"]).isEqualTo(1)
    }

    @Test
    fun `once due the step is polled again and counts the poll`() {
        val id = "poll-due"
        start(id)
        val f = fixture()
        every { f.capability.resolveAndRecord(any(), any(), any(), any(), any(), any(), any()) } returns pending()
        f.service.runSession(scope, namespace, id, repoRoot)
        val first = pollOf(id, "ci")

        f.clock.now = f.clock.now.plusSeconds(61)
        f.service.runSession(scope, namespace, id, repoRoot)

        verify(exactly = 2) { f.capability.resolveAndRecord(any(), any(), any(), any(), any(), any(), any()) }
        val second = pollOf(id, "ci")
        assertThat(second["pollCount"]).isEqualTo(2)
        // The window is measured from the FIRST poll, not from the latest one.
        assertThat(second["firstPollStartedAt"]).isEqualTo(first["firstPollStartedAt"])
        assertThat(second["deadlineAt"]).isEqualTo(first["deadlineAt"])
        assertThat(statusOf(id, "ci")).isEqualTo(WorkflowStatuses.WAITING_EXTERNAL)
    }

    @Test
    fun `a green verdict completes the step releases its dependants and clears the poll`() {
        val id = "poll-green"
        start(id)
        val f = fixture()
        var calls = 0
        every { f.capability.resolveAndRecord(any(), any(), any(), any(), any(), any(), any()) } answers {
            calls++
            val step = invocation.args[3] as io.whozoss.factory.workflow.domain.WorkflowStepDefinition
            if (calls == 1) pending() else green(step.id)
        }
        f.service.runSession(scope, namespace, id, repoRoot)

        f.clock.now = f.clock.now.plusSeconds(61)
        val result = f.service.runSession(scope, namespace, id, repoRoot)

        assertThat(result.status).isEqualTo(WorkflowStatuses.COMPLETED)
        assertThat(statusOf(id, "ci")).isEqualTo(WorkflowStatuses.COMPLETED)
        assertThat(statusOf(id, "after")).isEqualTo(WorkflowStatuses.COMPLETED)
        assertThat(workflowRepository.findStepStates(scope, namespace, id).first { it.stepId == "ci" }.payload).doesNotContainKey("poll")
    }

    @Test
    fun `still pending after thirty minutes fails with POLL_TIMEOUT_EXCEEDED and blocks dependants`() {
        val id = "poll-timeout"
        start(id)
        val f = fixture()
        every { f.capability.resolveAndRecord(any(), any(), any(), any(), any(), any(), any()) } returns pending()
        f.service.runSession(scope, namespace, id, repoRoot)
        val polls = pollOf(id, "ci")

        f.clock.now = Instant.parse(polls["deadlineAt"] as String).plusSeconds(1)
        val result = f.service.runSession(scope, namespace, id, repoRoot)

        assertThat(result.status).isEqualTo(WorkflowStatuses.FAILED)
        assertThat(statusOf(id, "ci")).isEqualTo(WorkflowStatuses.FAILED)
        assertThat(statusOf(id, "after")).isEqualTo(WorkflowStatuses.BLOCKED)
        val failure = evidenceRepository.list(scope, namespace, id, "ci").last { it.outcome == WorkflowStatuses.FAILED }
        assertThat(failure.facts["code"]).isEqualTo("POLL_TIMEOUT_EXCEEDED")
        assertThat(failure.facts["pollCount"]).isEqualTo(1)
        // The capability is not called again once the window is over.
        verify(exactly = 1) { f.capability.resolveAndRecord(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `the next poll is never scheduled after the deadline`() {
        val id = "poll-deadline-cap"
        start(id)
        val f = fixture(ExternalPollingProperties(maxWaitSeconds = 90))
        every { f.capability.resolveAndRecord(any(), any(), any(), any(), any(), any(), any()) } returns pending()
        val notBefore = mutableListOf<Instant>()
        every { f.drain.scheduleWake(any(), any(), any(), any(), any(), capture(notBefore)) } returns Unit
        f.service.runSession(scope, namespace, id, repoRoot)
        f.clock.now = f.clock.now.plusSeconds(61)
        f.service.runSession(scope, namespace, id, repoRoot)

        // 60 s, then min(61 + 60, 90) = 90 s: the second wake lands exactly on the deadline.
        assertThat(notBefore).hasSize(2)
        assertThat(notBefore.last()).isEqualTo(Instant.parse("2026-06-01T10:00:00Z").plusSeconds(90))
    }

    @Test
    fun `a requested interval is clamped to the configured bounds`() {
        val id = "poll-clamp"
        start(id)
        val f = fixture()
        every { f.capability.resolveAndRecord(any(), any(), any(), any(), any(), any(), any()) } returns pending(retryAfter = 5)
        f.service.runSession(scope, namespace, id, repoRoot)
        assertThat(pollOf(id, "ci")["intervalSeconds"]).isEqualTo(30)
    }

    @Test
    fun `polling never records a correction cycle nor a failed outcome`() {
        val id = "poll-no-budget"
        start(id)
        val f = fixture()
        every { f.capability.resolveAndRecord(any(), any(), any(), any(), any(), any(), any()) } returns pending()
        repeat(5) {
            f.service.runSession(scope, namespace, id, repoRoot)
            f.clock.now = f.clock.now.plusSeconds(61)
        }
        val projection = workflowRepository.findInstance(scope, namespace, id)!!.projection
        assertThat(projection).doesNotContainKeys("correctionCounts", "correction")
        assertThat(statusOf(id, "ci")).isEqualTo(WorkflowStatuses.WAITING_EXTERNAL)
        assertThat(pollOf(id, "ci")["pollCount"]).isEqualTo(5)
    }
}
