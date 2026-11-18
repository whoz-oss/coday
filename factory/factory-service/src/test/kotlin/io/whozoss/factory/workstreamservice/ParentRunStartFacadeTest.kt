package io.whozoss.factory.workstreamservice

import io.mockk.every
import io.mockk.mockk
import io.whozoss.factory.agentattempt.service.DurableAgentAttemptService
import io.whozoss.factory.config.SessionProperties
import io.whozoss.factory.run.domain.RunRecord
import io.whozoss.factory.run.service.RunService
import io.whozoss.factory.run.service.RunStartCommand
import io.whozoss.factory.web.AuthenticatedUser
import io.whozoss.factory.workflow.service.CreateWorkflowRunService
import io.whozoss.factory.workflow.service.WorkflowService
import io.whozoss.factory.workstream.WorkstreamService
import io.whozoss.factory.workstream.domain.Workstream
import io.whozoss.factory.workstream.domain.WorkstreamStatus
import io.whozoss.factory.workstream.projection.WorkstreamProjectionService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ParentRunStartFacadeTest {
    private val namespace = "0d4bd471-df37-43d8-a8f7-c989f95e71d7"
    private val user = AuthenticatedUser("alice", "org-test")
    private val workstream = Workstream("org-test", "ws-test", namespace, "Test workstream", WorkstreamStatus.ACTIVE)

    @Test
    fun `separate requests for the same ticket launch separate parents and only warn`() {
        val workstreams = mockk<WorkstreamService>()
        every { workstreams.findByNamespace("org-test", namespace) } returns workstream
        every { workstreams.findDomain(any(), "ws-test") } returns workstream
        every { workstreams.requireConfiguredNamespace(any()) } returns namespace

        val runs = mockk<RunService>()
        val created = linkedMapOf<String, RunRecord>()
        every { runs.startRun(any(), namespace, any(), any(), "alice") } answers {
            val command = thirdArg<RunStartCommand>()
            created.getOrPut(command.runId) {
                RunRecord(namespace, command.runId, command.runType, "1.0.0", command.title,
                    ticket = command.ticket, rootCaseId = "root-${command.runId}")
            }
        }
        every { runs.listRuns(any(), namespace) } answers { created.values.toList() }
        val facade = WorkstreamServiceController(
            mockk<WorkflowService>(), mockk<DurableAgentAttemptService>(), mockk<WorkstreamProjectionService>(),
            workstreams, mockk<CreateWorkflowRunService>(), runs, SessionProperties(),
        )
        val request = mapOf("runType" to "forge.delivery", "ticket" to "WZ-36147")

        val first = facade.startParentRun(namespace, request, "approach-a", user).body!!.data
        val replay = facade.startParentRun(namespace, request, "approach-a", user).body!!.data
        val second = facade.startParentRun(namespace, request, "approach-b", user).body!!.data

        assertThat(first["runId"]).isEqualTo(replay["runId"])
        assertThat(first["runId"]).isNotEqualTo(second["runId"])
        assertThat(first["rootCaseId"]).isNotEqualTo(second["rootCaseId"])
        assertThat(created).hasSize(2)
        @Suppress("UNCHECKED_CAST")
        val overlap = second["concurrentRuns"] as List<Map<String, String>>
        assertThat(overlap).containsExactly(mapOf(
            "runId" to first.getValue("runId") as String,
            "runType" to "forge.delivery", "status" to "running",
        ))
    }
}
