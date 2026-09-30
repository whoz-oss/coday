package io.whozoss.factory.adapter.agentos

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Integration tests of [AgentOsSseClient] against an in-process AgentOS SSE
 * double ([FakeAgentOsSseServer], JDK `HttpServer`). Timings are shrunk
 * (millisecond backoffs and stall windows) so the scenarios run fast.
 */
class AgentOsSseClientTest {

    private lateinit var server: FakeAgentOsSseServer

    @BeforeEach
    fun startServer() {
        server = FakeAgentOsSseServer()
    }

    @AfterEach
    fun stopServer() {
        server.close()
    }

    private fun client(maxReconnects: Int = 3, stallTimeoutMs: Long = 150) = AgentOsSseClient(
        baseUrl = server.baseUrl,
        backoffBaseMs = 5,
        backoffMaxMs = 20,
        maxReconnects = maxReconnects,
        stallTimeoutMs = stallTimeoutMs,
    )

    private fun processedIdsCounter(): Pair<(CaseEventView) -> Unit, ConcurrentHashMap<String, AtomicInteger>> {
        val counts = ConcurrentHashMap<String, AtomicInteger>()
        val onEvent: (CaseEventView) -> Unit = { event ->
            counts.computeIfAbsent(event.eventId) { AtomicInteger(0) }.incrementAndGet()
        }
        return onEvent to counts
    }

    @Test
    fun `initial connection before execution never concludes and times out as Indeterminate`() {
        // live-only stream: heartbeats but no durable event, connection held open
        server.enqueue(
            FakeAgentOsSseServer.Script(
                frames = (1..60).map { FakeAgentOsSseServer.heartbeat(delayAfterMs = 25) },
            ),
        )

        val verdict = client(stallTimeoutMs = 1_000).observe(caseId = "case-1", timeoutMs = 300)

        assertThat(verdict).isInstanceOf(AgentOsExecutionVerdict.Indeterminate::class.java)
        assertThat((verdict as AgentOsExecutionVerdict.Indeterminate).reason)
            .isEqualTo(VerdictDeriver.OBSERVATION_TIMEOUT)
    }

    @Test
    fun `connection after start derives Succeeded from RUNNING then IDLE with an agent message`() {
        val (onEvent, counts) = processedIdsCounter()
        server.enqueueEvents(
            FakeAgentOsSseServer.statusEvent("e1", "case-1", "RUNNING"),
            FakeAgentOsSseServer.agentMessageEvent("m1", "case-1", "all good"),
            FakeAgentOsSseServer.statusEvent("e2", "case-1", "IDLE", "2026-01-01T00:00:02Z"),
            holdAfterMs = 1_000,
        )

        val verdict = client().observe(caseId = "case-1", timeoutMs = 5_000, onEvent = onEvent)

        assertThat(verdict).isInstanceOf(AgentOsExecutionVerdict.Succeeded::class.java)
        assertThat((verdict as AgentOsExecutionVerdict.Succeeded).outputs["summary"]).isEqualTo("all good")
        assertThat(counts.keys).containsExactlyInAnyOrder("e1", "m1", "e2")
    }

    @Test
    fun `connection after completion replays the full history and derives the final verdict`() {
        // post-termination connection: snapshot replay, then the stream auto-closes
        server.enqueueEvents(
            FakeAgentOsSseServer.statusEvent("e1", "case-1", "RUNNING"),
            FakeAgentOsSseServer.agentMessageEvent("m1", "case-1", "finished work"),
            FakeAgentOsSseServer.statusEvent("e2", "case-1", "IDLE", "2026-01-01T00:00:02Z"),
        )

        val verdict = client().observe(caseId = "case-1", timeoutMs = 5_000)

        assertThat(verdict).isInstanceOf(AgentOsExecutionVerdict.Succeeded::class.java)
        assertThat((verdict as AgentOsExecutionVerdict.Succeeded).outputs["summary"]).isEqualTo("finished work")
    }

