package io.whozoss.factory.workflow

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.factory.agentattempt.domain.AgentAttemptStatus
import io.whozoss.factory.agentattempt.domain.DurableAgentAttempt
import io.whozoss.factory.agentattempt.service.DurableAgentAttemptService
import io.whozoss.factory.error.FactoryException
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.proxy.AgentOsProxyClient
import io.whozoss.factory.proxy.RunCostDto
import io.whozoss.factory.proxy.UsageTrackingUnavailableException
import io.whozoss.factory.workflow.domain.HumanInteractionRecord
import io.whozoss.factory.workflow.domain.WorkflowActionTypes
import io.whozoss.factory.workflow.domain.WorkflowBlockerCodes
import io.whozoss.factory.workflow.domain.WorkflowInstanceRecord
import io.whozoss.factory.workflow.domain.WorkflowProjectionRecord
import io.whozoss.factory.workflow.persistence.HumanInteractionRepository
import io.whozoss.factory.workflow.persistence.WorkflowEvidenceRepository
import io.whozoss.factory.workflow.persistence.WorkflowRepository
import io.whozoss.factory.workflow.service.WorkflowService
import io.whozoss.factory.workflow.sse.WorkflowSseHub
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Unit tests of the authoritative `allowedActions` / `blockers` derivation and
 * of the cost-control pass-through, driven by mocked state only.
 *
 * The tests assert that an action is emitted IFF the current state permits it
 * AND the caller is authorized, that every action carries the correct
 * `expectedRevision`, and that a disabled/unavailable usage-tracking surface
 * degrades to a clean 503 instead of a 500.
 */
class WorkflowActionsServiceTest {
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

    private fun projectionRecord(revision: Int, steps: List<Map<String, Any?>>): WorkflowProjectionRecord =
        WorkflowProjectionRecord(
            namespaceId = namespace,
            workflowId = workflowId,
            schemaVersion = "2",
            revision = revision,
            projectionHash = "hash",
            status = "ready",
            projection = mapOf(
                "schemaVersion" to "2",
                "workflowId" to workflowId,
                "workflowType" to "wf",
                "title" to "Workflow",
                "status" to "ready",
                "steps" to steps,
            ),
            instance = null,
            governanceMode = null,
            definitionVersion = null,
            definitionHash = null,
            relations = null,
            controllerExecution = null,
            lifecycleState = "active",
        )

    private fun stubBaseline(
        revision: Int = 1,
        steps: List<Map<String, Any?>> = emptyList(),
        interactions: List<HumanInteractionRecord> = emptyList(),
        workflowAttempts: List<DurableAgentAttempt> = emptyList(),
        instance: WorkflowInstanceRecord? = null,
    ) {
        every { repository.findProjection(scope, namespace, workflowId) } returns projectionRecord(revision, steps)
        every { repository.findInstance(scope, namespace, workflowId) } returns instance
        every { interactionRepository.list(scope, namespace, workflowId, true) } returns interactions
        every { evidenceRepository.list(scope, namespace, workflowId) } returns emptyList()
        every { attempts.findByWorkflow(scope, namespace, workflowId) } returns workflowAttempts
    }

    private fun attempt(
        caseId: String,
        status: AgentAttemptStatus = AgentAttemptStatus.PENDING,
        revision: Int = 1,
    ) = DurableAgentAttempt(
        attemptId = "attempt-$caseId",
        caseId = caseId,
        namespaceId = namespace,
        workflowId = workflowId,
        stepId = "step-$caseId",
        attemptNumber = 1,
        agentName = "agent",
        status = status,
        revision = revision,
    )

    private fun runCost(caseId: String, paused: Boolean = false, threshold: Double? = null) =
        RunCostDto(caseId, 0.0, 0L, threshold, paused, false, 0L)

    @Test
    fun `an open interaction yields a reply action fenced on the interaction revision`() {
        stubBaseline(
            revision = 2,
            interactions = listOf(
                HumanInteractionRecord(
                    "int-1", namespace, workflowId, "step-1", "approval", "waiting", 2,
                    mapOf("prompt" to "Approve?"),
                ),
            ),
        )

        val result = service.workflowActions(scope, namespace, workflowId, canReply = true)

        val reply = result.allowedActions.single { it.type == WorkflowActionTypes.REPLY }
        assertThat(reply.interactionId).isEqualTo("int-1")
        assertThat(reply.stepId).isEqualTo("step-1")
        assertThat(reply.expectedRevision).isEqualTo(2)
        assertThat(result.blockers.map { it.code }).contains(WorkflowBlockerCodes.WAITING_HUMAN_INTERACTION)
    }

