package io.whozoss.factory.workflow

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.whozoss.factory.agentattempt.domain.AgentAttemptStatus
import io.whozoss.factory.agentattempt.domain.AgentStepResultFinding
import io.whozoss.factory.agentattempt.domain.AgentStepResultSubmitted
import io.whozoss.factory.agentattempt.domain.DurableAgentAttempt
import io.whozoss.factory.agentattempt.domain.IdempotencyKeyCollisionException
import io.whozoss.factory.agentattempt.persistence.IdempotencyRecord
import io.whozoss.factory.agentattempt.persistence.IdempotencyRepository
import io.whozoss.factory.agentattempt.service.AgentStepResultService
import io.whozoss.factory.agentattempt.service.DurableAgentAttemptService
import io.whozoss.factory.capability.CapabilityExecutionService
import io.whozoss.factory.capability.FailedAttemptSummary
import io.whozoss.factory.capability.NeedsResearchRouter
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.proxy.AgentOsProxyClient
import io.whozoss.factory.workflow.domain.HumanInteractionRecord
import io.whozoss.factory.workflow.domain.WorkflowActionTypes
import io.whozoss.factory.workflow.domain.WorkflowBlockerCodes
import io.whozoss.factory.workflow.domain.WorkflowDefinitionRecord
import io.whozoss.factory.workflow.domain.WorkflowErrorCodes
import io.whozoss.factory.workflow.domain.WorkflowEvidenceItem
import io.whozoss.factory.workflow.domain.WorkflowException
import io.whozoss.factory.workflow.domain.WorkflowInstanceRecord
import io.whozoss.factory.workflow.domain.WorkflowProjectionRecord
import io.whozoss.factory.workflow.domain.WorkflowStatuses
import io.whozoss.factory.workflow.domain.WorkflowStepStateRecord
import io.whozoss.factory.workflow.persistence.HumanInteractionRepository
import io.whozoss.factory.workflow.persistence.ProjectionPublishResult
import io.whozoss.factory.workflow.persistence.WorkflowEvidenceRepository
import io.whozoss.factory.workflow.persistence.WorkflowRepository
import io.whozoss.factory.workflow.service.SessionRunSubmissionService
import io.whozoss.factory.workflow.service.WorkflowHttpResult
import io.whozoss.factory.workflow.service.WorkflowService
import io.whozoss.factory.workflow.sse.WorkflowSseHub
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Unit tests of the governed `retryFailedStep` command and of the `retry` allowed action it backs.
 *
 * DAG used: `A` (agent, failed) <- `B` (agent) <- `C` (code); `E` depends on `A` AND on `F`, and `F` genuinely failed;
 * `D` is independent and completed; `H` is a human step. Dependants blocked ONLY because of `A` go back to `pending`;
 * `E` stays blocked because of the REAL failure of `F`.
 */
class WorkflowFailedStepRetryTest {

    private val scope = TenantScope("org", "ws")
    private val namespace = "ns-1"
    private val workflowId = "wf-1"
    private val mapper = jacksonObjectMapper()

    private val repository = mockk<WorkflowRepository>()
    private val evidenceRepository = mockk<WorkflowEvidenceRepository>()
    private val interactionRepository = mockk<HumanInteractionRepository>()
    private val sseHub = mockk<WorkflowSseHub>(relaxed = true)
    private val proxy = mockk<AgentOsProxyClient>()
    private val attempts = mockk<DurableAgentAttemptService>()
    private val results = mockk<AgentStepResultService>()
    private val submissions = mockk<SessionRunSubmissionService>()
    private val router = mockk<NeedsResearchRouter>()
    private val idempotency = InMemoryIdempotency()

    private val service = WorkflowService(
        repository = repository,
        evidenceRepository = evidenceRepository,
        interactionRepository = interactionRepository,
        sseHub = sseHub,
        agentOsProxyClient = proxy,
        durableAgentAttemptService = attempts,
        agentStepResultService = results,
        sessionRunSubmissionService = submissions,
        needsResearchRouter = router,
        idempotencyRepository = idempotency,
        objectMapper = mapper,
    )

