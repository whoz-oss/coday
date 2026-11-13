package io.whozoss.factory.capability

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.factory.adapter.agentos.AgentOsExecutionVerdict
import io.whozoss.factory.adapter.agentos.AgentOsObservationResult
import java.nio.file.Path
import io.whozoss.factory.adapter.agentos.AgentOsExecutionAdapter
import io.whozoss.factory.agentattempt.domain.AgentStepResultSubmitted
import io.whozoss.factory.agentattempt.domain.AgentStepResultStatus
import io.whozoss.factory.agentattempt.domain.AgentStepResultClaim
import io.whozoss.factory.agentattempt.service.AgentStepResultService
import io.whozoss.factory.sdk.spi.FactoryStepResultValidator
import io.whozoss.factory.workflow.domain.WorkflowStepDefinition
import io.whozoss.factory.workflow.domain.WorkflowStepResponsibility
import io.whozoss.factory.workflow.domain.ResponsibilityKind
import io.whozoss.factory.workflow.domain.WorkflowEvidenceItem
import io.whozoss.factory.agentattempt.domain.AgentAttemptStatus
import io.whozoss.factory.agentattempt.domain.DurableAgentAttempt
import io.whozoss.factory.agentattempt.service.DurableAgentAttemptService
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.workflow.domain.WorkflowInstanceRecord
import io.whozoss.factory.workflow.persistence.WorkflowEvidenceRepository
import io.whozoss.factory.workflow.persistence.WorkflowRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.SimpleTransactionStatus

class CapabilityExecutionRoutingRecoveryTest {

    @Test
    fun `recovery finalizes inline when the submission service is absent`() {
        assertRecoveryWithoutSubmissionService()
    }

    @Test
    fun `recovery commits a nonnull Unit when the submission service is absent`() {
        val manager = mockk<PlatformTransactionManager>(relaxed = true)
        val status = SimpleTransactionStatus()
        every { manager.getTransaction(any()) } returns status

        assertRecoveryWithoutSubmissionService(manager)

        verify(exactly = 1) {
            manager.getTransaction(match { it.propagationBehavior == TransactionDefinition.PROPAGATION_REQUIRES_NEW })
            manager.commit(status)
        }
        verify(exactly = 0) { manager.rollback(any()) }
    }

    @Test
    fun `ordinary workflow recovery uses the same validator evidence path`() {
        assertRecoveryWithoutSubmissionService(routing = false)
    }

    @Test
    fun `late accepted PASS replays research resolution without invoking a registered validator or a new turn`() {
        val scope = TenantScope("org", "ws")
        val workflows = mockk<WorkflowRepository>()
        val attempts = mockk<DurableAgentAttemptService>()
        val results = mockk<AgentStepResultService>()
        val evidence = mockk<WorkflowEvidenceRepository>(relaxed = true)
        val adapter = mockk<AgentOsExecutionAdapter>()
        val validator = mockk<FactoryStepResultValidator>()
        val step = WorkflowStepDefinition("work", "Work", WorkflowStepResponsibility(ResponsibilityKind.AGENT, "Dev"), emptyList())
        val attempt = DurableAgentAttempt("attempt", "case", "ns", "wf", "work", 1, "Dev",
            brief = "frozen", contextEnvelope = "{}", status = AgentAttemptStatus.INDETERMINATE, failureCode = "AGENT_NEEDS_RESEARCH")
        every { attempts.findLatestForStep(scope, "ns", "wf", "work") } returns attempt
        every { attempts.find(scope, "ns", "wf", "work", "attempt") } returns attempt
        every { workflows.findInstance(scope, "ns", "wf") } returns WorkflowInstanceRecord(
            "ns", "wf", 1, "active", "h", emptyMap(), mapOf("steps" to listOf(mapOf(
                "id" to "work", "name" to "Work", "responsibility" to step.responsibility.toJson(), "dependsOn" to emptyList<String>(),
            ))))
        every { results.acceptedResult(scope, "ns", "wf", "work", "attempt") } returns AgentStepResultSubmitted(
            "result-submitted", "result", "attempt", "wf", "work", "ns", "case", "Dev", "hash",
            AgentStepResultStatus.PASS, "resolved", emptyList(), AgentStepResultClaim(emptyList()), emptyList(), "2026-01-01T00:00:00Z", "hash")
        val recorded = mutableListOf<WorkflowEvidenceItem>()
        every { evidence.append(scope, "ns", "wf", capture(recorded)) } returns mockk(relaxed = true)
        every { evidence.list(scope, "ns", "wf", "work") } answers { recorded.toList() }
        val service = CapabilityExecutionService(CapabilityResolver(), workflows, evidence, mockk(), mockk(), attempts, adapter,
            agentStepResultService = results, registeredStepResultValidators = listOf(validator))

        val execution = service.resolveAndRecord(scope, "ns", "wf", step, Path.of("."))

        assertThat(execution.outcome).isInstanceOf(CapabilityOutcome.AgentCompleted::class.java)
        assertThat(recorded.single().outcome).isEqualTo("pass")
        assertThat(recorded.single().facts["validatorsAccepted"]).isEqualTo(true)
        verify(exactly = 0) { validator.supports(any(), any(), any()) }
        verify(exactly = 0) { validator.validate(any()) }
        verify(exactly = 0) { adapter.startTurn(any(), any(), any(), any(), any(), any()) }
    }

