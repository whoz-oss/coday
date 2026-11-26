package io.whozoss.factory.capability

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.whozoss.factory.agentattempt.domain.AgentAttemptStatus
import io.whozoss.factory.agentattempt.domain.AgentStepResultClaim
import io.whozoss.factory.agentattempt.domain.AgentStepResultStatus
import io.whozoss.factory.agentattempt.domain.AgentStepResultSubmitted
import io.whozoss.factory.agentattempt.domain.CanonicalJsonHash
import io.whozoss.factory.agentattempt.domain.DurableAgentAttempt
import io.whozoss.factory.agentattempt.domain.IssuedCapability
import io.whozoss.factory.agentattempt.service.AgentStepResultService
import io.whozoss.factory.agentattempt.service.DurableAgentAttemptService
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.workflow.domain.ResponsibilityKind
import io.whozoss.factory.workflow.domain.WorkflowEvidenceItem
import io.whozoss.factory.workflow.domain.WorkflowInstanceRecord
import io.whozoss.factory.workflow.domain.WorkflowStatuses
import io.whozoss.factory.workflow.domain.WorkflowStepDefinition
import io.whozoss.factory.workflow.domain.WorkflowStepResponsibility
import io.whozoss.factory.workflow.domain.WorkflowStepStateRecord
import io.whozoss.factory.workflow.persistence.WorkflowEvidenceRepository
import io.whozoss.factory.workflow.persistence.WorkflowRepository
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The agent `IN_PROGRESS` path of [CapabilityExecutionService], without Spring or a database:
 * the re-arm of the next poll attempt on the SAME case family, the replay of a finished poll attempt and the
 * rule that an accepted IN_PROGRESS is never a final result nor a failure.
 */
class CapabilityExecutionInProgressTest {

    private val scope = TenantScope("org", "ws")

    /** A real attempt id is a UUID: the successor brief is derived by an exact textual replacement of it. */
    private val predecessorId = "6f1c2a9e-0b7d-4c55-9a31-1d2e3f4a5b6c"
    private val step = WorkflowStepDefinition(
        "work", "Work", WorkflowStepResponsibility(ResponsibilityKind.AGENT, "Dev"), emptyList(),
    )
    private val predecessorBrief =
        "Use Factory attemptId '$predecessorId' and Idempotency-Key 'factory:$predecessorId:result'. Check the deployment."
    private val predecessor = DurableAgentAttempt(
        predecessorId, "case-1", "ns", "wf", "work", 1, "Dev",
        brief = predecessorBrief,
        contextEnvelope = """{"schemaVersion":1,"attemptNumber":1,"briefHash":"sha256:old","inputs":{}}""",
        rootCaseId = "root", parentCaseId = "root", environmentRef = "env-1", expectedEnvironmentRevision = 2,
        expectedAmendmentSeq = 4L, status = AgentAttemptStatus.INDETERMINATE, failureCode = "AGENT_IN_PROGRESS",
    )

    private fun submitted(status: AgentStepResultStatus, id: String = predecessorId) = AgentStepResultSubmitted(
        "result-submitted", "result-$id", id, "wf", "work", "ns", "case-1", "Dev", "hash",
        status, "deployment at 60%", emptyList(), AgentStepResultClaim(emptyList()), emptyList(),
        "2026-01-01T00:00:00Z", "hash-$id", retryAfterSeconds = 45.takeIf { status == AgentStepResultStatus.IN_PROGRESS },
    )

    private class Fixture(
        val service: CapabilityExecutionService,
        val attempts: DurableAgentAttemptService,
        val results: AgentStepResultService,
        val registered: io.mockk.CapturingSlot<DurableAgentAttempt>,
    )

    private fun fixture(pollAttemptId: String?, latest: DurableAgentAttempt = predecessor): Fixture {
        val workflows = mockk<WorkflowRepository>()
        val states = if (pollAttemptId == null) emptyList() else listOf(
            WorkflowStepStateRecord(
                "ns", "wf", "work", 1, WorkflowStatuses.WAITING_EXTERNAL,
                mapOf("poll" to mapOf("attemptId" to pollAttemptId, "pollCount" to 1, "reason" to "deployment at 60%")),
            ),
        )
        every { workflows.findStepStates(scope, "ns", "wf") } returns states
        every { workflows.findInstance(scope, "ns", "wf") } returns WorkflowInstanceRecord(
            "ns", "wf", 1, "active", "h", emptyMap(),
            mapOf("steps" to listOf(mapOf(
                "id" to "work", "name" to "Work", "responsibility" to step.responsibility.toJson(), "dependsOn" to emptyList<String>(),
            ))),
        )
        val attempts = mockk<DurableAgentAttemptService>()
        every { attempts.findLatestForStep(scope, "ns", "wf", "work") } returns latest
        every { attempts.find(scope, "ns", "wf", "work", latest.attemptId) } returns latest
        every { attempts.nextAttemptNumber(scope, "ns", "wf", "work") } returns 2
        val registered = slot<DurableAgentAttempt>()
        every { attempts.registerRetry(scope, capture(registered), any()) } answers { registered.captured }
        val results = mockk<AgentStepResultService>()
        every { results.issue(scope, any(), any(), any()) } returns IssuedCapability("new-token", "2026-01-01T00:15:00Z")
        every { results.acceptedResult(scope, "ns", "wf", "work", predecessorId) } returns submitted(AgentStepResultStatus.IN_PROGRESS)
        val recorded = mutableListOf<WorkflowEvidenceItem>(
            WorkflowEvidenceItem(
                "ev-1", "ns", "wf", "work", "agent-result", "in_progress", null,
                mapOf("attemptId" to predecessorId, "status" to "IN_PROGRESS"), null, null,
            ),
        )
        val evidence = mockk<WorkflowEvidenceRepository>(relaxed = true)
        every { evidence.list(scope, "ns", "wf", "work") } answers { recorded.toList() }
        val service = CapabilityExecutionService(
            resolver = CapabilityResolver(), workflowRepository = workflows, evidenceRepository = evidence,
            interactionRepository = mockk(relaxed = true), attemptRepository = mockk(relaxed = true),
            durableAgentAttemptService = attempts, agentOsExecutionAdapter = mockk(relaxed = true),
            agentStepResultService = results,
        )
        return Fixture(service, attempts, results, registered)
    }