    private class InMemoryIdempotency : IdempotencyRepository {
        val store = HashMap<String, IdempotencyRecord>()
        override fun find(scope: TenantScope, idempotencyKey: String) = store[idempotencyKey]
        override fun save(scope: TenantScope, idempotencyKey: String, requestHash: String, responsePayload: String) {
            store[idempotencyKey] = IdempotencyRecord(requestHash, responsePayload, "completed")
        }
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private fun step(id: String, vararg deps: String, kind: String = "agent") = mapOf(
        "id" to id,
        "name" to "Step $id",
        "responsibility" to mapOf("kind" to kind, "name" to "Persona$id"),
        "dependsOn" to deps.toList(),
    )

    private val definition = WorkflowDefinitionRecord(
        workflowType = "wf-type",
        version = "1",
        definitionHash = "hash-1",
        definition = mapOf(
            "steps" to listOf(
                step("A"), step("B", "A"), step("C", "B", kind = "code"),
                step("D"), step("F"), step("E", "A", "F"), step("H", kind = "human"),
            ),
        ),
    )

    private val defaultStatuses = mapOf(
        "A" to WorkflowStatuses.FAILED,
        "B" to WorkflowStatuses.BLOCKED,
        "C" to WorkflowStatuses.BLOCKED,
        "D" to WorkflowStatuses.COMPLETED,
        "F" to WorkflowStatuses.FAILED,
        "E" to WorkflowStatuses.BLOCKED,
        "H" to WorkflowStatuses.COMPLETED,
    )

    private fun instance(
        runStatus: String = WorkflowStatuses.FAILED,
        revision: Int = 7,
        statuses: Map<String, String> = defaultStatuses,
        extraInstance: Map<String, Any?> = emptyMap(),
        extraProjection: Map<String, Any?> = emptyMap(),
    ): WorkflowInstanceRecord {
        val steps = statuses.map { (id, status) -> mapOf("id" to id, "status" to status) }
        return WorkflowInstanceRecord(
            namespaceId = namespace,
            workflowId = workflowId,
            revision = revision,
            status = "active",
            creationCommandHash = "h",
            instance = mapOf(
                "workflowType" to "wf-type",
                "definitionVersion" to "1",
                "definitionHash" to "hash-1",
                "status" to runStatus,
                "steps" to steps,
            ) + extraInstance,
            projection = mapOf("status" to runStatus, "schemaVersion" to "2", "steps" to steps) + extraProjection,
        )
    }

    private fun state(stepId: String, status: String, root: String? = null) = WorkflowStepStateRecord(
        namespace, workflowId, stepId, 3, status,
        buildMap {
            put("startedAt", "2026-01-01T00:00:00Z")
            put("completedAt", "2026-01-01T00:01:00Z")
            if (root != null) {
                put("cause", "dependency_non_success")
                put("rootStepId", root)
            }
        },
    )

    private val predecessor = DurableAgentAttempt(
        attemptId = "att-1", caseId = "case-A", namespaceId = namespace, workflowId = workflowId, stepId = "A",
        attemptNumber = 1, agentName = "PersonaA", brief = "brief att-1", contextEnvelope = "{}",
        status = AgentAttemptStatus.FAILED, failureCode = "FACTORY_STEP_RESULT_FAILED", revision = 3,
    )

    private val upserts = mutableListOf<WorkflowStepStateRecord>()
    private val updatedInstance = mutableListOf<WorkflowInstanceRecord>()
    private val published = mutableListOf<WorkflowProjectionRecord>()
    private val previousResultSlot = slot<FailedAttemptSummary>()
    private val contextSlot = slot<String>()
    private var routerCalls = 0

