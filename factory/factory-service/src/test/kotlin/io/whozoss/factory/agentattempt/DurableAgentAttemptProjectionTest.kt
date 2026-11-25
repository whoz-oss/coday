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

    @Autowired
    private lateinit var modelObserver: io.whozoss.factory.adapter.agentos.AttemptModelObserver

    @Test
    fun `actual model survives reload and recovery and is not inherited by a same-case successor`() {
        val first = service.register(scope, attempt("model-first"))
        modelObserver.captureBaseline(scope, NAMESPACE_ID, first.caseId, first.attemptId,
            io.whozoss.factory.adapter.agentos.HighWaterMark(null, null))
        val event = io.whozoss.factory.adapter.agentos.CaseEventView(
            "event-model", "AgentRunningEvent", first.caseId, "2026-01-01T00:00:00Z",
            mapOf("agentName" to "builder", "llmProvider" to "provider", "llmModel" to "actual-model"),
        )
        modelObserver.observe(scope, NAMESPACE_ID, first.caseId, first.attemptId, listOf(event))
        modelObserver.observe(scope, NAMESPACE_ID, first.caseId, first.attemptId, listOf(event.copy(raw = event.raw + ("llmModel" to "changed"))))
        val reloaded = service.find(scope, NAMESPACE_ID, WORKFLOW_ID, STEP_ID, first.attemptId)!!
        assertThat(reloaded.toDto().resolvedModel).isEqualTo(
            io.whozoss.factory.agentattempt.domain.ResolvedModel("provider", "actual-model"),
        )
        val successor = service.register(scope, attempt("model-successor").copy(attemptNumber = 2))
        assertThat(successor.toDto().resolvedModel).isNull()
        // Legacy unknown baseline must not adopt an old event from the reused case.
        modelObserver.observe(scope, NAMESPACE_ID, successor.caseId, successor.attemptId, listOf(event))
        assertThat(service.find(scope, NAMESPACE_ID, WORKFLOW_ID, STEP_ID, successor.attemptId)!!.resolvedModel).isNull()
    }

    @Test
    fun `the same case and attempt ids in another tenant are neither read nor written`() {
        val mine = service.register(scope, attempt("model-tenant"))
        val otherScope = io.whozoss.factory.persistence.TenantScope(scope.organizationId, "other-workstream-model")
        val theirs = service.register(otherScope, attempt("model-tenant"))
        val baseline = io.whozoss.factory.adapter.agentos.HighWaterMark(null, null)
        // Both tenants hold an attempt with the identical (caseId, attemptId): the scoped lookup must stay unique.
        modelObserver.captureBaseline(scope, NAMESPACE_ID, mine.caseId, mine.attemptId, baseline)
        assertThat(modelObserver.baseline(scope, NAMESPACE_ID, mine.caseId, mine.attemptId)).isNotNull()
        assertThat(modelObserver.baseline(otherScope, NAMESPACE_ID, theirs.caseId, theirs.attemptId)).isNull()
        val event = io.whozoss.factory.adapter.agentos.CaseEventView(
            "event-tenant", "AgentRunningEvent", mine.caseId, "2026-01-01T00:00:00Z",
            mapOf("agentName" to "builder", "llmProvider" to "provider", "llmModel" to "mine-only"),
        )
        modelObserver.observe(scope, NAMESPACE_ID, mine.caseId, mine.attemptId, listOf(event))
        assertThat(service.find(scope, NAMESPACE_ID, WORKFLOW_ID, STEP_ID, mine.attemptId)!!.resolvedModel)
            .isEqualTo(io.whozoss.factory.agentattempt.domain.ResolvedModel("provider", "mine-only"))
        assertThat(service.find(otherScope, NAMESPACE_ID, WORKFLOW_ID, STEP_ID, theirs.attemptId)!!.resolvedModel).isNull()
    }

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
