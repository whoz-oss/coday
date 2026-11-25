package io.whozoss.factory.workflow

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.factory.agentattempt.domain.DurableAgentAttempt
import io.whozoss.factory.agentattempt.service.DurableAgentAttemptService
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.proxy.AgentOsProxyClient
import io.whozoss.factory.proxy.CumulativeUsageDto
import io.whozoss.factory.proxy.RunCostDto
import io.whozoss.factory.workflow.domain.WorkflowInstanceRecord
import io.whozoss.factory.workflow.persistence.HumanInteractionRepository
import io.whozoss.factory.workflow.persistence.WorkflowEvidenceRepository
import io.whozoss.factory.workflow.persistence.WorkflowRepository
import io.whozoss.factory.workflow.service.WorkflowService
import io.whozoss.factory.workflow.sse.WorkflowSseHub
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WorkflowRealCostMetricsTest {
    private val scope = TenantScope("org", "ws")
    private val repository = mockk<WorkflowRepository>()
    private val evidence = mockk<WorkflowEvidenceRepository>()
    private val interactions = mockk<HumanInteractionRepository>()
    private val attempts = mockk<DurableAgentAttemptService>()
    private val proxy = mockk<AgentOsProxyClient>()
    private val service = WorkflowService(repository, evidence, interactions, mockk<WorkflowSseHub>(relaxed = true),
        agentOsProxyClient = proxy, durableAgentAttemptService = attempts)

    private fun prepare(usage: CumulativeUsageDto?, instance: Map<String, Any?> = mapOf("controllerExecution" to mapOf("caseId" to "parent"))) {
        every { repository.findInstance(scope, "ns", "wf") } returns WorkflowInstanceRecord(
            namespaceId = "ns", workflowId = "wf", revision = 1, status = "active", creationCommandHash = null,
            instance = instance, projection = emptyMap(),
        )
        every { repository.listTransitionTimestamps(scope, "ns", "wf") } returns emptyList()
        every { evidence.list(scope, "ns", "wf", null) } returns emptyList()
        every { interactions.list(scope, "ns", "wf", false) } returns emptyList()
        val first = DurableAgentAttempt("one", "child", "ns", "wf", "step", 1, "Dev")
        every { attempts.findByWorkflow(scope, "ns", "wf") } returns listOf(first, first.copy(attemptId = "retry", attemptNumber = 2))
        every { proxy.getRunCost(any(), "reader") } answers {
            RunCostDto(firstArg(), 999.0, 99, 10.0, true, true, 0)
        }
        every { proxy.getCumulativeUsage(listOf("child"), "reader") } returns usage
    }

    @Test
    fun `usage union is the workflow-owned attempt cases and never the shared controller case`() {
        // The controller case is a shared Run root here: counting its tree would add every sibling fragment.
        prepare(CumulativeUsageDto(12.5, 2, 100, 60, 20, 15, 5))
        service.metrics(scope, "ns", "wf", "self", "reader")
        verify(exactly = 1) { proxy.getCumulativeUsage(listOf("child"), "reader") }
        verify(exactly = 0) { proxy.getCumulativeUsage(match { "parent" in it }, any()) }
    }

    @Test
    fun `a parent Run fragment without attempts reports unavailable instead of its shared root case`() {
        prepare(null, mapOf("runId" to "run-1", "controllerExecution" to mapOf("caseId" to "shared-root")))
        every { attempts.findByWorkflow(scope, "ns", "wf") } returns emptyList()
        val cost = service.metrics(scope, "ns", "wf", "self", "reader")["realCost"] as Map<*, *>
        assertThat(cost["totalTokens"]).isNull()
        verify(exactly = 0) { proxy.getCumulativeUsage(any(), any()) }
    }

    @Test
    fun `a standalone workflow without attempts falls back to its own controller case`() {
        prepare(CumulativeUsageDto(1.0, 0, 10, 6, 4, 0, 0))
        every { attempts.findByWorkflow(scope, "ns", "wf") } returns emptyList()
        every { proxy.getCumulativeUsage(listOf("parent"), "reader") } returns CumulativeUsageDto(1.0, 0, 10, 6, 4, 0, 0)
        val cost = service.metrics(scope, "ns", "wf", "self", "reader")["realCost"] as Map<*, *>
        assertThat(cost["totalTokens"]).isEqualTo(10L)
    }

    @Test
    fun `cost and every token counter use one lifetime union including retries and never budget sums`() {
        prepare(CumulativeUsageDto(12.5, 2, 100, 60, 20, 15, 5))
        val cost = service.metrics(scope, "ns", "wf", "self", "reader")["realCost"] as Map<*, *>
        assertThat(cost["cost"]).isEqualTo(12.5)
        assertThat(cost["unknownCostCount"]).isEqualTo(2L)
        assertThat(cost["totalTokens"]).isEqualTo(100L)
        assertThat(cost["inputTokens"]).isEqualTo(60L)
        assertThat(cost["outputTokens"]).isEqualTo(20L)
        assertThat(cost["cacheReadTokens"]).isEqualTo(15L)
        assertThat(cost["cacheWriteTokens"]).isEqualTo(5L)
        assertThat(cost["paused"]).isEqualTo(true)
        verify(exactly = 1) { proxy.getCumulativeUsage(listOf("child"), "reader") }
    }

    @Test
    fun `missing cumulative endpoint never falls back to budget-window cost or fake zero tokens`() {
        prepare(null)
        val cost = service.metrics(scope, "ns", "wf", "self", "reader")["realCost"] as Map<*, *>
        for (field in listOf("cost", "unknownCostCount", "totalTokens", "inputTokens", "outputTokens", "cacheReadTokens", "cacheWriteTokens")) {
            assertThat(cost[field]).describedAs(field).isNull()
        }
    }
}