    @Test
    fun `connection after completion replays a terminal ERROR as Failed - never Succeeded`() {
        server.enqueueEvents(
            FakeAgentOsSseServer.statusEvent("e1", "case-1", "RUNNING"),
            FakeAgentOsSseServer.statusEvent("e2", "case-1", "ERROR", "2026-01-01T00:00:01Z"),
        )

        val verdict = client().observe(caseId = "case-1", timeoutMs = 5_000)

        assertThat(verdict).isInstanceOf(AgentOsExecutionVerdict.Failed::class.java)
        assertThat((verdict as AgentOsExecutionVerdict.Failed).code).isEqualTo("AGENT_CASE_ERROR")
    }

    @Test
    fun `a dropped connection reconnects, reconciles over REST and never double-processes an event`() {
        val (onEvent, counts) = processedIdsCounter()
        val reconcileCalls = AtomicInteger(0)
        val running = CaseEventView.fromJson(
            mapOf(
                "id" to "e1",
                "type" to "CaseStatusEvent",
                "status" to "RUNNING",
                "caseId" to "case-1",
                "timestamp" to "2026-01-01T00:00:00Z",
            ),
        )!!
        // first connection: only RUNNING, then the stream drops mid-turn
        server.enqueueEvents(FakeAgentOsSseServer.statusEvent("e1", "case-1", "RUNNING"))
        // second connection: full replay + the events that landed while disconnected
        server.enqueueEvents(
            FakeAgentOsSseServer.statusEvent("e1", "case-1", "RUNNING"),
            FakeAgentOsSseServer.agentMessageEvent("m1", "case-1", "recovered"),
            FakeAgentOsSseServer.statusEvent("e2", "case-1", "IDLE", "2026-01-01T00:00:02Z"),
            holdAfterMs = 1_000,
        )

        val verdict = client().observe(
            caseId = "case-1",
            timeoutMs = 5_000,
            onEvent = onEvent,
            reconcile = {
                reconcileCalls.incrementAndGet()
                // REST catch-up sees the same durable RUNNING event — already known
                AgentOsSseClient.ReconcileResult(events = listOf(running), verdict = null)
            },
        )

        assertThat(verdict).isInstanceOf(AgentOsExecutionVerdict.Succeeded::class.java)
        assertThat(reconcileCalls.get()).isGreaterThanOrEqualTo(1)
        // every event processed exactly once across replay + live + reconnect + REST catch-up
        assertThat(counts.entries.associate { it.key to it.value.get() })
            .containsExactlyInAnyOrderEntriesOf(mapOf("e1" to 1, "m1" to 1, "e2" to 1))
    }

    @Test
    fun `a duplicate event in the stream is processed exactly once`() {
        val (onEvent, counts) = processedIdsCounter()
        server.enqueueEvents(
            FakeAgentOsSseServer.statusEvent("e1", "case-1", "RUNNING"),
            FakeAgentOsSseServer.statusEvent("e1", "case-1", "RUNNING"), // double delivery
            FakeAgentOsSseServer.agentMessageEvent("m1", "case-1", "done"),
            FakeAgentOsSseServer.statusEvent("e2", "case-1", "IDLE", "2026-01-01T00:00:02Z"),
            holdAfterMs = 1_000,
        )

        val verdict = client().observe(caseId = "case-1", timeoutMs = 5_000, onEvent = onEvent)

        assertThat(verdict).isInstanceOf(AgentOsExecutionVerdict.Succeeded::class.java)
        assertThat(counts["e1"]!!.get()).isEqualTo(1)
    }

    @Test
    fun `IDLE with an unanswered question is WaitingHuman`() {
        server.enqueueEvents(
            FakeAgentOsSseServer.statusEvent("e1", "case-1", "RUNNING"),
            FakeAgentOsSseServer.questionEvent("q1", "case-1", "Which branch?"),
            FakeAgentOsSseServer.statusEvent("e2", "case-1", "IDLE", "2026-01-01T00:00:02Z"),
            holdAfterMs = 1_000,
        )

        val verdict = client().observe(caseId = "case-1", timeoutMs = 5_000)

        assertThat(verdict).isInstanceOf(AgentOsExecutionVerdict.WaitingHuman::class.java)
        verdict as AgentOsExecutionVerdict.WaitingHuman
        assertThat(verdict.questionRef).isEqualTo("q1")
        assertThat(verdict.questionText).isEqualTo("Which branch?")
    }

