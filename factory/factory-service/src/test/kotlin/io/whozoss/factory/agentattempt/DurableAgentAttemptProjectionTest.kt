package io.whozoss.factory.agentattempt

import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.agentattempt.domain.AgentAttemptStatus
import io.whozoss.factory.agentattempt.domain.DurableAgentAttempt
import io.whozoss.factory.agentattempt.domain.DurableAgentAttemptDto
import io.whozoss.factory.agentattempt.domain.toDto
import io.whozoss.factory.agentattempt.service.DurableAgentAttemptService
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import kotlin.reflect.full.memberProperties

/**
 * Req 9 attestation: the attempt projection ([DurableAgentAttemptDto]) is a
 * bounded, secret-free read model served purely from persistence — no AgentOS
 * call, no SSE stream, no live runtime dependency.
 */
class DurableAgentAttemptProjectionTest : Neo4jDomainIntegrationTest() {

    @Autowired
    private lateinit var service: DurableAgentAttemptService

    @Test
    fun `the dto carries no execution secret nor internal recovery or lease data`() {
        val exposed = DurableAgentAttemptDto::class.memberProperties.map { it.name }
        val secrets = listOf(
            "ownerToken",
            "capabilityToken",
            "commandId",
            "brief",
            "leaseExpiresAt",
            "lastObservedEventId",
            "turnCorrelation",
        )
        assertThat(exposed).doesNotContainAnyElementsOf(secrets)
    }

    @Test
    fun `toDto maps the identity and lifecycle fields including the environment link`() {
        val now = Instant.parse("2026-01-01T00:00:00Z")
        val attempt = DurableAgentAttempt(
            attemptId = "attempt-dto",
            caseId = "case-1",
            namespaceId = NAMESPACE_ID,
            workflowId = WORKFLOW_ID,
            stepId = STEP_ID,
            attemptNumber = 2,
            agentName = "builder",
            capabilityToken = "cap-secret",
            ownerToken = "owner-secret",
            turnCorrelation = "turn-secret",
            commandId = "cmd-secret",
            brief = "brief-secret",
            environmentRef = "env-1",
            expectedEnvironmentRevision = 4,
            status = AgentAttemptStatus.FAILED,
            failureCode = "AGENT_CASE_ERROR",
            resultEvidenceId = "evidence-1",
            lastObservedEventId = "evt-secret",
            revision = 7,
            createdAt = now,
            startedAt = now,
            updatedAt = now,
            completedAt = now,
            leaseExpiresAt = now,
        )

        val dto = attempt.toDto()

        assertThat(dto.attemptId).isEqualTo("attempt-dto")
        assertThat(dto.stepId).isEqualTo(STEP_ID)
        assertThat(dto.attemptNumber).isEqualTo(2)
        assertThat(dto.agentName).isEqualTo("builder")
        assertThat(dto.status).isEqualTo("failed")
        assertThat(dto.caseId).isEqualTo("case-1")
        assertThat(dto.failureCode).isEqualTo("AGENT_CASE_ERROR")
        assertThat(dto.resultEvidenceId).isEqualTo("evidence-1")
        assertThat(dto.environmentRef).isEqualTo("env-1")
        assertThat(dto.expectedEnvironmentRevision).isEqualTo(4)
        assertThat(dto.revision).isEqualTo(7)
        assertThat(dto.createdAt).isEqualTo(now)
        assertThat(dto.startedAt).isEqualTo(now)
        assertThat(dto.completedAt).isEqualTo(now)
    }

    @Test
    fun `the projection reflects the persisted terminal state with no live runtime interaction`() {
        // The whole lifecycle runs against persistence only: no adapter, no SSE.
        service.register(scope, attempt("attempt-projection"))
        service.claim(
            scope,
            namespaceId = NAMESPACE_ID,
            workflowId = WORKFLOW_ID,
            stepId = STEP_ID,
            attemptId = "attempt-projection",
            ownerToken = "owner-a",
            leaseTtlMs = 60_000,
        )
        service.transition(
            scope,
            namespaceId = NAMESPACE_ID,
            workflowId = WORKFLOW_ID,
            stepId = STEP_ID,
            attemptId = "attempt-projection",
            ownerToken = "owner-a",
            target = AgentAttemptStatus.STARTING,
        )
        service.transition(
            scope,
            namespaceId = NAMESPACE_ID,
            workflowId = WORKFLOW_ID,
            stepId = STEP_ID,
            attemptId = "attempt-projection",
            ownerToken = "owner-a",
            target = AgentAttemptStatus.RUNNING,
        )
        service.finalize(
            scope,
            namespaceId = NAMESPACE_ID,
            workflowId = WORKFLOW_ID,
            stepId = STEP_ID,
            attemptId = "attempt-projection",
            ownerToken = "owner-a",
            target = AgentAttemptStatus.INDETERMINATE,
            failureCode = "AGENT_INDETERMINATE",
        )

        // The exact read path of WorkflowController.listAttempts: persistence only.
        val projections = service.findByWorkflow(scope, NAMESPACE_ID, WORKFLOW_ID).map { it.toDto() }

        val dto = projections.single { it.attemptId == "attempt-projection" }
        assertThat(dto.status).isEqualTo("indeterminate")
        assertThat(dto.failureCode).isEqualTo("AGENT_INDETERMINATE")
        assertThat(dto.completedAt).isNotNull
        assertThat(dto.agentName).isEqualTo("builder")
    }

    private fun attempt(attemptId: String): DurableAgentAttempt = DurableAgentAttempt(
        attemptId = attemptId,
        caseId = "case-1",
        namespaceId = NAMESPACE_ID,
        workflowId = WORKFLOW_ID,
        stepId = STEP_ID,
        attemptNumber = 1,
        agentName = "builder",
    )

    companion object {
        private const val NAMESPACE_ID = "ns-projection"
        private const val WORKFLOW_ID = "wf-projection"
        private const val STEP_ID = "step-projection"
    }
}
