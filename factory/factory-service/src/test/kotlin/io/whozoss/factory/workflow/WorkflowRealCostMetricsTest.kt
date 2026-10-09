package io.whozoss.factory.workflow

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.factory.agentattempt.domain.DurableAgentAttempt
import io.whozoss.factory.agentattempt.service.DurableAgentAttemptService
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.proxy.AgentOsProxyClient
import io.whozoss.factory.proxy.RunCostDto
import io.whozoss.factory.workflow.domain.WorkflowInstanceRecord
import io.whozoss.factory.workflow.persistence.HumanInteractionRepository
import io.whozoss.factory.workflow.persistence.WorkflowEvidenceRepository
import io.whozoss.factory.workflow.persistence.WorkflowRepository
import io.whozoss.factory.workflow.service.WorkflowService
import io.whozoss.factory.workflow.sse.WorkflowSseHub
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Unit tests of the additive `realCost` block exposed by
 * [WorkflowService.metrics]. The aggregation sums AgentOS
 * `GET /api/cases/{caseId}/run-cost` over the workflow's distinct case ids and
 * degrades to a zero block whenever AgentOS is disabled, unreachable or the
 * workflow produced no case at all — `metrics` must never fail because of it.
 */
class WorkflowRealCostMetricsTest {
    private val scope = TenantScope("org", "workstream")
    private val namespace = "00000000-0000-4000-8000-000000000001"
    private val workflowId = "workflow-1"

    private val repository = mockk<WorkflowRepository>()
    private val evidenceRepository = mockk<WorkflowEvidenceRepository>()
    private val interactionRepository = mockk<HumanInteractionRepository>()
    private val attempts = mockk<DurableAgentAttemptService>()
    private val proxy = mockk<AgentOsProxyClient>()

    private val service = WorkflowService(
        repository,
        evidenceRepository,
        interactionRepository,
        mockk<WorkflowSseHub>(relaxed = true),
        agentOsProxyClient = proxy,
        durableAgentAttemptService = attempts,
    )

    /** Stubs the baseline reads `metrics` performs before the cost aggregation. */
    private fun stubWorkflow(controllerCaseId: String? = null) {
        every { repository.findInstance(scope, namespace, workflowId) } returns WorkflowInstanceRecord(
            namespaceId = namespace,
            workflowId = workflowId,
            revision = 1,
            status = "active",
            creationCommandHash = null,
            instance = controllerCaseId
                ?.let { mapOf("controllerExecution" to mapOf("caseId" to it)) }
                ?: emptyMap(),
            projection = emptyMap(),
        )
        every { repository.listTransitionTimestamps(scope, namespace, workflowId) } returns emptyList()
        every { evidenceRepository.list(scope, namespace, workflowId, null) } returns emptyList()
        every { interactionRepository.list(scope, namespace, workflowId, false) } returns emptyList()
    }

    private fun attempt(caseId: String, stepId: String = "step-$caseId") = DurableAgentAttempt(
        attemptId = "$workflowId#$stepId",
        caseId = caseId,
        namespaceId = namespace,
        workflowId = workflowId,
        stepId = stepId,
        attemptNumber = 1,
        agentName = "agent",
    )

    private fun runCost(
        caseId: String,
        cost: Double,
        unknownCostCount: Long = 0L,
        liveTokens: Long = 0L,
        paused: Boolean = false,
        active: Boolean = false,
        runCostThreshold: Double? = null,
    ) = RunCostDto(caseId, cost, unknownCostCount, runCostThreshold, paused, active, liveTokens)

    @Suppress("UNCHECKED_CAST")
    private fun realCostOf(metrics: Map<String, Any?>): Map<String, Any?> = metrics["realCost"] as Map<String, Any?>

    @Test
    fun `realCost aggregates a single known root case and keeps the existing metrics keys`() {
        stubWorkflow(controllerCaseId = "case-1")
        every { attempts.findByWorkflow(scope, namespace, workflowId) } returns emptyList()
        every { proxy.getRunCost("case-1", null) } returns runCost(
            "case-1", cost = 12.5, liveTokens = 100, active = true, runCostThreshold = 50.0,
        )

        val metrics = service.metrics(scope, namespace, workflowId, "test")

        assertThat(metrics.keys).contains(
            "namespaceId", "workflowId", "scope", "observedAt",
            "timing", "retries", "evidenceCount", "interactionCount", "realCost",
        )
        val realCost = realCostOf(metrics)
        assertThat(realCost["cost"]).isEqualTo(12.5)
        assertThat(realCost["unknownCostCount"]).isEqualTo(0L)
        assertThat(realCost["liveTokens"]).isEqualTo(100L)
        assertThat(realCost["paused"]).isEqualTo(false)
        assertThat(realCost["active"]).isEqualTo(true)
        assertThat(realCost["runCostThreshold"]).isEqualTo(50.0)
    }