    @Test
    fun `IDLE with an answered question and an agent message is not WaitingHuman`() {
        server.enqueueEvents(
            FakeAgentOsSseServer.statusEvent("e1", "case-1", "RUNNING"),
            FakeAgentOsSseServer.questionEvent("q1", "case-1", "Which branch?"),
            FakeAgentOsSseServer.answerEvent("a1", "case-1", "q1"),
            FakeAgentOsSseServer.agentMessageEvent("m1", "case-1", "proceeded on main", "2026-01-01T00:00:03Z"),
            FakeAgentOsSseServer.statusEvent("e2", "case-1", "IDLE", "2026-01-01T00:00:04Z"),
            holdAfterMs = 1_000,
        )

        val verdict = client().observe(caseId = "case-1", timeoutMs = 5_000)

        assertThat(verdict).isInstanceOf(AgentOsExecutionVerdict.Succeeded::class.java)
    }

    @Test
    fun `IDLE without question and without structured output is Indeterminate - never Succeeded`() {
        server.enqueueEvents(
            FakeAgentOsSseServer.statusEvent("e1", "case-1", "RUNNING"),
            FakeAgentOsSseServer.statusEvent("e2", "case-1", "IDLE", "2026-01-01T00:00:01Z"),
            holdAfterMs = 1_000,
        )

        val verdict = client().observe(caseId = "case-1", timeoutMs = 5_000)

        assertThat(verdict).isInstanceOf(AgentOsExecutionVerdict.Indeterminate::class.java)
        assertThat((verdict as AgentOsExecutionVerdict.Indeterminate).reason)
            .isEqualTo(VerdictDeriver.IDLE_WITHOUT_OUTPUT)
    }

    @Test
    fun `KILLED is Failed and never Succeeded`() {
        server.enqueueEvents(
            FakeAgentOsSseServer.statusEvent("e1", "case-1", "RUNNING"),
            FakeAgentOsSseServer.statusEvent("e2", "case-1", "KILLED", "2026-01-01T00:00:01Z"),
        )

        val verdict = client().observe(caseId = "case-1", timeoutMs = 5_000)

        assertThat(verdict).isInstanceOf(AgentOsExecutionVerdict.Failed::class.java)
        assertThat((verdict as AgentOsExecutionVerdict.Failed).code).isEqualTo("AGENT_CASE_KILLED")
    }

    @Test
    fun `an exhausted reconnection budget is Indeterminate - never a verdict by silence`() {
        // no script enqueued: every connection closes immediately without data
        val verdict = client(maxReconnects = 2).observe(caseId = "case-1", timeoutMs = 5_000)

        assertThat(verdict).isInstanceOf(AgentOsExecutionVerdict.Indeterminate::class.java)
        assertThat((verdict as AgentOsExecutionVerdict.Indeterminate).reason)
            .isEqualTo(VerdictDeriver.RECONNECT_BUDGET_EXHAUSTED)
        // initial connection + 2 reconnects
        assertThat(server.connectionCount.get()).isEqualTo(3)
    }

    @Test
    fun `a stalled stream without heartbeat is treated as dropped and reconciled`() {
        // connection delivers RUNNING then goes silent (no frame, no :keep-alive)
        server.enqueue(
            FakeAgentOsSseServer.Script(
                frames = FakeAgentOsSseServer.sseEvent(FakeAgentOsSseServer.statusEvent("e1", "case-1", "RUNNING")),
                holdAfterMs = 5_000,
            ),
        )
        // reconnection replays the completed history
        server.enqueueEvents(
            FakeAgentOsSseServer.statusEvent("e1", "case-1", "RUNNING"),
            FakeAgentOsSseServer.agentMessageEvent("m1", "case-1", "recovered after stall"),
            FakeAgentOsSseServer.statusEvent("e2", "case-1", "IDLE", "2026-01-01T00:00:02Z"),
            holdAfterMs = 1_000,
        )

        val verdict = client(stallTimeoutMs = 100).observe(caseId = "case-1", timeoutMs = 5_000)

        assertThat(verdict).isInstanceOf(AgentOsExecutionVerdict.Succeeded::class.java)
        assertThat((verdict as AgentOsExecutionVerdict.Succeeded).outputs["summary"])
            .isEqualTo("recovered after stall")
    }

