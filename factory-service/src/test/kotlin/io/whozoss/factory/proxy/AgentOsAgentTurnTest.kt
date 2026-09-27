package io.whozoss.factory.proxy

import io.whozoss.factory.capability.AgentOsAgentTurnCapability
import io.whozoss.factory.capability.AgentTurnRequest
import io.whozoss.factory.capability.AgentTurnResult
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient

/**
 * Pure unit tests of the W8.3 agent-turn HTTP transport, against a mock AgentOS
 * server (never a real AgentOS) and of the capability mapping.
 */
class AgentOsAgentTurnTest {

    private val baseUrl = "http://agentos.test"
    private val eventsUrl = "$baseUrl/api/case-events/by-parentId/case-1"

    private fun build(
        pollIntervalMs: Long = 0L,
        startTimeoutMs: Long = 30_000L,
        workTimeoutMs: Long = 600_000L,
        now: () -> Long = { 0L },
    ): Pair<HttpAgentOsProxyClient, MockRestServiceServer> {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val client = HttpAgentOsProxyClient(
            builder,
            baseUrl,
            pollIntervalMs = pollIntervalMs,
            startTimeoutMs = startTimeoutMs,
            workTimeoutMs = workTimeoutMs,
            sleep = {},
            now = now,
        )
        return client to server
    }

