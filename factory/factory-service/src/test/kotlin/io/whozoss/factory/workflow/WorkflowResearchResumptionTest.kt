package io.whozoss.factory.workflow

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.factory.agentattempt.domain.AgentAttemptStatus
import io.whozoss.factory.agentattempt.domain.AgentStepResultStatus
import io.whozoss.factory.agentattempt.domain.AgentStepResultSubmitted
import io.whozoss.factory.agentattempt.domain.DurableAgentAttempt
import io.whozoss.factory.agentattempt.domain.IdempotencyKeyCollisionException
import io.whozoss.factory.agentattempt.persistence.IdempotencyRecord
import io.whozoss.factory.agentattempt.persistence.IdempotencyRepository
import io.whozoss.factory.agentattempt.service.AgentStepResultService
import io.whozoss.factory.agentattempt.service.DurableAgentAttemptService
import io.whozoss.factory.capability.NeedsResearchRouter
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.proxy.AgentOsProxyClient
import io.whozoss.factory.workflow.domain.HumanInteractionRecord
import io.whozoss.factory.workflow.domain.WorkflowActionTypes
import io.whozoss.factory.workflow.domain.WorkflowBlockerCodes
import io.whozoss.factory.workflow.domain.WorkflowDefinitionRecord
import io.whozoss.factory.workflow.domain.WorkflowException
import io.whozoss.factory.workflow.domain.WorkflowEvidenceItem
import io.whozoss.factory.workflow.domain.WorkflowInstanceRecord
import io.whozoss.factory.workflow.domain.WorkflowProjectionRecord
import io.whozoss.factory.workflow.domain.WorkflowStatuses
import io.whozoss.factory.workflow.domain.WorkflowStepStateRecord
import io.whozoss.factory.workflow.persistence.HumanInteractionRepository
import io.whozoss.factory.workflow.persistence.ProjectionPublishResult
import io.whozoss.factory.workflow.persistence.WorkflowEvidenceRepository
import io.whozoss.factory.workflow.persistence.WorkflowRepository
import io.whozoss.factory.workflow.service.SessionRunSubmissionService
import io.whozoss.factory.workflow.service.WorkflowService
import io.whozoss.factory.workflow.sse.WorkflowSseHub
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Unit tests of the governed `resume_research` command and its allowed action.
 *
 * DAG used: `A` (needs_research) <- `B` <- `C`; `E` depends on `A` AND on `F`, and `F` genuinely failed;
 * `D` is independent. Blocked descendants of `A` are re-armed; `E` stays blocked because of the REAL failure.
 */