    @Test
    fun `frames of a foreign case are strictly ignored`() {
        val (onEvent, counts) = processedIdsCounter()
        server.enqueueEvents(
            FakeAgentOsSseServer.statusEvent("x1", "other-case", "IDLE"),
            FakeAgentOsSseServer.agentMessageEvent("x2", "other-case", "foreign output"),
            FakeAgentOsSseServer.statusEvent("e1", "case-1", "RUNNING"),
            FakeAgentOsSseServer.agentMessageEvent("m1", "case-1", "mine"),
            FakeAgentOsSseServer.statusEvent("e2", "case-1", "IDLE", "2026-01-01T00:00:02Z"),
            holdAfterMs = 1_000,
        )

        val checkpoint = EventCheckpoint()
        val verdict = client().observe(caseId = "case-1", timeoutMs = 5_000, checkpoint = checkpoint, onEvent = onEvent)

        assertThat(verdict).isInstanceOf(AgentOsExecutionVerdict.Succeeded::class.java)
        assertThat((verdict as AgentOsExecutionVerdict.Succeeded).outputs["summary"]).isEqualTo("mine")
        // the foreign frames never reached processing nor the checkpoint
        assertThat(counts.keys).doesNotContain("x1", "x2")
        assertThat(checkpoint.mark.lastEventId).isEqualTo("e2")
    }

    @Test
    fun `transient events are display-only - no verdict, no checkpoint advance`() {
        val (onEvent, counts) = processedIdsCounter()
        server.enqueueEvents(
            FakeAgentOsSseServer.transientEvent("t1", "case-1", "ThinkingEvent"),
            FakeAgentOsSseServer.transientEvent("t2", "case-1", "TextChunkEvent"),
            FakeAgentOsSseServer.transientEvent("t3", "case-1", "CaseUpdatedEvent"),
            FakeAgentOsSseServer.statusEvent("e1", "case-1", "RUNNING", "2026-01-01T00:00:01Z"),
            FakeAgentOsSseServer.agentMessageEvent("m1", "case-1", "done", "2026-01-01T00:00:02Z"),
            FakeAgentOsSseServer.statusEvent("e2", "case-1", "IDLE", "2026-01-01T00:00:03Z"),
            holdAfterMs = 1_000,
        )

        val checkpoint = EventCheckpoint()
        val verdict = client().observe(caseId = "case-1", timeoutMs = 5_000, checkpoint = checkpoint, onEvent = onEvent)

        assertThat(verdict).isInstanceOf(AgentOsExecutionVerdict.Succeeded::class.java)
        assertThat(counts.keys).doesNotContain("t1", "t2", "t3")
        assertThat(checkpoint.mark.lastEventId).isEqualTo("e2")
    }

    @Test
    fun `a warm high-water mark dedups the replayed prefix without reprocessing`() {
        val (onEvent, counts) = processedIdsCounter()
        val checkpoint = EventCheckpoint()
        // a previous observation already recorded the RUNNING event
        checkpoint.record(
            CaseEventView.fromJson(
                mapOf(
                    "id" to "e1",
                    "type" to "CaseStatusEvent",
                    "status" to "RUNNING",
                    "caseId" to "case-1",
                    "timestamp" to "2026-01-01T00:00:00Z",
                ),
            )!!,
        )
        // the reconnection replays the full history, including the known prefix
        server.enqueueEvents(
            FakeAgentOsSseServer.statusEvent("e1", "case-1", "RUNNING"),
            FakeAgentOsSseServer.agentMessageEvent("m1", "case-1", "late message"),
            FakeAgentOsSseServer.statusEvent("e2", "case-1", "IDLE", "2026-01-01T00:00:02Z"),
            holdAfterMs = 1_000,
        )

        val verdict = client().observe(caseId = "case-1", timeoutMs = 5_000, checkpoint = checkpoint, onEvent = onEvent)

        assertThat(verdict).isInstanceOf(AgentOsExecutionVerdict.Succeeded::class.java)
        // the known prefix was not reprocessed…
        assertThat(counts).doesNotContainKey("e1")
        // …yet the warm history still fed the verdict derivation (message precedes IDLE)
        assertThat((verdict as AgentOsExecutionVerdict.Succeeded).outputs["summary"]).isEqualTo("late message")
    }
}
