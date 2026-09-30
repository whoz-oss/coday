package io.whozoss.factory.adapter.agentos

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient

/**
 * Tests of [DefaultAgentOsExecutionAdapter]: REST operations against a
 * `MockRestServiceServer` (mirroring `AgentOsAgentTurnTest`), SSE observation
 * against the in-process [FakeAgentOsSseServer].
 */
class DefaultAgentOsExecutionAdapterTest {

    private val baseUrl = "http://agentos.test"
    private val eventsUrl = "$baseUrl/api/case-events/by-parentId/case-1"

    private lateinit var sseServer: FakeAgentOsSseServer

    @BeforeEach
    fun startSseServer() {
        sseServer = FakeAgentOsSseServer()
    }

    @AfterEach
    fun stopSseServer() {
        sseServer.close()
    }

    private fun build(
        sseClientFactory: (String) -> AgentOsSseClient = { AgentOsSseClient(it) },
    ): Pair<DefaultAgentOsExecutionAdapter, MockRestServiceServer> {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        return DefaultAgentOsExecutionAdapter(builder, baseUrl, sseClientFactory) to server
    }

    private fun sseFactory(): (String) -> AgentOsSseClient = {
        AgentOsSseClient(
            baseUrl = sseServer.baseUrl,
            backoffBaseMs = 5,
            backoffMaxMs = 20,
            maxReconnects = 3,
            stallTimeoutMs = 150,
        )
    }

    @Test
    fun `createOrRecoverExecution is idempotent by attemptId - the case is created only once`() {
        val (adapter, server) = build()
        server.expect(requestTo("$baseUrl/api/cases"))
            .andExpect(header("X-External-User-Id", "user-1"))
            .andExpect(header("X-Factory-Attempt-Id", "attempt-1"))
            .andExpect(header("X-Factory-Capability-Token", "token-1"))
            .andExpect(jsonPath("$.id").value("case-1"))
            .andExpect(jsonPath("$.attemptId").value("attempt-1"))
            .andExpect(jsonPath("$.capabilityToken").value("token-1"))
            .andRespond(withSuccess("""{"id":"case-1","namespaceId":"ns-1"}""", MediaType.APPLICATION_JSON))

        val created = adapter.createOrRecoverExecution(
            namespaceId = "ns-1",
            workflowId = "wf-1",
            stepId = "step-1",
            externalUserId = "user-1",
            attemptId = "attempt-1",
            capabilityToken = "token-1",
            caseId = "case-1",
        )
        val recovered = adapter.createOrRecoverExecution(
            namespaceId = "ns-1",
            workflowId = "wf-1",
            stepId = "step-1",
            externalUserId = "user-1",
            attemptId = "attempt-1",
            capabilityToken = "token-1",
            caseId = "case-1",
        )

        assertThat(created).isEqualTo(CaseHandle("case-1", "ns-1", recovered = false))
        assertThat(recovered).isEqualTo(CaseHandle("case-1", "ns-1", recovered = true))
        server.verify() // a single POST /api/cases — the second call recovered
    }

    @Test
    fun `a different attemptId drives a new case creation`() {
        val (adapter, server) = build()
        repeat(2) {
            server.expect(requestTo("$baseUrl/api/cases"))
                .andRespond(withSuccess("""{"id":"case-1","namespaceId":"ns-1"}""", MediaType.APPLICATION_JSON))
        }

        adapter.createOrRecoverExecution("ns-1", "wf-1", "step-1", null, "attempt-1", null, "case-1")
        adapter.createOrRecoverExecution("ns-1", "wf-1", "step-1", null, "attempt-2", null, "case-1")

        server.verify()
    }

