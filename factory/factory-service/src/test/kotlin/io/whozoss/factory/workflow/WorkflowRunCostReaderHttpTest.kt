package io.whozoss.factory.workflow

import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.proxy.AgentOsProxyClient
import io.whozoss.factory.proxy.RunCostDto
import io.whozoss.factory.workflow.domain.WorkflowInstanceRecord
import io.whozoss.factory.workflow.persistence.WorkflowRepository
import io.whozoss.factory.workstream.WorkstreamService
import io.whozoss.factory.workstream.web.CreateWorkstreamRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.test.context.TestPropertySource

/**
 * Real auth-mode HTTP boundaries and workflow reads; only the outbound AgentOS transport is mocked.
 * The trusted gateway header identifies a reader distinct from the creator and the local test user.
 * MembershipResolver supplies org-local-dev, matching the fixture scope; organization headers are not trusted.
 * The superclass DynamicPropertySource does not set security mode, so this override selects the real auth resolver
 * and disables the shared fixed-local-identity bean.
 */
@TestPropertySource(properties = ["factory.security.mode=auth"])
@Import(WorkflowRunCostReaderHttpTest.ReaderProxyConfiguration::class)
class WorkflowRunCostReaderHttpTest : Neo4jDomainIntegrationTest() {

    @TestConfiguration
    class ReaderProxyConfiguration {
        @Bean
        @Primary
        fun runCostReaderProxy(): AgentOsProxyClient = mockk()
    }

    @Autowired
    private lateinit var proxy: AgentOsProxyClient

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var repository: WorkflowRepository

    @Autowired
    private lateinit var workstreams: WorkstreamService

    private val namespace = "0d4bd471-df37-43d8-a8f7-c989f95e71d7"
    private val workflowId = "wf-reader"
    private val caseId = "case-reader"
    private val creator = "persisted-creator"
    private val reader = "authenticated-run-cost-reader"

    @BeforeEach
    fun prepareWorkflowCreatedByAnotherUser() {
        clearMocks(proxy)
        workstreams.create(scope, CreateWorkstreamRequest(
            slug = scope.workstreamId, name = "Reader fixture", status = "active", namespaceId = namespace,
        ))
        repository.insertInstance(scope, WorkflowInstanceRecord(
            namespaceId = namespace,
            workflowId = workflowId,
            revision = 1,
            status = "active",
            creationCommandHash = null,
            instance = mapOf(
                "controllerExecution" to mapOf("caseId" to caseId, "actorId" to creator),
                "controllerRequest" to mapOf("actorId" to creator),
            ),
            projection = mapOf("status" to "ready", "steps" to emptyList<Any>()),
        ))
        every { proxy.getRunCost(caseId, reader) } returns
            RunCostDto(caseId, 12.5, 2L, 10.0, true, true, 123L)
        every { proxy.getCumulativeUsage(listOf(caseId), reader) } returns
            io.whozoss.factory.proxy.CumulativeUsageDto(12.5, 2, 200, 100, 50, 30, 20, 123)
    }

    private fun get(path: String): Map<String, Any?> {
        val response = restTemplate.exchange(
            "$path?namespaceId=$namespace",
            HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { set("X-External-User-Id", reader) }),
            object : ParameterizedTypeReference<Map<String, Any?>>() {},
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        verify(exactly = 1) { proxy.getRunCost(caseId, reader) }
        verify(exactly = 1) { proxy.getRunCost(any(), any()) }
        verify(exactly = 0) { proxy.getRunCost(any(), TEST_USER_ID) }
        verify(exactly = 0) { proxy.getRunCost(any(), creator) }
        verify(exactly = 0) { proxy.getRunCost(any(), null) }
        @Suppress("UNCHECKED_CAST")
        return response.body!!["data"] as Map<String, Any?>
    }

    @Test
    fun `workflow metrics forwards the authenticated reader instead of the creator`() {
        val payload = get("/api/factory/workflows/$workflowId/metrics")

        val cost = payload["realCost"] as Map<*, *>
        assertThat(cost["cost"]).isEqualTo(12.5)
        assertThat(cost["unknownCostCount"]).isEqualTo(2)
        assertThat(cost["paused"]).isEqualTo(true)
        assertThat(cost.keys.map { it.toString() }).containsExactlyInAnyOrder(
            "cost", "unknownCostCount", "liveTokens", "paused", "active", "runCostThreshold",
            "totalTokens", "inputTokens", "outputTokens", "cacheReadTokens", "cacheWriteTokens",
        )
    }

    @Test
    fun `workflow actions forwards the authenticated reader and exposes cost controls`() {
        val payload = get("/api/factory/workflows/$workflowId/actions")

        val actions = (payload["allowedActions"] as List<*>).filterIsInstance<Map<*, *>>()
        assertThat(actions.map { it["type"] }).containsExactlyInAnyOrder("continue_cost", "stop_cost")
        assertThat(actions.map { it["caseId"] }).containsOnly(caseId)
        assertThat(payload["blockers"].toString()).contains("REAL_COST_PAUSED")
    }

    @Test
    fun `workstream get workflow forwards the authenticated reader and keeps controls information only`() {
        val payload = get("/api/factory/workstream-service/workflows/$workflowId")

        assertThat(payload["blockers"].toString()).contains("REAL_COST_PAUSED")
        assertThat(payload["allowedActions"] as List<*>).isEmpty()
    }

    @Test
    fun `workstream get blockers forwards the authenticated reader instead of the creator`() {
        val payload = get("/api/factory/workstream-service/workflows/$workflowId/blockers")

        val blockers = (payload["items"] as List<*>).filterIsInstance<Map<*, *>>()
        assertThat(blockers.map { it["code"] }).containsExactly("REAL_COST_PAUSED")
    }
}