    @Test
    fun `a reply action is withheld when the caller cannot act as a human but the blocker remains`() {
        stubBaseline(
            interactions = listOf(
                HumanInteractionRecord("int-1", namespace, workflowId, "step-1", "approval", "waiting", 1, emptyMap()),
            ),
        )

        val result = service.workflowActions(scope, namespace, workflowId, canReply = false)

        assertThat(result.allowedActions.none { it.type == WorkflowActionTypes.REPLY }).isTrue()
        assertThat(result.blockers.map { it.code }).contains(WorkflowBlockerCodes.WAITING_HUMAN_INTERACTION)
    }

    @Test
    fun `a blocked step yields a retry action fenced on the workflow revision`() {
        stubBaseline(revision = 4, steps = listOf(mapOf("id" to "gate", "status" to "blocked")))

        val result = service.workflowActions(scope, namespace, workflowId, canReply = true)

        val retry = result.allowedActions.single { it.type == WorkflowActionTypes.RETRY }
        assertThat(retry.stepId).isEqualTo("gate")
        assertThat(retry.expectedRevision).isEqualTo(4)
        assertThat(result.blockers.map { it.code }).contains(WorkflowBlockerCodes.STEP_BLOCKED)
        assertThat(result.blockers.single { it.code == WorkflowBlockerCodes.STEP_BLOCKED }.stepId).isEqualTo("gate")
    }

    @Test
    fun `an active durable attempt yields a cancel action fenced on the attempt revision`() {
        stubBaseline(
            workflowAttempts = listOf(attempt("case-9", AgentAttemptStatus.RUNNING, revision = 7)),
        )
        every { proxy.getRunCost("case-9", null) } returns null

        val result = service.workflowActions(scope, namespace, workflowId, canReply = true)

        val cancel = result.allowedActions.single { it.type == WorkflowActionTypes.CANCEL_ATTEMPT }
        assertThat(cancel.attemptId).isEqualTo("attempt-case-9")
        assertThat(cancel.stepId).isEqualTo("step-case-9")
        assertThat(cancel.caseId).isEqualTo("case-9")
        assertThat(cancel.expectedRevision).isEqualTo(7)
    }

    @Test
    fun `terminal attempts yield blockers and no cancel action`() {
        stubBaseline(
            workflowAttempts = listOf(
                attempt("case-a", AgentAttemptStatus.FAILED),
                attempt("case-b", AgentAttemptStatus.INDETERMINATE),
            ),
        )
        every { proxy.getRunCost("case-a", null) } returns null
        every { proxy.getRunCost("case-b", null) } returns null

        val result = service.workflowActions(scope, namespace, workflowId, canReply = true)

        assertThat(result.allowedActions.none { it.type == WorkflowActionTypes.CANCEL_ATTEMPT }).isTrue()
        assertThat(result.blockers.map { it.code })
            .contains(WorkflowBlockerCodes.ATTEMPT_FAILED, WorkflowBlockerCodes.UNKNOWN_RUNTIME)
    }

    @Test
    fun `a paused real cost yields continue and stop actions fenced on the workflow revision`() {
        stubBaseline(
            revision = 5,
            workflowAttempts = listOf(attempt("case-1")),
        )
        every { proxy.getRunCost("case-1", null) } returns runCost("case-1", paused = true, threshold = 50.0)

        val result = service.workflowActions(scope, namespace, workflowId, canReply = true)

        assertThat(result.blockers.map { it.code }).contains(WorkflowBlockerCodes.REAL_COST_PAUSED)
        val continueCost = result.allowedActions.single { it.type == WorkflowActionTypes.CONTINUE_COST }
        val stopCost = result.allowedActions.single { it.type == WorkflowActionTypes.STOP_COST }
        assertThat(continueCost.caseId).isEqualTo("case-1")
        assertThat(continueCost.expectedRevision).isEqualTo(5)
        assertThat(stopCost.caseId).isEqualTo("case-1")
        assertThat(stopCost.expectedRevision).isEqualTo(5)
    }

    @Test
    fun `no actions are emitted when the state does not allow them`() {
        stubBaseline(revision = 3, steps = listOf(mapOf("id" to "done", "status" to "completed")))

        val result = service.workflowActions(scope, namespace, workflowId, canReply = true)

        assertThat(result.allowedActions).isEmpty()
        assertThat(result.blockers).isEmpty()
    }