    @Test
    fun `startTurn posts the persona brief with the factory headers`() {
        val (adapter, server) = build()
        server.expect(requestTo(eventsUrl)).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON))
        server.expect(requestTo("$baseUrl/api/cases/case-1/messages"))
            .andExpect(header("X-External-User-Id", "user-1"))
            .andExpect(header("X-Factory-Attempt-Id", "attempt-1"))
            .andExpect(jsonPath("$.content").value("@architect do the thing"))
            .andRespond(withSuccess("", MediaType.APPLICATION_JSON))

        adapter.startTurn("case-1", "architect", "do the thing", "user-1", "attempt-1", null)

        server.verify()
    }

    @Test
    fun `startTurn refuses a busy case`() {
        val (adapter, server) = build()
        server.expect(requestTo(eventsUrl)).andRespond(
            withSuccess(
                """[{"id":"e1","type":"CaseStatusEvent","status":"RUNNING","caseId":"case-1"}]""",
                MediaType.APPLICATION_JSON,
            ),
        )

        assertThatThrownBy {
            adapter.startTurn("case-1", "architect", "do the thing", null, "attempt-1", null)
        }.isInstanceOf(AgentOsCaseBusyException::class.java)

        server.verify()
    }

    @Test
    fun `reconcile derives WaitingHuman from an IDLE case with an unanswered question`() {
        val (adapter, server) = build()
        server.expect(requestTo(eventsUrl)).andRespond(
            withSuccess(
                """[
                  {"id":"e1","type":"CaseStatusEvent","status":"RUNNING","caseId":"case-1","timestamp":"2026-01-01T00:00:00Z"},
                  {"id":"q1","type":"QuestionEvent","question":"Which branch?","caseId":"case-1","timestamp":"2026-01-01T00:00:01Z"},
                  {"id":"e2","type":"CaseStatusEvent","status":"IDLE","caseId":"case-1","timestamp":"2026-01-01T00:00:02Z"}
                ]""",
                MediaType.APPLICATION_JSON,
            ),
        )

        val verdict = adapter.reconcile("case-1")

        assertThat(verdict).isInstanceOf(AgentOsExecutionVerdict.WaitingHuman::class.java)
        assertThat((verdict as AgentOsExecutionVerdict.WaitingHuman).questionRef).isEqualTo("q1")
        server.verify()
    }

    @Test
    fun `reconcile of a case that is still RUNNING is Indeterminate - never Succeeded`() {
        val (adapter, server) = build()
        server.expect(requestTo(eventsUrl)).andRespond(
            withSuccess(
                """[{"id":"e1","type":"CaseStatusEvent","status":"RUNNING","caseId":"case-1"}]""",
                MediaType.APPLICATION_JSON,
            ),
        )

        val verdict = adapter.reconcile("case-1")

        assertThat(verdict).isInstanceOf(AgentOsExecutionVerdict.Indeterminate::class.java)
        assertThat((verdict as AgentOsExecutionVerdict.Indeterminate).reason).isEqualTo(VerdictDeriver.NOT_QUIESCENT)
        assertThat(verdict.evidence["caseStatus"]).isEqualTo("RUNNING")
        server.verify()
    }

    @Test
    fun `a caller-initiated interrupt maps a terminal KILLED to Interrupted`() {
        val (adapter, server) = build()
        server.expect(requestTo("$baseUrl/api/cases/case-1/kill"))
            .andRespond(withSuccess("", MediaType.APPLICATION_JSON))
        server.expect(requestTo(eventsUrl)).andRespond(
            withSuccess(
                """[
                  {"id":"e1","type":"CaseStatusEvent","status":"RUNNING","caseId":"case-1","timestamp":"2026-01-01T00:00:00Z"},
                  {"id":"e2","type":"CaseStatusEvent","status":"KILLED","caseId":"case-1","timestamp":"2026-01-01T00:00:01Z"}
                ]""",
                MediaType.APPLICATION_JSON,
            ),
        )

        adapter.interrupt("case-1", "budget exceeded")
        val verdict = adapter.reconcile("case-1")

        assertThat(verdict).isInstanceOf(AgentOsExecutionVerdict.Interrupted::class.java)
        assertThat((verdict as AgentOsExecutionVerdict.Interrupted).reason).isEqualTo("budget exceeded")
        server.verify()
    }

    @Test
    fun `a KILLED case without an interrupt intent is Failed and never Succeeded`() {
        val (adapter, server) = build()
        server.expect(requestTo(eventsUrl)).andRespond(
            withSuccess(
                """[{"id":"e1","type":"CaseStatusEvent","status":"KILLED","caseId":"case-1"}]""",
                MediaType.APPLICATION_JSON,
            ),
        )

        val verdict = adapter.reconcile("case-1")

        assertThat(verdict).isInstanceOf(AgentOsExecutionVerdict.Failed::class.java)
        assertThat((verdict as AgentOsExecutionVerdict.Failed).code).isEqualTo("AGENT_CASE_KILLED")
        server.verify()
    }

    @Test
    fun `kill is best-effort and never throws`() {
        val (adapter, server) = build()
        server.expect(requestTo("$baseUrl/api/cases/case-1/kill")).andRespond(withServerError())

        adapter.kill("case-1")

        server.verify()
    }

    @Test
    fun `observeTurn derives Succeeded from the SSE replay of a completed turn`() {
        val (adapter, server) = build(sseFactory())
        server.expect(requestTo("$baseUrl/api/cases"))
            .andRespond(withSuccess("""{"id":"case-1","namespaceId":"ns-1"}""", MediaType.APPLICATION_JSON))
        adapter.createOrRecoverExecution("ns-1", "wf-1", "step-1", "user-1", "attempt-1", null, "case-1")

        sseServer.enqueueEvents(
            FakeAgentOsSseServer.statusEvent("e1", "case-1", "RUNNING"),
            FakeAgentOsSseServer.agentMessageEvent("m1", "case-1", "turn output"),
            FakeAgentOsSseServer.statusEvent("e2", "case-1", "IDLE", "2026-01-01T00:00:02Z"),
            holdAfterMs = 1_000,
        )

        // the completed turn is decided from the stream alone: no further REST call
        val verdict = adapter.observeTurn("case-1", "attempt-1", timeoutMs = 5_000)

        assertThat(verdict).isInstanceOf(AgentOsExecutionVerdict.Succeeded::class.java)
        assertThat((verdict as AgentOsExecutionVerdict.Succeeded).outputs["summary"]).isEqualTo("turn output")
        server.verify()
    }

    @Test
    fun `observeTurn falls back to the REST reconciliation after a connection drop`() {
        val (adapter, server) = build(sseFactory())
        // first SSE connection drops immediately with no data
        sseServer.enqueue(FakeAgentOsSseServer.Script())
        // the reconnection triggers a REST catch-up that sees the finished turn
        server.expect(requestTo(eventsUrl)).andRespond(
            withSuccess(
                """[
                  {"id":"e1","type":"CaseStatusEvent","status":"RUNNING","caseId":"case-1","timestamp":"2026-01-01T00:00:00Z"},
                  {"id":"m1","type":"MessageEvent","caseId":"case-1","timestamp":"2026-01-01T00:00:01Z","actor":{"role":"AGENT"},"content":[{"content":"caught up"}]},
                  {"id":"e2","type":"CaseStatusEvent","status":"IDLE","caseId":"case-1","timestamp":"2026-01-01T00:00:02Z"}
                ]""",
                MediaType.APPLICATION_JSON,
            ),
        )

        val verdict = adapter.observeTurn("case-1", "attempt-x", timeoutMs = 5_000)

        assertThat(verdict).isInstanceOf(AgentOsExecutionVerdict.Succeeded::class.java)
        assertThat((verdict as AgentOsExecutionVerdict.Succeeded).outputs["summary"]).isEqualTo("caught up")
        server.verify()
    }
}