    @Test
    fun `a poll of a parked agent step registers attempt N+1 on the same case family with its own capability and the poll section`() {
        val f = fixture(pollAttemptId = predecessorId)

        // The registered successor is captured before the dispatch phase, which this unit fixture does not drive.
        runCatching { f.service.resolveAndRecord(scope, "ns", "wf", step, Path.of(".")) }

        val successor = f.registered.captured
        assertThat(successor.attemptNumber).isEqualTo(2)
        assertThat(successor.attemptId).isEqualTo(CapabilityExecutionService.retryAttemptId("wf", "work", 2))
        assertThat(successor.caseId).isEqualTo("case-1")
        assertThat(successor.rootCaseId).isEqualTo("root")
        assertThat(successor.parentCaseId).isEqualTo("root")
        assertThat(successor.environmentRef).isEqualTo("env-1")
        assertThat(successor.expectedEnvironmentRevision).isEqualTo(2)
        assertThat(successor.expectedAmendmentSeq).isEqualTo(4L)
        assertThat(successor.capabilityToken).isEqualTo("new-token")
        assertThat(successor.resumptionContext).contains("external-poll").contains("\"pollNumber\":2")
        // The brief carries the N+1 identifiers, never the predecessor's, plus the continuation section.
        val brief = successor.brief!!
        assertThat(brief).doesNotContain(predecessorId)
        assertThat(brief).contains("attemptId '${successor.attemptId}'").contains("factory:${successor.attemptId}:result")
        assertThat(brief).contains("## External poll continuation").contains("poll #2").contains("deployment at 60%")
        assertThat(brief).contains("Check the deployment.")
        // The capability is bound to the successor brief, and the frozen envelope is re-pinned to it.
        verify { f.results.issue(scope, match { it.attemptId == successor.attemptId && it.briefHash == CanonicalJsonHash.sha256(brief) }, any(), any()) }
        assertThat(successor.contextEnvelope).contains(CanonicalJsonHash.sha256(brief)).contains("\"attemptNumber\":2")
    }

    @Test
    fun `no successor is registered when the step is not parked on the latest attempt`() {
        // No poll state at all (legacy / first run): nothing to re-arm.
        val unparked = fixture(pollAttemptId = null)
        runCatching { unparked.service.resolveAndRecord(scope, "ns", "wf", step, Path.of(".")) }
        verify(exactly = 0) { unparked.attempts.registerRetry(any(), any(), any()) }

        // The poll belongs to another attempt (another execution): it is never reused.
        val other = fixture(pollAttemptId = "another-attempt")
        runCatching { other.service.resolveAndRecord(scope, "ns", "wf", step, Path.of(".")) }
        verify(exactly = 0) { other.attempts.registerRetry(any(), any(), any()) }

        // The latest attempt is not an IN_PROGRESS one: it is replayed, not polled.
        val failed = fixture(
            pollAttemptId = predecessorId,
            latest = predecessor.copy(status = AgentAttemptStatus.FAILED, failureCode = "AGENT_INDETERMINATE"),
        )
        runCatching { failed.service.resolveAndRecord(scope, "ns", "wf", step, Path.of(".")) }
        verify(exactly = 0) { failed.attempts.registerRetry(any(), any(), any()) }
    }

    @Test
    fun `replaying a finished IN_PROGRESS attempt yields AgentInProgress and never AgentFailed`() {
        val f = fixture(pollAttemptId = null, latest = predecessor.copy(status = AgentAttemptStatus.INDETERMINATE))

        val execution = f.service.resolveAndRecord(scope, "ns", "wf", step, Path.of("."))

        val outcome = execution.outcome as CapabilityOutcome.AgentInProgress
        assertThat(outcome.attemptId).isEqualTo(predecessorId)
        assertThat(outcome.resultId).isEqualTo("result-$predecessorId")
        assertThat(outcome.retryAfterSeconds).isEqualTo(45)
        assertThat(execution.evidenceId).isEqualTo("ev-1")
        verify(exactly = 0) { f.attempts.registerRetry(any(), any(), any()) }
    }

    @Test
    fun `an accepted IN_PROGRESS is not a final result while PASS and FAIL are`() {
        for ((status, final) in listOf(
            AgentStepResultStatus.PASS to true, AgentStepResultStatus.FAIL to true,
            AgentStepResultStatus.NEEDS_RESEARCH to false, AgentStepResultStatus.IN_PROGRESS to false,
        )) {
            val f = fixture(pollAttemptId = null)
            every { f.results.acceptedResult(scope, "ns", "wf", "work", predecessorId) } returns submitted(status)

            assertThat(f.service.hasAcceptedFinalResult(scope, "ns", "wf", "work")).describedAs(status.name).isEqualTo(final)
        }
        val none = fixture(pollAttemptId = null)
        every { none.results.acceptedResult(scope, "ns", "wf", "work", predecessorId) } returns null
        assertThat(none.service.hasAcceptedFinalResult(scope, "ns", "wf", "work")).isFalse()
    }
}
