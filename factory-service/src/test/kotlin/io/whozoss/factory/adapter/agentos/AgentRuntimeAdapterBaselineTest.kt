package io.whozoss.factory.adapter.agentos

import java.io.IOException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.client.ExpectedCount
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient

/**
 * Acceptance tests of the explicit [AgentRuntimeAdapter] contract as
 * implemented by [DefaultAgentOsExecutionAdapter]:
 *
 *  - (a) the per-turn baseline: events of an old turn on a reused case never
 *    trigger the verdict of the new turn;
 *  - (b) active-case tracking: the adapter registers every case it creates in
 *    the [ActiveCaseRegistry], which the graceful shutdown interrupts/kills;
 *  - (c) an unreachable runtime is always [AgentOsExecutionVerdict.Indeterminate]
 *    ([VerdictDeriver.RUNTIME_UNREACHABLE] or an exhausted observation budget),
 *    never `Succeeded`;
 *  - (d) reconciliation after a Factory restart resumes the observation
 *    without ever concluding from silence.
 *
 * Note: `MockRestServiceServer` requires every expectation to be declared
 * before the first actual request, so each test stages its whole scripted
 * exchange up front.
 */
class AgentRuntimeAdapterBaselineTest {

    private val baseUrl = "http://agentos.test"
    private val eventsUrl = "$baseUrl/api/case-events/by-parentId/case-1"

    private fun build(
        registry: ActiveCaseRegistry = ActiveCaseRegistry(),
        ignoreExpectOrder: Boolean = false,
        sseClientFactory: (String) -> AgentOsSseClient = { AgentOsSseClient(it) },
    ): Pair<DefaultAgentOsExecutionAdapter, MockRestServiceServer> {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(ignoreExpectOrder).build()
        return DefaultAgentOsExecutionAdapter(
            builder,
            baseUrl,
            sseClientFactory = sseClientFactory,
            registry = registry,
        ) to server
    }

    private fun binding(attemptId: String) = TrustedCaseBinding(
        caseId = "case-1",
        namespaceId = "ns-1",
        attemptId = attemptId,
        runtimeId = "agentos-primary",
        externalUserId = "user-1",
    )