    private fun stubHappyPath(current: WorkflowInstanceRecord = instance(), latest: DurableAgentAttempt? = predecessor) {
        every { repository.findInstance(scope, namespace, workflowId) } returns current
        every { repository.findDefinition(scope, "wf-type", "1") } returns definition
        every { attempts.findLatestForStep(scope, namespace, workflowId, any()) } returns latest
        // No accepted structured result by default: the previous result comes from the evidence, or is unavailable.
        every { results.acceptedResult(scope, namespace, workflowId, any(), any()) } returns null
        every { evidenceRepository.list(scope, namespace, workflowId, "A") } returns emptyList()
        every {
            router.retryFailed(scope, namespace, workflowId, any(), any(), capture(previousResultSlot), capture(contextSlot), any())
        } answers {
            routerCalls++
            "successor-1"
        }
        every { repository.findStepStates(scope, namespace, workflowId) } returns listOf(
            state("A", WorkflowStatuses.FAILED),
            state("B", WorkflowStatuses.BLOCKED, root = "A"),
            state("C", WorkflowStatuses.BLOCKED, root = "A"),
            state("D", WorkflowStatuses.COMPLETED),
            state("F", WorkflowStatuses.FAILED),
            state("E", WorkflowStatuses.BLOCKED, root = "F"),
            state("H", WorkflowStatuses.COMPLETED),
        )
        every { repository.updateInstance(scope, namespace, workflowId, current.revision, capture(updatedInstance)) } returns true
        every { repository.upsertStepState(scope, capture(upserts)) } answers { arg(1) }
        every { repository.findProjection(scope, namespace, workflowId) } returns null
        every { repository.publishProjection(scope, capture(published), null) } returns ProjectionPublishResult.Conflict("unused")
        every { proxy.resolveRepoRoot(namespace, "alice") } returns "/repo"
        every { submissions.submit(scope, namespace, workflowId, "/repo", "retry-failed-step:successor-1", null) } returns
            SessionRunSubmissionService.SubmissionResult("sub-1", queued = true, idempotent = false, status = "pending")
    }

    private fun retry(
        stepId: String = "A",
        revision: Int = 7,
        context: String? = "Use the staging database.",
        key: String = "key-1",
        ns: String = namespace,
    ): WorkflowHttpResult = service.retryFailedStep(scope, ns, workflowId, stepId, revision, context, "alice", key)

    @Suppress("UNCHECKED_CAST")
    private fun data(result: WorkflowHttpResult) = result.data as Map<String, Any?>

    private fun errorCodeOf(block: () -> Unit): String? {
        val error = runCatching(block).exceptionOrNull()
        return (error as? WorkflowException)?.errorCode
    }

    // ------------------------------------------------------------------
    // retryFailedStep
    // ------------------------------------------------------------------

    @Test
    fun `a failed agent step of a failed run is retried as a successor attempt and the run is queued again`() {
        stubHappyPath()

        val result = retry()

        assertThat(result.status).isEqualTo(201)
        val body = data(result)
        assertThat(body["attemptId"]).isEqualTo("successor-1")
        assertThat(body["previousAttemptId"]).isEqualTo("att-1")
        assertThat(body["attemptNumber"]).isEqualTo(2)
        assertThat(body["idempotent"]).isEqualTo(false)
        assertThat(body["queued"]).isEqualTo(true)
        assertThat(body["submissionId"]).isEqualTo("sub-1")
        assertThat(body["revision"]).isEqualTo(8)
        assertThat(routerCalls).isEqualTo(1)
        assertThat(contextSlot.captured).isEqualTo("Use the staging database.")

        val next = updatedInstance.single()
        assertThat(next.revision).isEqualTo(8)
        assertThat(next.instance["status"]).isEqualTo(WorkflowStatuses.PENDING)
        val projected = (next.projection["steps"] as List<*>).filterIsInstance<Map<*, *>>().associate { it["id"] to it["status"] }
        // The failed step is re-armed; only the dependants blocked BECAUSE of it go back to pending.
        assertThat(projected["A"]).isEqualTo(WorkflowStatuses.READY)
        assertThat(projected["B"]).isEqualTo(WorkflowStatuses.PENDING)
        assertThat(projected["C"]).isEqualTo(WorkflowStatuses.PENDING)
        // E is blocked by the REAL failure of F: unchanged. D and F are untouched too.
        assertThat(projected["E"]).isEqualTo(WorkflowStatuses.BLOCKED)
        assertThat(projected["F"]).isEqualTo(WorkflowStatuses.FAILED)
        assertThat(projected["D"]).isEqualTo(WorkflowStatuses.COMPLETED)

        val persisted = upserts.associate { it.stepId to it.status }
        assertThat(persisted).containsEntry("A", WorkflowStatuses.READY)
            .containsEntry("B", WorkflowStatuses.PENDING)
            .containsEntry("C", WorkflowStatuses.PENDING)
            .doesNotContainKeys("D", "E", "F", "H")
        assertThat(upserts.first { it.stepId == "A" }.payload).doesNotContainKey("completedAt")

        assertThat(published.single().status).isEqualTo(WorkflowStatuses.PENDING)
        verify(exactly = 1) { submissions.submit(scope, namespace, workflowId, "/repo", "retry-failed-step:successor-1", null) }
    }

