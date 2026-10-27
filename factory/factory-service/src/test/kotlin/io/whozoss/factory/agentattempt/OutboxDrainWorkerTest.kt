package io.whozoss.factory.agentattempt

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.factory.agentattempt.service.DrainReport
import io.whozoss.factory.agentattempt.service.OutboxDrainService
import io.whozoss.factory.agentattempt.service.OutboxDrainWorker
import io.whozoss.factory.agentattempt.service.OutboxEvent
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.proxy.AgentOsProxyClient
import io.whozoss.factory.workflow.service.SessionRunResult
import io.whozoss.factory.workflow.service.SessionRunService
import java.nio.file.Path
import org.junit.jupiter.api.Test

/**
 * Pure unit tests (no Spring, no database) of [OutboxDrainWorker].
 *
 * The drain is mocked so the tests focus on the continuation contract: a
 * `result_submitted` event is mapped to a scoped [SessionRunService.runSession]
 * call, while every other event type is ignored.
 */
class OutboxDrainWorkerTest {

    private val drainService = mockk<OutboxDrainService>()
    private val sessionRunService = mockk<SessionRunService>()
    private val proxy = mockk<AgentOsProxyClient>()
    private val worker = OutboxDrainWorker(drainService, sessionRunService, proxy, ObjectMapper())

    private fun stubDrain(vararg events: OutboxEvent) {
        every { drainService.pendingOrganizations() } returns listOf("org-1")
        every { drainService.drainPending(any(), any(), any(), any()) } answers {
            @Suppress("UNCHECKED_CAST")
            val handler = invocation.args[3] as (OutboxEvent) -> Unit
            events.forEach(handler)
            DrainReport(dispatched = events.size, failed = 0)
        }
    }

    private fun stubRunSession() {
        every { sessionRunService.runSession(any(), any(), any(), any(), any()) } returns
            SessionRunResult("ns-1", "wf-1", "completed", emptyList())
    }

    @Test
    fun `a result_submitted event triggers a scoped sequencer continuation`() {
        stubDrain(
            OutboxEvent(
                id = "evt-1",
                eventType = "result_submitted",
                payload = """{"attemptId":"attempt-1","namespaceId":"ns-1","workflowId":"wf-1","stepId":"s1"}""",
                attempts = 0,
                workstreamId = "ws-1",
            ),
        )
        stubRunSession()
        every { proxy.resolveRepoRoot("ns-1", null) } returns "/repo"

        worker.drain()

        verify(exactly = 1) {
            sessionRunService.runSession(TenantScope("org-1", "ws-1"), "ns-1", "wf-1", Path.of("/repo"), null)
        }
    }

    @Test
    fun `a non-result event never triggers a continuation`() {
        stubDrain(OutboxEvent(id = "evt-2", eventType = "other", payload = "{}", attempts = 0, workstreamId = "ws-1"))

        worker.drain()

        verify(exactly = 0) { sessionRunService.runSession(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a continuation without continuation coordinates is ignored`() {
        stubDrain(
            OutboxEvent(
                id = "evt-3",
                eventType = "result_submitted",
                payload = """{"attemptId":"attempt-1"}""",
                attempts = 0,
                workstreamId = "ws-1",
            ),
        )

        worker.drain()

        verify(exactly = 0) { sessionRunService.runSession(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a failing continuation never breaks the worker`() {
        stubDrain(
            OutboxEvent(
                id = "evt-4",
                eventType = "result_submitted",
                payload = """{"namespaceId":"ns-1","workflowId":"wf-1"}""",
                attempts = 0,
                workstreamId = "ws-1",
            ),
        )
        every { proxy.resolveRepoRoot("ns-1", null) } returns null
        every { sessionRunService.runSession(any(), any(), any(), any(), any()) } throws IllegalStateException("boom")

        worker.drain()

        verify(exactly = 1) { sessionRunService.runSession(any(), any(), any(), any(), any()) }
    }
}