    @Test
    fun `baseline - an old turn's terminal events never close the new turn of a reused case`() {
        val turn1History = """[
          {"id":"e1","type":"CaseStatusEvent","status":"RUNNING","caseId":"case-1","timestamp":"2026-01-01T00:00:00Z"},
          {"id":"m1","type":"MessageEvent","caseId":"case-1","timestamp":"2026-01-01T00:00:01Z","actor":{"role":"AGENT"},"content":[{"content":"first output"}]},
          {"id":"e2","type":"CaseStatusEvent","status":"IDLE","caseId":"case-1","timestamp":"2026-01-01T00:00:02Z"}
        ]"""
        val turn2History = """[
          {"id":"e1","type":"CaseStatusEvent","status":"RUNNING","caseId":"case-1","timestamp":"2026-01-01T00:00:00Z"},
          {"id":"m1","type":"MessageEvent","caseId":"case-1","timestamp":"2026-01-01T00:00:01Z","actor":{"role":"AGENT"},"content":[{"content":"first output"}]},
          {"id":"e2","type":"CaseStatusEvent","status":"IDLE","caseId":"case-1","timestamp":"2026-01-01T00:00:02Z"},
          {"id":"e3","type":"CaseStatusEvent","status":"RUNNING","caseId":"case-1","timestamp":"2026-01-01T00:00:03Z"},
          {"id":"m2","type":"MessageEvent","caseId":"case-1","timestamp":"2026-01-01T00:00:04Z","actor":{"role":"AGENT"},"content":[{"content":"second output"}]},
          {"id":"e4","type":"CaseStatusEvent","status":"IDLE","caseId":"case-1","timestamp":"2026-01-01T00:00:05Z"}
        ]"""
        val (adapter, server) = build()
        // Scripted exchange, in request order:
        server.expect(requestTo("$baseUrl/api/cases"))
            .andRespond(withSuccess("""{"id":"case-1","namespaceId":"ns-1"}""", MediaType.APPLICATION_JSON))
        // turn 1: baseline capture on an empty history, then the turn is posted
        server.expect(requestTo(eventsUrl)).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON))
        server.expect(requestTo("$baseUrl/api/cases/case-1/messages"))
            .andRespond(withSuccess("", MediaType.APPLICATION_JSON))
        // turn 1 completes: the durable history ends on IDLE@t2 (free text:
        // never an authoritative success, but a quiescent turn)
        server.expect(requestTo(eventsUrl)).andRespond(withSuccess(turn1History, MediaType.APPLICATION_JSON))
        // turn 2 (reused case, new attemptId): baseline capture sees the old
        // IDLE@t2, then the turn is posted
        server.expect(requestTo(eventsUrl)).andRespond(withSuccess(turn1History, MediaType.APPLICATION_JSON))
        server.expect(requestTo("$baseUrl/api/cases/case-1/messages"))
            .andRespond(withSuccess("", MediaType.APPLICATION_JSON))
        // no new terminal event yet: the history still ends on the old IDLE
        server.expect(requestTo(eventsUrl)).andRespond(withSuccess(turn1History, MediaType.APPLICATION_JSON))
        // the new turn's own terminal event lands after the baseline
        server.expect(requestTo(eventsUrl)).andRespond(withSuccess(turn2History, MediaType.APPLICATION_JSON))

        adapter.createOrRecoverExecution(binding("attempt-1"), workflowId = "wf-1", stepId = "step-1")

        // --- turn 1 on a fresh case: empty baseline ---
        val turn1 = adapter.startTurn(binding("attempt-1"), persona = "architect", brief = "first turn")
        assertThat(turn1.baseline.isPositioned()).isFalse()

        val verdict1 = adapter.reconcile(turn1)
        assertThat(verdict1).isInstanceOf(AgentOsExecutionVerdict.Indeterminate::class.java)
        assertThat((verdict1 as AgentOsExecutionVerdict.Indeterminate).reason)
            .isEqualTo(VerdictDeriver.AGENT_NO_STRUCTURED_RESULT)

        // --- turn 2 reuses the same case: the baseline is captured on the old
        // IDLE BEFORE the turn is posted ---
        val turn2 = adapter.startTurn(binding("attempt-2"), persona = "architect", brief = "second turn")
        assertThat(turn2.baseline).isEqualTo(HighWaterMark("2026-01-01T00:00:02Z", "e2"))

        // The old IDLE is baseline-covered and must NOT close turn 2 — the
        // verdict is honestly "not quiescent", never a premature old-turn verdict.
        val premature = adapter.reconcile(turn2)
        assertThat(premature).isInstanceOf(AgentOsExecutionVerdict.Indeterminate::class.java)
        assertThat((premature as AgentOsExecutionVerdict.Indeterminate).reason).isEqualTo(VerdictDeriver.NOT_QUIESCENT)
        assertThat(premature).isNotInstanceOf(AgentOsExecutionVerdict.Succeeded::class.java)

        // The verdict of the new turn is derived once its own terminal event exists.
        val verdict2 = adapter.reconcile(turn2)
        assertThat(verdict2).isInstanceOf(AgentOsExecutionVerdict.Indeterminate::class.java)
        assertThat((verdict2 as AgentOsExecutionVerdict.Indeterminate).reason)
            .isEqualTo(VerdictDeriver.AGENT_NO_STRUCTURED_RESULT)
        assertThat(verdict2.evidence["summary"]).isEqualTo("second output")
        server.verify()
    }

    @Test
    fun `the adapter registers every created case in the ActiveCaseRegistry and tracks its lifecycle state`() {
        val registry = ActiveCaseRegistry()
        val (adapter, server) = build(registry)
        server.expect(requestTo("$baseUrl/api/cases"))
            .andRespond(withSuccess("""{"id":"case-1","namespaceId":"ns-1"}""", MediaType.APPLICATION_JSON))
        server.expect(requestTo(eventsUrl)).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON))
        server.expect(requestTo("$baseUrl/api/cases/case-1/messages"))
            .andRespond(withSuccess("", MediaType.APPLICATION_JSON))

        adapter.createOrRecoverExecution(binding("attempt-1"), workflowId = "wf-1", stepId = "step-1")

        assertThat(registry.activeCount()).isEqualTo(1)
        val entry = registry.snapshot().single()
        assertThat(entry.binding.caseId).isEqualTo("case-1")
        assertThat(entry.binding.attemptId).isEqualTo("attempt-1")
        assertThat(entry.binding.runtimeId).isEqualTo("agentos-primary")
        assertThat(entry.state).isEqualTo(ActiveCaseState.CREATED)

        adapter.startTurn(binding("attempt-1"), persona = "architect", brief = "go")

        assertThat(registry.snapshot().single().state).isEqualTo(ActiveCaseState.RUNNING)
        server.verify()
    }

    @Test
    fun `shutdownActiveCases through the adapter interrupts and kills every registered case best-effort`() {
        val registry = ActiveCaseRegistry()
        // The shutdown iteration order across cases is not significant.
        val (adapter, server) = build(registry, ignoreExpectOrder = true)
        server.expect(requestTo("$baseUrl/api/cases"))
            .andRespond(withSuccess("""{"id":"case-1","namespaceId":"ns-1"}""", MediaType.APPLICATION_JSON))
        server.expect(requestTo("$baseUrl/api/cases"))
            .andRespond(withSuccess("""{"id":"case-2","namespaceId":"ns-1"}""", MediaType.APPLICATION_JSON))
        // interrupt posts a kill, then the forced kill posts again — both
        // best-effort (case-2's runtime answers 500 and is still deregistered).
        server.expect(ExpectedCount.times(2), requestTo("$baseUrl/api/cases/case-1/kill"))
            .andRespond(withSuccess("", MediaType.APPLICATION_JSON))
        server.expect(ExpectedCount.times(2), requestTo("$baseUrl/api/cases/case-2/kill"))
            .andRespond(withServerError())

        adapter.createOrRecoverExecution(binding("attempt-1"), workflowId = "wf-1", stepId = "step-1")
        adapter.createOrRecoverExecution(
            binding("attempt-2").copy(caseId = "case-2"),
            workflowId = "wf-1",
            stepId = "step-2",
        )
        assertThat(registry.activeCount()).isEqualTo(2)

        registry.shutdownActiveCases(adapter)

        assertThat(registry.activeCount()).isZero()
        server.verify()
    }

    @Test
    fun `unreachable runtime - reconcile is Indeterminate RUNTIME_UNREACHABLE and never Succeeded`() {
        val (adapter, server) = build()
        server.expect(requestTo(eventsUrl)).andRespond { throw IOException("connection refused") }

        val verdict = adapter.reconcile("case-1")

        assertThat(verdict).isInstanceOf(AgentOsExecutionVerdict.Indeterminate::class.java)
        assertThat((verdict as AgentOsExecutionVerdict.Indeterminate).reason).isEqualTo(VerdictDeriver.RUNTIME_UNREACHABLE)
        assertThat(verdict).isNotInstanceOf(AgentOsExecutionVerdict.Succeeded::class.java)
        server.verify()
    }

    @Test
    fun `unreachable runtime - observeTurn exhausts its budget into Indeterminate and never Succeeded`() {
        val unreachableSse: (String) -> AgentOsSseClient = {
            AgentOsSseClient(
                baseUrl = "http://127.0.0.1:1", // connection refused
                backoffBaseMs = 1,
                backoffMaxMs = 2,
                maxReconnects = 1,
                stallTimeoutMs = 25,
            )
        }
        val (adapter, server) = build(sseClientFactory = unreachableSse)
        // The REST catch-up attempted on every reconnection is unreachable too.
        server.expect(ExpectedCount.manyTimes(), requestTo(eventsUrl))
            .andRespond { throw IOException("connection refused") }

        val verdict = adapter.observeTurn("case-1", "attempt-1", timeoutMs = 250)

        assertThat(verdict).isInstanceOf(AgentOsExecutionVerdict.Indeterminate::class.java)
        assertThat((verdict as AgentOsExecutionVerdict.Indeterminate).reason)
            .isEqualTo(VerdictDeriver.RECONNECT_BUDGET_EXHAUSTED)
        assertThat(verdict).isNotInstanceOf(AgentOsExecutionVerdict.Succeeded::class.java)
    }

    @Test
    fun `reconciliation after a factory restart resumes observation without concluding from silence`() {
        // Before the "restart": the case exists and a turn was started.
        val (before, serverBefore) = build()
        serverBefore.expect(requestTo("$baseUrl/api/cases"))
            .andRespond(withSuccess("""{"id":"case-1","namespaceId":"ns-1"}""", MediaType.APPLICATION_JSON))
        serverBefore.expect(requestTo(eventsUrl)).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON))
        serverBefore.expect(requestTo("$baseUrl/api/cases/case-1/messages"))
            .andRespond(withSuccess("", MediaType.APPLICATION_JSON))

        before.createOrRecoverExecution(binding("attempt-1"), workflowId = "wf-1", stepId = "step-1")
        before.startTurn(binding("attempt-1"), persona = "architect", brief = "do the thing")
        serverBefore.verify()

        // "Restart": a brand-new adapter — every in-memory record, checkpoint
        // and baseline is lost. Re-observation fails safe to a full replay.
        val (after, serverAfter) = build()
        // The case is still running: reconciliation resumes the observation and
        // stays Indeterminate — the absence of a terminal event is not a verdict.
        val runningHistory = """[
          {"id":"e1","type":"CaseStatusEvent","status":"RUNNING","caseId":"case-1","timestamp":"2026-01-01T00:00:00Z"}
        ]"""
        // When the terminal event lands durably, reconciliation derives the verdict.
        val finishedHistory = """[
          {"id":"e1","type":"CaseStatusEvent","status":"RUNNING","caseId":"case-1","timestamp":"2026-01-01T00:00:00Z"},
          {"id":"m1","type":"MessageEvent","caseId":"case-1","timestamp":"2026-01-01T00:00:01Z","actor":{"role":"AGENT"},"content":[{"content":"done"}]},
          {"id":"e2","type":"CaseStatusEvent","status":"IDLE","caseId":"case-1","timestamp":"2026-01-01T00:00:02Z"}
        ]"""
        serverAfter.expect(requestTo(eventsUrl)).andRespond(withSuccess(runningHistory, MediaType.APPLICATION_JSON))
        serverAfter.expect(requestTo(eventsUrl)).andRespond(withSuccess(finishedHistory, MediaType.APPLICATION_JSON))

        val resumed = after.reconcile("case-1")
        assertThat(resumed).isInstanceOf(AgentOsExecutionVerdict.Indeterminate::class.java)
        assertThat((resumed as AgentOsExecutionVerdict.Indeterminate).reason).isEqualTo(VerdictDeriver.NOT_QUIESCENT)
        assertThat(resumed).isNotInstanceOf(AgentOsExecutionVerdict.Succeeded::class.java)

        val terminal = after.reconcile("case-1")
        assertThat(terminal).isInstanceOf(AgentOsExecutionVerdict.Indeterminate::class.java)
        assertThat((terminal as AgentOsExecutionVerdict.Indeterminate).reason)
            .isEqualTo(VerdictDeriver.AGENT_NO_STRUCTURED_RESULT)
        assertThat(terminal.evidence["summary"]).isEqualTo("done")
        serverAfter.verify()
    }
}