    private fun assertRecoveryWithoutSubmissionService(manager: PlatformTransactionManager? = null, routing: Boolean = true) {
        val scope = TenantScope("org", "ws")
        val workflows = mockk<WorkflowRepository>()
        val attempts = mockk<DurableAgentAttemptService>()
        val evidence = mockk<WorkflowEvidenceRepository>(relaxed = true)
        val attempt = DurableAgentAttempt(
            attemptId = "attempt-1",
            caseId = "case-1",
            namespaceId = "ns",
            workflowId = "wf",
            stepId = "decision",
            attemptNumber = 1,
            agentName = "architect",
            status = AgentAttemptStatus.RUNNING,
        )
        every {
            attempts.finalize(scope, "ns", "wf", "decision", "attempt-1", "owner",
                AgentAttemptStatus.SUCCEEDED, any(), any(), any(), any())
        } answers {
            attempt.copy(status = AgentAttemptStatus.SUCCEEDED, resultEvidenceId = arg<String?>(8))
        }
        val step = mutableMapOf<String, Any?>(
            "id" to "decision", "name" to "Choose a route",
            "responsibility" to mapOf("kind" to "agent", "name" to "architect"),
            "dependsOn" to emptyList<String>(),
        )
        if (routing) step["decision"] = mapOf(
            "mode" to "single", "routes" to listOf(
                mapOf("name" to "left", "target" to "left-step"),
                mapOf("name" to "right", "target" to "right-step"),
            ),
        )
        every { workflows.findInstance(scope, "ns", "wf") } returns WorkflowInstanceRecord(
            namespaceId = "ns",
            workflowId = "wf",
            revision = 1,
            status = "active",
            creationCommandHash = "recovery-test",
            instance = emptyMap(),
            projection = mapOf("steps" to listOf(step)),
        )
        val service = CapabilityExecutionService(
            resolver = CapabilityResolver(),
            workflowRepository = workflows,
            evidenceRepository = evidence,
            interactionRepository = mockk(relaxed = true),
            attemptRepository = mockk(relaxed = true),
            durableAgentAttemptService = attempts,
            agentOsExecutionAdapter = mockk(relaxed = true),
            transactionManager = manager,
        )

        val recovered = service.finalizeRecoveredAttempt(
            scope, attempt, "owner", AgentOsObservationResult(AgentOsExecutionVerdict.Succeeded(emptyMap())),
        )

        assertThat(recovered).isTrue()
        verify(exactly = 1) {
            evidence.append(scope, "ns", "wf", match {
                it.stepId == "decision" && it.kind == "agent-result" && it.outcome == "pass" &&
                    it.source?.get("agentId") == "architect" &&
                    it.facts.let { facts ->
                        facts["attemptId"] == "attempt-1" && facts["validatorsAccepted"] == true
                    }
            })
        }
        verify(exactly = 1) {
            attempts.finalize(scope, "ns", "wf", "decision", "attempt-1", "owner",
                AgentAttemptStatus.SUCCEEDED, null, any(), null, any())
        }
    }
}