    @Test
    fun `unknown costs are summed verbatim across distinct cases and never folded into cost`() {
        stubWorkflow(controllerCaseId = "case-1")
        every { attempts.findByWorkflow(scope, namespace, workflowId) } returns listOf(attempt("case-2"))
        every { proxy.getRunCost("case-1", null) } returns runCost(
            "case-1", cost = 4.0, unknownCostCount = 3, liveTokens = 10, paused = true, runCostThreshold = 50.0,
        )
        every { proxy.getRunCost("case-2", null) } returns runCost(
            "case-2", cost = 2.0, unknownCostCount = 1, liveTokens = 20, active = true, runCostThreshold = 30.0,
        )

        val realCost = realCostOf(service.metrics(scope, namespace, workflowId, "test"))

        assertThat(realCost["cost"]).isEqualTo(6.0)
        assertThat(realCost["unknownCostCount"]).isEqualTo(4L)
        assertThat(realCost["liveTokens"]).isEqualTo(30L)
        assertThat(realCost["paused"]).isEqualTo(true)
        assertThat(realCost["active"]).isEqualTo(true)
        assertThat(realCost["runCostThreshold"]).isEqualTo(50.0)
    }

    @Test
    fun `an attempt case id equal to the controller case id is queried only once`() {
        stubWorkflow(controllerCaseId = "case-1")
        every { attempts.findByWorkflow(scope, namespace, workflowId) } returns listOf(
            attempt("case-1", "step-a"),
            attempt("case-1", "step-b"),
        )
        every { proxy.getRunCost("case-1", null) } returns runCost("case-1", cost = 7.0)

        val realCost = realCostOf(service.metrics(scope, namespace, workflowId, "test"))

        assertThat(realCost["cost"]).isEqualTo(7.0)
        verify(exactly = 1) { proxy.getRunCost(any(), any()) }
    }

    @Test
    fun `an unreachable AgentOS or unknown case degrades to the zero block without failing metrics`() {
        stubWorkflow(controllerCaseId = "case-1")
        every { attempts.findByWorkflow(scope, namespace, workflowId) } returns listOf(attempt("case-2"))
        every { proxy.getRunCost(any(), any()) } returns null

        val metrics = service.metrics(scope, namespace, workflowId, "test")

        assertThat(metrics["workflowId"]).isEqualTo(workflowId)
        val realCost = realCostOf(metrics)
        assertThat(realCost["cost"]).isEqualTo(0.0)
        assertThat(realCost["unknownCostCount"]).isEqualTo(0L)
        assertThat(realCost["liveTokens"]).isEqualTo(0L)
        assertThat(realCost["paused"]).isEqualTo(false)
        assertThat(realCost["active"]).isEqualTo(false)
        assertThat(realCost["runCostThreshold"]).isNull()
    }

    @Test
    fun `a workflow without any case degrades to the zero block and never calls AgentOS`() {
        stubWorkflow(controllerCaseId = null)
        every { attempts.findByWorkflow(scope, namespace, workflowId) } returns emptyList()

        val realCost = realCostOf(service.metrics(scope, namespace, workflowId, "test"))

        assertThat(realCost["cost"]).isEqualTo(0.0)
        assertThat(realCost["unknownCostCount"]).isEqualTo(0L)
        assertThat(realCost["liveTokens"]).isEqualTo(0L)
        assertThat(realCost["paused"]).isEqualTo(false)
        assertThat(realCost["active"]).isEqualTo(false)
        assertThat(realCost["runCostThreshold"]).isNull()
        verify(exactly = 0) { proxy.getRunCost(any(), any()) }
    }

    @Test
    fun `a disabled proxy degrades to the zero block`() {
        stubWorkflow(controllerCaseId = "case-1")
        val proxyLessService = WorkflowService(
            repository,
            evidenceRepository,
            interactionRepository,
            mockk<WorkflowSseHub>(relaxed = true),
            durableAgentAttemptService = attempts,
        )

        val realCost = realCostOf(proxyLessService.metrics(scope, namespace, workflowId, "test"))

        assertThat(realCost["cost"]).isEqualTo(0.0)
        assertThat(realCost["unknownCostCount"]).isEqualTo(0L)
        assertThat(realCost["runCostThreshold"]).isNull()
        verify(exactly = 0) { proxy.getRunCost(any(), any()) }
    }
}