    private fun expectCaseLifecycle(server: MockRestServiceServer, vararg eventBodies: String) {
        server.expect(requestTo("$baseUrl/api/cases"))
            .andRespond(withSuccess("""{"id":"case-1","namespaceId":"ns-1"}""", MediaType.APPLICATION_JSON))
        server.expect(requestTo(eventsUrl)).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON))
        server.expect(requestTo("$baseUrl/api/cases/case-1/messages"))
            .andRespond(withSuccess("", MediaType.APPLICATION_JSON))
        for (body in eventBodies) {
            server.expect(requestTo(eventsUrl)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON))
        }
    }

    @Test
    fun `a successful turn reaches IDLE and returns the last agent message`() {
        val (client, server) = build()
        val running = """[{"id":"e1","type":"CaseStatusEvent","status":"RUNNING"}]"""
        val idle = """
            [{"id":"e1","type":"CaseStatusEvent","status":"RUNNING"},
             {"id":"e2","type":"CaseStatusEvent","status":"IDLE"},
             {"id":"e3","type":"MessageEvent","actor":{"role":"AGENT"},"content":[{"content":"all good"}]}]
        """.trimIndent()
        expectCaseLifecycle(server, running, idle)

        val result = client.executeAgentTurn("ns-1", "architect", "step-1", "wf-1", brief = "do it")

        assertThat(result).isInstanceOf(AgentTurnExecutionResult.Completed::class.java)
        assertThat((result as AgentTurnExecutionResult.Completed).summary).isEqualTo("all good")
        assertThat(result.facts["caseStatus"]).isEqualTo("IDLE")
        server.verify()
    }

    @Test
    fun `an IDLE case waiting for an answer is a failure`() {
        val (client, server) = build()
        val running = """[{"id":"e1","type":"CaseStatusEvent","status":"RUNNING"}]"""
        val idleQuestion = """
            [{"id":"e1","type":"CaseStatusEvent","status":"RUNNING"},
             {"id":"e2","type":"CaseStatusEvent","status":"IDLE"},
             {"id":"q1","type":"QuestionEvent","question":"Which branch?"}]
        """.trimIndent()
        expectCaseLifecycle(server, running, idleQuestion)

        val result = client.executeAgentTurn("ns-1", "architect", "step-1", "wf-1")

        assertThat(result).isInstanceOf(AgentTurnExecutionResult.Failed::class.java)
        assertThat((result as AgentTurnExecutionResult.Failed).code).isEqualTo("AGENT_TURN_PENDING_QUESTION")
    }

    @Test
    fun `a case reaching ERROR is a failure`() {
        val (client, server) = build()
        val running = """[{"id":"e1","type":"CaseStatusEvent","status":"RUNNING"}]"""
        val error = """
            [{"id":"e1","type":"CaseStatusEvent","status":"RUNNING"},
             {"id":"e2","type":"CaseStatusEvent","status":"ERROR"}]
        """.trimIndent()
        expectCaseLifecycle(server, running, error)

        val result = client.executeAgentTurn("ns-1", "architect", "step-1", "wf-1")

        assertThat(result).isInstanceOf(AgentTurnExecutionResult.Failed::class.java)
        assertThat((result as AgentTurnExecutionResult.Failed).code).isEqualTo("AGENT_CASE_ERROR")
    }

    @Test
    fun `a case that never reaches RUNNING times out`() {
        val clock = object {
            var value = 0L
            fun now(): Long {
                val current = value
                value += 6_000L
                return current
            }
        }
        val (client, server) = build(startTimeoutMs = 5_000L, now = clock::now)
        expectCaseLifecycle(server, "[]")
        server.expect(requestTo("$baseUrl/api/cases/case-1/kill"))
            .andRespond(withSuccess("", MediaType.APPLICATION_JSON))

        val result = client.executeAgentTurn("ns-1", "architect", "step-1", "wf-1")

        assertThat(result).isInstanceOf(AgentTurnExecutionResult.Failed::class.java)
        assertThat((result as AgentTurnExecutionResult.Failed).code).isEqualTo("AGENT_TURN_START_TIMEOUT")
    }

    @Test
    fun `an unreachable AgentOS is an explicit failure`() {
        val client = HttpAgentOsProxyClient(RestClient.builder(), "http://127.0.0.1:1", sleep = {})

        val result = client.executeAgentTurn("ns-1", "architect", "step-1", "wf-1")

        assertThat(result).isInstanceOf(AgentTurnExecutionResult.Failed::class.java)
        assertThat((result as AgentTurnExecutionResult.Failed).code).isEqualTo("AGENTOS_UNAVAILABLE")
    }

    @Test
    fun `the capability maps a completed turn to a PASS result`() {
        val capability = AgentOsAgentTurnCapability(
            fakeClient(AgentTurnExecutionResult.Completed("done", mapOf("caseStatus" to "IDLE"))),
        )

        val result = capability.executeAgentTurn(
            AgentTurnRequest("step-1", "architect", Path.of("/tmp"), namespaceId = "ns-1", workflowId = "wf-1"),
        )

        assertThat(result).isInstanceOf(AgentTurnResult.Completed::class.java)
        assertThat((result as AgentTurnResult.Completed).status).isEqualTo("PASS")
        assertThat(result.facts["caseStatus"]).isEqualTo("IDLE")
    }

    @Test
    fun `the capability maps a failed turn to an explicit failure`() {
        val capability = AgentOsAgentTurnCapability(
            fakeClient(AgentTurnExecutionResult.Failed("AGENT_CASE_ERROR", "boom")),
        )

        val result = capability.executeAgentTurn(
            AgentTurnRequest("step-1", "architect", Path.of("/tmp"), namespaceId = "ns-1", workflowId = "wf-1"),
        )

        assertThat(result).isInstanceOf(AgentTurnResult.Failed::class.java)
        assertThat((result as AgentTurnResult.Failed).code).isEqualTo("AGENT_CASE_ERROR")
    }

    @Test
    fun `the capability refuses an agent turn without a namespace`() {
        val capability = AgentOsAgentTurnCapability(fakeClient(AgentTurnExecutionResult.Completed("done")))

        val result = capability.executeAgentTurn(AgentTurnRequest("step-1", "architect", Path.of("/tmp")))

        assertThat((result as AgentTurnResult.Failed).code).isEqualTo("AGENTOS_NAMESPACE_REQUIRED")
    }

    private fun fakeClient(result: AgentTurnExecutionResult): AgentOsProxyClient = object : AgentOsProxyClient {
        override fun fetchAgents(namespaceId: String, externalUserId: String?): Any? = error("unused")
        override fun fetchNamespace(namespaceId: String, externalUserId: String?): Map<String, Any?>? = error("unused")
        override fun fetchCaseEvents(caseId: String, externalUserId: String?): Any? = error("unused")
        override fun resolveRepoRoot(namespaceId: String, externalUserId: String?): String? = error("unused")
        override fun resolveRunStoreRoot(namespaceId: String, externalUserId: String?): String? = error("unused")
        override fun executeAgentTurn(
            namespaceId: String,
            persona: String,
            stepId: String,
            workflowId: String,
            brief: String?,
            externalUserId: String?,
        ): AgentTurnExecutionResult = result
    }
}