    @Test
    fun `the retry action is offered with the instance revision and the command accepts exactly that revision`() {
        stubHappyPath()
        every { repository.findProjection(scope, namespace, workflowId) } returns WorkflowProjectionRecord(
            namespace, workflowId, "2", 5, "hash", WorkflowStatuses.FAILED, instance().projection, instance().instance,
            "governed", "1", "hash-1", null, null, "active",
        )
        every { interactionRepository.list(scope, namespace, workflowId, true) } returns emptyList<HumanInteractionRecord>()
        every { attempts.findByWorkflow(scope, namespace, workflowId) } returns listOf(predecessor)
        every { evidenceRepository.list(scope, namespace, workflowId) } returns emptyList()
        every { proxy.getRunCost(any(), any()) } returns null

        val action = service.workflowActions(scope, namespace, workflowId, canReply = true, actorId = "reader")
            .allowedActions.single { it.type == WorkflowActionTypes.RETRY }

        // The projection row is at revision 5; the command compares the instance revision (7).
        assertThat(action.stepId).isEqualTo("A")
        assertThat(action.expectedRevision).isEqualTo(7)
        assertThat(retry(revision = action.expectedRevision).status).isEqualTo(201)
    }

    @Test
    fun `the predecessor attempt and the evidence of the previous result are never rewritten`() {
        stubHappyPath()

        retry()

        // Strict mocks expose only reads here: an append, finalize or supersede would have thrown.
        verify(exactly = 0) { evidenceRepository.append(any(), any(), any(), any()) }
        verify(exactly = 0) { attempts.finalize(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { attempts.supersede(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a replay with the same key returns the same successor without a second router call`() {
        stubHappyPath()
        val first = retry()

        val replay = retry()

        assertThat(replay.status).isEqualTo(200)
        assertThat(data(replay)["attemptId"]).isEqualTo(data(first)["attemptId"])
        assertThat(data(replay)["idempotent"]).isEqualTo(true)
        assertThat(routerCalls).isEqualTo(1)
        verify(exactly = 1) { submissions.submit(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `the same key with another payload is a collision and creates nothing`() {
        stubHappyPath()
        retry()

        assertThatThrownBy { retry(context = "Something else entirely") }
            .isInstanceOf(IdempotencyKeyCollisionException::class.java)
        assertThat(routerCalls).isEqualTo(1)
    }

    @Test
    fun `a stale revision is a conflict and nothing is stored`() {
        stubHappyPath()

        assertThat(errorCodeOf { retry(revision = 6) }).isEqualTo(WorkflowErrorCodes.REVISION_CONFLICT)

        assertThat(routerCalls).isEqualTo(0)
        assertThat(updatedInstance).isEmpty()
        assertThat(upserts).isEmpty()
        assertThat(idempotency.store).isEmpty()
        verify(exactly = 0) { submissions.submit(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a lost instance compare-and-set is a conflict and no replay entry is stored`() {
        stubHappyPath()
        every { repository.updateInstance(scope, namespace, workflowId, 7, any()) } returns false

        assertThat(errorCodeOf { retry() }).isEqualTo(WorkflowErrorCodes.REVISION_CONFLICT)

        assertThat(idempotency.store).isEmpty()
        verify(exactly = 0) { submissions.submit(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a completed or cancelled run stays sealed and nothing is mutated`() {
        listOf(WorkflowStatuses.COMPLETED, WorkflowStatuses.CANCELLED).forEachIndexed { index, status ->
            stubHappyPath(instance(runStatus = status))

            assertThat(errorCodeOf { retry(key = "key-$index") }).isEqualTo(WorkflowErrorCodes.WORKFLOW_SEALED)
        }
        assertThat(routerCalls).isEqualTo(0)
        assertThat(updatedInstance).isEmpty()
        assertThat(upserts).isEmpty()
    }

    @Test
    fun `a run that is still active is never reopened, even when one of its agent steps failed`() {
        // D is independent and still running while A has failed: the sequencer has not marked the run failed yet.
        listOf(WorkflowStatuses.RUNNING, WorkflowStatuses.PENDING, WorkflowStatuses.READY, WorkflowStatuses.WAITING_HUMAN)
            .forEachIndexed { index, status ->
                stubHappyPath(instance(runStatus = status))

                assertThat(errorCodeOf { retry(key = "key-active-$index") }).isEqualTo(WorkflowErrorCodes.INTERACTION_STALE)
            }
        assertThat(routerCalls).isEqualTo(0)
        assertThat(updatedInstance).isEmpty()
        assertThat(upserts).isEmpty()
        assertThat(idempotency.store).isEmpty()
        verify(exactly = 0) { submissions.submit(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a code step a human step and a step that did not fail are refused`() {
        stubHappyPath(instance(statuses = defaultStatuses + ("C" to WorkflowStatuses.FAILED) + ("H" to WorkflowStatuses.FAILED)))

        assertThat(errorCodeOf { retry(stepId = "C", key = "k-code") }).isEqualTo(WorkflowErrorCodes.ACTION_NOT_ALLOWED)
        assertThat(errorCodeOf { retry(stepId = "H", key = "k-human") }).isEqualTo(WorkflowErrorCodes.ACTION_NOT_ALLOWED)
        // D is an agent step but completed: not retryable.
        assertThat(errorCodeOf { retry(stepId = "D", key = "k-done") }).isEqualTo(WorkflowErrorCodes.INTERACTION_STALE)
        assertThat(routerCalls).isEqualTo(0)
    }

    @Test
    fun `an outcome-managed run and a parent Run fragment are refused`() {
        stubHappyPath(instance(extraProjection = mapOf("stepExecutions" to emptyList<Any>())))
        assertThat(errorCodeOf { retry(key = "k-outcome") }).isEqualTo(WorkflowErrorCodes.ACTION_NOT_ALLOWED)

        stubHappyPath(instance(extraInstance = mapOf("runId" to "run-1")))
        assertThat(errorCodeOf { retry(key = "k-fragment") }).isEqualTo(WorkflowErrorCodes.ACTION_NOT_ALLOWED)

        stubHappyPath(instance(extraInstance = mapOf("relations" to mapOf("runId" to "run-1"))))
        assertThat(errorCodeOf { retry(key = "k-relation") }).isEqualTo(WorkflowErrorCodes.ACTION_NOT_ALLOWED)
        assertThat(routerCalls).isEqualTo(0)
    }

    @Test
    fun `the optional context accepts absent blank and 4000 characters and rejects 4001`() {
        stubHappyPath()

        assertThat(retry(context = null, key = "k-null").status).isEqualTo(201)
        assertThat(contextSlot.captured).isEmpty()
        assertThat(retry(context = "   ", key = "k-blank").status).isEqualTo(201)
        assertThat(contextSlot.captured).isEmpty()
        assertThat(retry(context = "x".repeat(4_000), key = "k-max").status).isEqualTo(201)
        assertThat(errorCodeOf { retry(context = "x".repeat(4_001), key = "k-over") }).isEqualTo(WorkflowErrorCodes.INVALID_REQUEST)
    }

    @Test
    fun `a latest attempt that is not finished or has no frozen context is refused`() {
        stubHappyPath(latest = predecessor.copy(status = AgentAttemptStatus.RUNNING))
        assertThat(errorCodeOf { retry(key = "k-running") }).isEqualTo(WorkflowErrorCodes.INTERACTION_STALE)

        stubHappyPath(latest = predecessor.copy(status = AgentAttemptStatus.SUPERSEDED))
        assertThat(errorCodeOf { retry(key = "k-superseded") }).isEqualTo(WorkflowErrorCodes.INTERACTION_STALE)

        stubHappyPath(latest = predecessor.copy(contextEnvelope = null))
        assertThat(errorCodeOf { retry(key = "k-legacy") }).isEqualTo(WorkflowErrorCodes.INTERACTION_STALE)

        stubHappyPath(latest = null)
        assertThat(errorCodeOf { retry(key = "k-none") }).isEqualTo(WorkflowErrorCodes.INTERACTION_STALE)
        assertThat(routerCalls).isEqualTo(0)
    }

    @Test
    fun `an unresolvable repository root refuses the whole retry before any write instead of guessing a directory`() {
        stubHappyPath()
        every { proxy.resolveRepoRoot(namespace, "alice") } returns null

        assertThat(errorCodeOf { retry() }).isEqualTo(WorkflowErrorCodes.INVALID_REQUEST)

        // The root is a pure read resolved first: no successor, no audit, no occupation transfer, no CAS.
        assertThat(routerCalls).isEqualTo(0)
        assertThat(updatedInstance).isEmpty()
        assertThat(upserts).isEmpty()
        assertThat(idempotency.store).isEmpty()
        verify(exactly = 0) { submissions.submit(any(), any(), any(), any(), any(), any()) }
    }

    // ------------------------------------------------------------------
    // previous result handed to the router
    // ------------------------------------------------------------------

    @Test
    fun `the accepted structured result is the previous result when there is one`() {
        stubHappyPath()
        val accepted = mockk<AgentStepResultSubmitted>()
        every { accepted.resultId } returns "result-1"
        every { accepted.summary } returns "The build is red"
        every { accepted.findings } returns listOf(AgentStepResultFinding("error", "BUILD", "does not compile"))
        every { results.acceptedResult(scope, namespace, workflowId, "A", "att-1") } returns accepted

        retry()

        assertThat(previousResultSlot.captured.resultId).isEqualTo("result-1")
        assertThat(previousResultSlot.captured.summary).isEqualTo("The build is red")
        assertThat(previousResultSlot.captured.failureCode).isEqualTo("FACTORY_STEP_RESULT_FAILED")
        assertThat(previousResultSlot.captured.findings).hasSize(1)
    }

    @Test
    fun `without an accepted result the failure code and message of the evidence are used`() {
        stubHappyPath()
        every { evidenceRepository.list(scope, namespace, workflowId, "A") } returns listOf(
            WorkflowEvidenceItem(
                "ev-1", namespace, workflowId, "A", CapabilityExecutionService.AGENT_RESULT_EVIDENCE_KIND, "fail", null,
                mapOf("attemptId" to "att-1", "code" to "AGENT_CASE_ERROR", "message" to "the case crashed"), null, null,
            ),
        )

        retry()

        assertThat(previousResultSlot.captured.available).isTrue()
        assertThat(previousResultSlot.captured.summary).isEqualTo("AGENT_CASE_ERROR: the case crashed")
    }

    @Test
    fun `when nothing can be retrieved the retry still runs and the result is flagged unavailable`() {
        stubHappyPath(latest = predecessor.copy(failureCode = null))

        val result = retry()

        assertThat(result.status).isEqualTo(201)
        assertThat(previousResultSlot.captured.available).isFalse()
        assertThat(previousResultSlot.captured.summary).isNull()
    }

    // ------------------------------------------------------------------
    // allowed action
    // ------------------------------------------------------------------

    private fun actionsFor(
        current: WorkflowInstanceRecord,
        attemptList: List<DurableAgentAttempt> = listOf(predecessor),
    ): List<String?> {
        every { repository.findInstance(scope, namespace, workflowId) } returns current
        every { repository.findDefinition(scope, "wf-type", "1") } returns definition
        every { repository.findProjection(scope, namespace, workflowId) } returns WorkflowProjectionRecord(
            namespace, workflowId, "2", 5, "hash", current.instance["status"] as String, current.projection, current.instance,
            "governed", "1", "hash-1", null, null, "active",
        )
        every { interactionRepository.list(scope, namespace, workflowId, true) } returns emptyList<HumanInteractionRecord>()
        every { attempts.findByWorkflow(scope, namespace, workflowId) } returns attemptList
        every { evidenceRepository.list(scope, namespace, workflowId) } returns emptyList()
        every { proxy.getRunCost(any(), any()) } returns null
        return service.workflowActions(scope, namespace, workflowId, canReply = true, actorId = "reader")
            .allowedActions.filter { it.type == WorkflowActionTypes.RETRY }.map { it.stepId }
    }

    @Test
    fun `retry is offered only for the failed agent steps of a failed run`() {
        val failedAttemptOfF = predecessor.copy(attemptId = "att-f", stepId = "F", caseId = "case-F")

        // A and F are failed agent steps; B, C and E are blocked consequences and offer nothing.
        assertThat(actionsFor(instance(), listOf(predecessor, failedAttemptOfF))).containsExactlyInAnyOrder("A", "F")
    }

    @Test
    fun `retry is not offered for a failed code or human step`() {
        val onlyCodeAndHumanFailed = mapOf(
            "A" to WorkflowStatuses.COMPLETED, "B" to WorkflowStatuses.COMPLETED, "C" to WorkflowStatuses.FAILED,
            "D" to WorkflowStatuses.COMPLETED, "F" to WorkflowStatuses.COMPLETED, "E" to WorkflowStatuses.COMPLETED,
            "H" to WorkflowStatuses.FAILED,
        )

        assertThat(actionsFor(instance(statuses = onlyCodeAndHumanFailed))).isEmpty()
    }

    @Test
    fun `retry is not offered on a cancelled or completed run, an outcome-managed run or a fragment`() {
        assertThat(actionsFor(instance(runStatus = WorkflowStatuses.CANCELLED))).isEmpty()
        assertThat(actionsFor(instance(runStatus = WorkflowStatuses.COMPLETED))).isEmpty()
        assertThat(actionsFor(instance(extraProjection = mapOf("stepExecutions" to emptyList<Any>())))).isEmpty()
        assertThat(actionsFor(instance(extraInstance = mapOf("runId" to "run-1")))).isEmpty()
    }

    @Test
    fun `retry is not offered on a run that is still active even when an agent step failed`() {
        listOf(WorkflowStatuses.RUNNING, WorkflowStatuses.PENDING, WorkflowStatuses.WAITING_HUMAN).forEach { status ->
            assertThat(actionsFor(instance(runStatus = status))).describedAs("run status $status").isEmpty()
        }
    }

    @Test
    fun `retry is not offered while the latest attempt is not finished or when there is none, but the blocker stays`() {
        assertThat(actionsFor(instance(), listOf(predecessor.copy(status = AgentAttemptStatus.RUNNING)))).isEmpty()
        assertThat(actionsFor(instance(), emptyList())).isEmpty()

        val blockers = service.workflowActions(scope, namespace, workflowId, canReply = true, actorId = "reader").blockers
        assertThat(blockers.map { it.code }).contains(WorkflowBlockerCodes.ATTEMPT_FAILED)
    }
}