    @Test
    fun `a missing workflow yields a 404 workflow-not-found`() {
        every { repository.findProjection(scope, namespace, workflowId) } returns null
        every { repository.findInstance(scope, namespace, workflowId) } returns null

        assertThatThrownBy { service.workflowActions(scope, namespace, workflowId, canReply = true) }
            .isInstanceOf(FactoryException::class.java)
            .extracting { (it as FactoryException).errorCode }
            .isEqualTo("WORKFLOW_NOT_FOUND")
    }

    @Test
    fun `cost continue relays to AgentOS for every bound case`() {
        stubBaseline(revision = 3, workflowAttempts = listOf(attempt("case-1")))
        every { proxy.continueRunCost("case-1", 50.0, "actor-1") } returns true

        val result = service.continueRunCost(scope, namespace, workflowId, "actor-1", null, 50.0, 3)

        assertThat(result["operation"]).isEqualTo("continue")
        assertThat(result["caseIds"]).isEqualTo(listOf("case-1"))
        assertThat(result["updated"]).isEqualTo(1)
        verify(exactly = 1) { proxy.continueRunCost("case-1", 50.0, "actor-1") }
    }

    @Test
    fun `cost continue resolves the threshold from the run cost when omitted`() {
        stubBaseline(workflowAttempts = listOf(attempt("case-1")))
        every { proxy.getRunCost("case-1", "actor-1") } returns runCost("case-1", paused = true, threshold = 42.0)
        every { proxy.continueRunCost("case-1", 42.0, "actor-1") } returns true

        service.continueRunCost(scope, namespace, workflowId, "actor-1", null, null, null)

        verify(exactly = 1) { proxy.continueRunCost("case-1", 42.0, "actor-1") }
    }

    @Test
    fun `cost stop relays to AgentOS`() {
        stubBaseline(workflowAttempts = listOf(attempt("case-2")))
        every { proxy.stopRunCost("case-2", "actor-1") } returns true

        val result = service.stopRunCost(scope, namespace, workflowId, "actor-1", null, null)

        assertThat(result["operation"]).isEqualTo("stop")
        assertThat(result["updated"]).isEqualTo(1)
        verify(exactly = 1) { proxy.stopRunCost("case-2", "actor-1") }
    }

    @Test
    fun `a stale cost-control revision is rejected with REVISION_CONFLICT`() {
        stubBaseline(revision = 3, workflowAttempts = listOf(attempt("case-1")))

        assertThatThrownBy { service.stopRunCost(scope, namespace, workflowId, "actor-1", null, 2) }
            .isInstanceOf(FactoryException::class.java)
            .extracting { (it as FactoryException).errorCode }
            .isEqualTo("REVISION_CONFLICT")
    }

    @Test
    fun `a disabled proxy degrades every cost command to a clean 503`() {
        val disabled = WorkflowService(
            repository,
            evidenceRepository,
            interactionRepository,
            mockk<WorkflowSseHub>(relaxed = true),
        )

        assertThatThrownBy { disabled.continueRunCost(scope, namespace, workflowId, "actor-1", null, 50.0, null) }
            .isInstanceOf(UsageTrackingUnavailableException::class.java)
            .hasMessage("Usage tracking is disabled")
        assertThatThrownBy { disabled.stopRunCost(scope, namespace, workflowId, "actor-1", null, null) }
            .isInstanceOf(UsageTrackingUnavailableException::class.java)
            .hasMessage("Usage tracking is disabled")
    }

    @Test
    fun `an unreachable AgentOS on cost control degrades to a clean 503`() {
        stubBaseline(workflowAttempts = listOf(attempt("case-1")))
        every { proxy.stopRunCost("case-1", "actor-1") } throws
            io.whozoss.factory.proxy.AgentOsUnavailableException("connection refused")

        assertThatThrownBy { service.stopRunCost(scope, namespace, workflowId, "actor-1", null, null) }
            .isInstanceOf(UsageTrackingUnavailableException::class.java)
            .hasMessage("Usage tracking is disabled")
    }

    @Test
    fun `a workflow without a bound case cannot be cost-controlled`() {
        stubBaseline()

        assertThatThrownBy { service.stopRunCost(scope, namespace, workflowId, "actor-1", null, null) }
            .isInstanceOf(FactoryException::class.java)
            .extracting { (it as FactoryException).errorCode }
            .isEqualTo("NO_RUN_CASE")
    }
}
