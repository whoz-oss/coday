package io.whozoss.factory.capability

import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.whozoss.factory.agentattempt.domain.AgentStepResultCapabilityIdentity
import io.whozoss.factory.agentattempt.domain.IssuedCapability
import io.whozoss.factory.agentattempt.persistence.AgentStepAttemptRepository
import io.whozoss.factory.agentattempt.service.AgentStepResultService
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.workflow.domain.ResponsibilityKind
import io.whozoss.factory.workflow.domain.WorkflowStepDefinition
import io.whozoss.factory.workflow.domain.WorkflowStepResponsibility
import io.whozoss.factory.workflow.persistence.HumanInteractionRepository
import io.whozoss.factory.workflow.persistence.WorkflowEvidenceRepository
import io.whozoss.factory.workflow.persistence.WorkflowRepository
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Pure unit tests (no Spring, no database) of the result-capability generation
 * performed by [CapabilityExecutionService] at agent step start.
 *
 * The attempt/evidence/repository boundary is mocked; the seam under test is
 * that a single-use capability is issued for the fresh attempt and that the
 * attempt id, case id and clear token are forwarded to the turn.
 */
class CapabilityExecutionCapabilityIssuanceTest {

    private val scope = TenantScope("org-1", "ws-1")
    private val namespaceId = "ns-1"
    private val workflowId = "wf-1"
    private val repoRoot: Path = Path.of("/tmp")

    private val attempts = mockk<AgentStepAttemptRepository>(relaxed = true)
    private val evidence = mockk<WorkflowEvidenceRepository>(relaxed = true)
    private val interactions = mockk<HumanInteractionRepository>(relaxed = true)
    private val workflows = mockk<WorkflowRepository>(relaxed = true)
    private val issuer = mockk<AgentStepResultService>()

    private val agentStep = WorkflowStepDefinition(
        id = "step-1",
        name = "Step step-1",
        responsibility = WorkflowStepResponsibility(ResponsibilityKind.AGENT, "architect"),
        dependsOn = emptyList(),
    )

    @Test
    fun `an agent step issues a capability and forwards the attempt facts to the turn`() {
        clearMocks(issuer)
        val identity = slot<AgentStepResultCapabilityIdentity>()
        every { issuer.issue(any(), capture(identity), any(), any()) } returns
            IssuedCapability(token = "clear-token", expiresAt = "2099-01-01T00:00:00Z")
        val turns = mutableListOf<AgentTurnRequest>()
        val service = service(turns)

        val execution = service.resolveAndRecord(scope, namespaceId, workflowId, agentStep, repoRoot)

        assertThat(execution.outcome).isInstanceOf(CapabilityOutcome.AgentCompleted::class.java)
        val issued = identity.captured
        assertThat(issued.attemptId).isEqualTo(execution.attemptId)
        assertThat(issued.workflowId).isEqualTo(workflowId)
        assertThat(issued.stepId).isEqualTo("step-1")
        assertThat(issued.namespaceId).isEqualTo(namespaceId)
        assertThat(issued.agentName).isEqualTo("architect")
        assertThat(issued.caseId).isNotBlank()
        assertThat(issued.briefHash).startsWith("sha256:")

        val turn = turns.single()
        assertThat(turn.attemptId).isEqualTo(execution.attemptId)
        assertThat(turn.capabilityToken).isEqualTo("clear-token")
        assertThat(turn.caseId).isEqualTo(issued.caseId)
        verify(exactly = 1) { issuer.issue(any(), any(), any(), any()) }
    }

    @Test
    fun `a missing issuer does not fail the step and passes no token`() {
        val turns = mutableListOf<AgentTurnRequest>()
        val service = CapabilityExecutionService(
            CapabilityResolver(
                object : AgentTurnCapability {
                    override fun executeAgentTurn(request: AgentTurnRequest): AgentTurnResult {
                        turns += request
                        return AgentTurnResult.Completed("PASS")
                    }
                },
            ),
            workflows,
            evidence,
            interactions,
            attempts,
            agentStepResultService = null,
        )

        val execution = service.resolveAndRecord(scope, namespaceId, workflowId, agentStep, repoRoot)

        assertThat(execution.outcome).isInstanceOf(CapabilityOutcome.AgentCompleted::class.java)
        assertThat(turns.single().attemptId).isNotBlank()
        assertThat(turns.single().capabilityToken).isNull()
        assertThat(turns.single().caseId).isNotBlank()
    }

    private fun service(turns: MutableList<AgentTurnRequest>): CapabilityExecutionService =
        CapabilityExecutionService(
            CapabilityResolver(
                object : AgentTurnCapability {
                    override fun executeAgentTurn(request: AgentTurnRequest): AgentTurnResult {
                        turns += request
                        return AgentTurnResult.Completed("PASS")
                    }
                },
            ),
            workflows,
            evidence,
            interactions,
            attempts,
            agentStepResultService = issuer,
        )
}