class WorkflowResearchResumptionTest {

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
                step("D"), step("F"), step("E", "A", "F"),
            ),
        ),
    )

    private fun instance(runStatus: String = WorkflowStatuses.NEEDS_RESEARCH, revision: Int = 7, aStatus: String = WorkflowStatuses.NEEDS_RESEARCH): WorkflowInstanceRecord {
        val steps = listOf(
            mapOf("id" to "A", "status" to aStatus),
            mapOf("id" to "B", "status" to WorkflowStatuses.BLOCKED),
            mapOf("id" to "C", "status" to WorkflowStatuses.BLOCKED),
            mapOf("id" to "D", "status" to WorkflowStatuses.COMPLETED),
            mapOf("id" to "F", "status" to WorkflowStatuses.FAILED),
            mapOf("id" to "E", "status" to WorkflowStatuses.BLOCKED),
        )
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
            ),
            projection = mapOf("status" to runStatus, "schemaVersion" to "2", "steps" to steps),
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
        status = AgentAttemptStatus.INDETERMINATE, failureCode = "AGENT_NEEDS_RESEARCH", revision = 3,
    )

    private val proof = WorkflowEvidenceItem(
        "proof-1", namespace, workflowId, "A", NeedsResearchRouter.NEEDS_RESEARCH_EVIDENCE_KIND, "needs_research",
        mapOf("persona" to "PersonaA", "attemptId" to "att-1"),
        mapOf("attemptId" to "att-1", "resultId" to "result-1", "summary" to "no repo access"), null, null,
    )

    private val upserts = mutableListOf<WorkflowStepStateRecord>()
    private val updatedInstance = mutableListOf<WorkflowInstanceRecord>()
    private val published = mutableListOf<WorkflowProjectionRecord>()
    private var routerCalls = 0

    private fun stubHappyPath(current: WorkflowInstanceRecord = instance()) {
        every { repository.findInstance(scope, namespace, workflowId) } returns current
        every { repository.findDefinition(scope, "wf-type", "1") } returns definition
        every { attempts.findLatestForStep(scope, namespace, workflowId, "A") } returns predecessor
        val accepted = mockk<AgentStepResultSubmitted>()
        every { accepted.status } returns AgentStepResultStatus.NEEDS_RESEARCH
        every { accepted.resultId } returns "result-1"
        every { results.acceptedResult(scope, namespace, workflowId, "A", "att-1") } returns accepted
        every { evidenceRepository.list(scope, namespace, workflowId, "A") } returns listOf(proof)
        every { router.resume(scope, namespace, workflowId, any(), any(), any(), any()) } answers {
            routerCalls++
            "successor-1"
        }
        every { repository.findStepStates(scope, namespace, workflowId) } returns listOf(
            state("A", WorkflowStatuses.NEEDS_RESEARCH),
            state("B", WorkflowStatuses.BLOCKED, root = "A"),
            state("C", WorkflowStatuses.BLOCKED, root = "A"),
            state("D", WorkflowStatuses.COMPLETED),
            state("F", WorkflowStatuses.FAILED),
            state("E", WorkflowStatuses.BLOCKED, root = "F"),
        )
        every { repository.updateInstance(scope, namespace, workflowId, current.revision, capture(updatedInstance)) } returns true
        every { repository.upsertStepState(scope, capture(upserts)) } answers { arg(1) }
        every { repository.findProjection(scope, namespace, workflowId) } returns null
        every { repository.publishProjection(scope, capture(published), null) } returns ProjectionPublishResult.Conflict("unused")
        every { proxy.resolveRepoRoot(namespace, "alice") } returns "/repo"
        every { submissions.submit(scope, namespace, workflowId, "/repo", "resume-research:successor-1", null) } returns
            SessionRunSubmissionService.SubmissionResult("sub-1", queued = true, idempotent = false, status = "pending")
    }

    private fun resume(
        revision: Int = 7,
        context: String = "Repository access fixed.",
        key: String = "key-1",
        ns: String = namespace,
    ) = service.resumeResearch(scope, ns, workflowId, "A", revision, context, "alice", key)

    @Suppress("UNCHECKED_CAST")
    private fun data(result: io.whozoss.factory.workflow.service.WorkflowHttpResult) = result.data as Map<String, Any?>

    // ------------------------------------------------------------------
    // resumeResearch
    // ------------------------------------------------------------------

    @Test
    fun `a resumption creates the successor, re-arms the step and queues a durable run`() {
        stubHappyPath()

        val result = resume()

        assertThat(result.status).isEqualTo(201)
        val body = data(result)
        assertThat(body["attemptId"]).isEqualTo("successor-1")
        assertThat(body["idempotent"]).isEqualTo(false)
        assertThat(body["queued"]).isEqualTo(true)
        assertThat(body["submissionId"]).isEqualTo("sub-1")
        assertThat(body["revision"]).isEqualTo(8)
        assertThat(routerCalls).isEqualTo(1)

        // The instance is CAS-updated at the caller's revision: the step is ready, the run pending.
        val next = updatedInstance.single()
        assertThat(next.revision).isEqualTo(8)
        assertThat(next.instance["status"]).isEqualTo(WorkflowStatuses.PENDING)
        val projected = (next.projection["steps"] as List<*>).filterIsInstance<Map<*, *>>().associate { it["id"] to it["status"] }
        assertThat(projected["A"]).isEqualTo(WorkflowStatuses.READY)
        // Only descendants blocked BECAUSE of A are re-armed.
        assertThat(projected["B"]).isEqualTo(WorkflowStatuses.PENDING)
        assertThat(projected["C"]).isEqualTo(WorkflowStatuses.PENDING)
        assertThat(projected["E"]).isEqualTo(WorkflowStatuses.BLOCKED)
        assertThat(projected["F"]).isEqualTo(WorkflowStatuses.FAILED)
        assertThat(projected["D"]).isEqualTo(WorkflowStatuses.COMPLETED)

        // The durable per-step states agree with the projection (what the sequencer actually reads).
        val persisted = upserts.associate { it.stepId to it.status }
        assertThat(persisted).containsEntry("A", WorkflowStatuses.READY)
            .containsEntry("B", WorkflowStatuses.PENDING)
            .containsEntry("C", WorkflowStatuses.PENDING)
            .doesNotContainKeys("D", "E", "F")
        assertThat(upserts.first { it.stepId == "A" }.payload).doesNotContainKey("completedAt")

        // The read model is published with the new status, and a run is durably queued for the successor.
        assertThat(published.single().status).isEqualTo(WorkflowStatuses.PENDING)
        verify(exactly = 1) { submissions.submit(scope, namespace, workflowId, "/repo", "resume-research:successor-1", null) }
    }

    @Test
    fun `the previous result, its proof and the predecessor attempt are never rewritten`() {
        stubHappyPath()

        resume()

        // evidenceRepository / attempts are strict mocks exposing only reads here: any append, finalize or
        // supersede of the earlier result would have thrown. Only the router (successor registration) writes.
        verify(exactly = 0) { evidenceRepository.append(any(), any(), any(), any()) }
        verify(exactly = 0) { attempts.finalize(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a replay with the same key and payload returns the same successor without a second attempt`() {
        stubHappyPath()
        val first = resume()

        val replay = resume()

        assertThat(replay.status).isEqualTo(200)
        assertThat(data(replay)["attemptId"]).isEqualTo(data(first)["attemptId"])
        assertThat(data(replay)["idempotent"]).isEqualTo(true)
        assertThat(routerCalls).isEqualTo(1)
        verify(exactly = 1) { submissions.submit(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `the same key with a different payload is a collision and creates nothing`() {
        stubHappyPath()
        resume()

        assertThatThrownBy { resume(context = "Something else entirely") }
            .isInstanceOf(IdempotencyKeyCollisionException::class.java)
        assertThat(routerCalls).isEqualTo(1)
    }

    @Test
    fun `the same client key in another namespace never aliases the first resumption`() {
        stubHappyPath()
        resume()
        val other = "ns-2"
        every { repository.findInstance(scope, other, workflowId) } returns null

        assertThatThrownBy { resume(ns = other) }
            .isInstanceOf(WorkflowException::class.java)
        assertThat(routerCalls).isEqualTo(1)
        assertThat(idempotency.store.keys).hasSize(1)
    }

    @Test
    fun `a stale revision is a conflict and mutates nothing`() {
        stubHappyPath()

        assertThatThrownBy { resume(revision = 6) }
            .isInstanceOf(WorkflowException::class.java)
            .hasMessageContaining("stale")
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

        assertThatThrownBy { resume() }
            .isInstanceOf(WorkflowException::class.java)
            .hasMessageContaining("stale")
        assertThat(idempotency.store).isEmpty()
        verify(exactly = 0) { submissions.submit(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a terminal sealed run is never reopened`() {
        stubHappyPath(instance(runStatus = WorkflowStatuses.FAILED))

        assertThatThrownBy { resume() }
            .isInstanceOf(WorkflowException::class.java)
            .hasMessageContaining("sealed")
        assertThat(routerCalls).isEqualTo(0)
    }

    @Test
    fun `only a NEEDS_RESEARCH step can be resumed`() {
        stubHappyPath(instance(aStatus = WorkflowStatuses.FAILED))

        assertThatThrownBy { resume() }
            .isInstanceOf(WorkflowException::class.java)
            .hasMessageContaining("NEEDS_RESEARCH")
        assertThat(routerCalls).isEqualTo(0)
    }

    @Test
    fun `a predecessor that did not end with NEEDS_RESEARCH is refused`() {
        stubHappyPath()
        every { attempts.findLatestForStep(scope, namespace, workflowId, "A") } returns
            predecessor.copy(failureCode = "AGENT_INDETERMINATE")

        assertThatThrownBy { resume() }.isInstanceOf(WorkflowException::class.java)
        assertThat(routerCalls).isEqualTo(0)
    }

    @Test
    fun `an accepted result that is not NEEDS_RESEARCH is refused`() {
        stubHappyPath()
        val accepted = mockk<AgentStepResultSubmitted>()
        every { accepted.status } returns AgentStepResultStatus.PASS
        every { results.acceptedResult(scope, namespace, workflowId, "A", "att-1") } returns accepted

        assertThatThrownBy { resume() }.isInstanceOf(WorkflowException::class.java)
        assertThat(routerCalls).isEqualTo(0)
    }

    @Test
    fun `an ambiguous or missing proof is refused rather than guessed`() {
        stubHappyPath()
        every { evidenceRepository.list(scope, namespace, workflowId, "A") } returns listOf(proof, proof.copy(evidenceId = "proof-2"))
        assertThatThrownBy { resume() }.isInstanceOf(WorkflowException::class.java)

        every { evidenceRepository.list(scope, namespace, workflowId, "A") } returns emptyList()
        assertThatThrownBy { resume(key = "key-2") }.isInstanceOf(WorkflowException::class.java)
        assertThat(routerCalls).isEqualTo(0)
    }

    @Test
    fun `a legacy predecessor without a frozen context cannot be resumed`() {
        stubHappyPath()
        every { attempts.findLatestForStep(scope, namespace, workflowId, "A") } returns predecessor.copy(contextEnvelope = null)

        assertThatThrownBy { resume() }.isInstanceOf(WorkflowException::class.java)
        assertThat(routerCalls).isEqualTo(0)
    }

    @Test
    fun `the supplemental context is bounded and mandatory`() {
        stubHappyPath()

        assertThatThrownBy { resume(context = "   ") }.isInstanceOf(WorkflowException::class.java)
        assertThatThrownBy { resume(context = "x".repeat(4_001), key = "key-2") }.isInstanceOf(WorkflowException::class.java)
        assertThat(routerCalls).isEqualTo(0)
        assertThat(resume(context = "x".repeat(4_000), key = "key-3").status).isEqualTo(201)
    }

    @Test
    fun `an unresolvable repository root refuses the whole resumption instead of guessing a directory`() {
        stubHappyPath()
        every { proxy.resolveRepoRoot(namespace, "alice") } returns null

        assertThatThrownBy { resume() }
            .isInstanceOf(WorkflowException::class.java)
            .hasMessageContaining("repository root")
        verify(exactly = 0) { submissions.submit(any(), any(), any(), any(), any(), any()) }
        assertThat(idempotency.store).isEmpty()
    }

    // ------------------------------------------------------------------
    // allowed action + blockers
    // ------------------------------------------------------------------

    private fun stubActions(current: WorkflowInstanceRecord, attemptList: List<DurableAgentAttempt>) {
        every { repository.findInstance(scope, namespace, workflowId) } returns current
        every { repository.findProjection(scope, namespace, workflowId) } returns WorkflowProjectionRecord(
            namespace, workflowId, "2", 5, "hash", current.instance["status"] as String, current.projection, current.instance,
            "governed", "1", "hash-1", null, null, "active",
        )
        every { interactionRepository.list(scope, namespace, workflowId, true) } returns emptyList<HumanInteractionRecord>()
        every { attempts.findByWorkflow(scope, namespace, workflowId) } returns attemptList
        every { evidenceRepository.list(scope, namespace, workflowId) } returns emptyList()
        every { proxy.getRunCost(any(), any()) } returns null
    }

    @Test
    fun `the action is fenced on the instance revision and the blocker is distinct from UNKNOWN_RUNTIME`() {
        stubActions(instance(revision = 7), listOf(predecessor))

        val response = service.workflowActions(scope, namespace, workflowId, canReply = true)

        val action = response.allowedActions.single { it.type == WorkflowActionTypes.RESUME_RESEARCH }
        assertThat(action.stepId).isEqualTo("A")
        // The projection row is at revision 5; resumeResearch compares the instance revision (7).
        assertThat(action.expectedRevision).isEqualTo(7)
        assertThat(response.blockers.map { it.code }).contains(WorkflowBlockerCodes.NEEDS_RESEARCH)
            .doesNotContain(WorkflowBlockerCodes.UNKNOWN_RUNTIME)
        // The terminal predecessor attempt is not cancellable.
        assertThat(response.allowedActions.map { it.type }).doesNotContain(WorkflowActionTypes.CANCEL_ATTEMPT)
    }

    @Test
    fun `a genuinely indeterminate attempt stays UNKNOWN_RUNTIME and exposes no resumption`() {
        val unknown = predecessor.copy(failureCode = "AGENT_INDETERMINATE")
        stubActions(instance(aStatus = WorkflowStatuses.FAILED), listOf(unknown))

        val response = service.workflowActions(scope, namespace, workflowId, canReply = true)

        assertThat(response.blockers.map { it.code }).contains(WorkflowBlockerCodes.UNKNOWN_RUNTIME)
            .doesNotContain(WorkflowBlockerCodes.NEEDS_RESEARCH)
        assertThat(response.allowedActions.map { it.type }).doesNotContain(WorkflowActionTypes.RESUME_RESEARCH)
    }

    @Test
    fun `a sealed run keeps the blocker visible but offers no resumption`() {
        stubActions(instance(runStatus = WorkflowStatuses.FAILED), listOf(predecessor))

        val response = service.workflowActions(scope, namespace, workflowId, canReply = true)

        assertThat(response.blockers.map { it.code }).contains(WorkflowBlockerCodes.NEEDS_RESEARCH)
        assertThat(response.allowedActions.map { it.type }).doesNotContain(WorkflowActionTypes.RESUME_RESEARCH)
    }

    @Test
    fun `an older NEEDS_RESEARCH attempt stops blocking once a newer attempt exists`() {
        val successor = predecessor.copy(
            attemptId = "att-2", attemptNumber = 2, status = AgentAttemptStatus.RUNNING, failureCode = null, revision = 2,
        )
        stubActions(instance(aStatus = WorkflowStatuses.READY), listOf(predecessor, successor))

        val response = service.workflowActions(scope, namespace, workflowId, canReply = true)

        assertThat(response.blockers.map { it.code }).doesNotContain(WorkflowBlockerCodes.NEEDS_RESEARCH)
        assertThat(response.allowedActions.map { it.type }).doesNotContain(WorkflowActionTypes.RESUME_RESEARCH)
        assertThat(response.allowedActions.single { it.type == WorkflowActionTypes.CANCEL_ATTEMPT }.attemptId).isEqualTo("att-2")
    }
}
