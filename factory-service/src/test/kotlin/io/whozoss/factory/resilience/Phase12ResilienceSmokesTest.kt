package io.whozoss.factory.resilience

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.adapter.agentos.AgentOsExecutionAdapter
import io.whozoss.factory.adapter.agentos.AgentOsExecutionVerdict
import io.whozoss.factory.adapter.agentos.CaseEventView
import io.whozoss.factory.adapter.agentos.CaseHandle
import io.whozoss.factory.adapter.agentos.VerdictDeriver
import io.whozoss.factory.agentattempt.domain.AgentAttemptStatus
import io.whozoss.factory.agentattempt.domain.AgentStepAttemptRecord
import io.whozoss.factory.agentattempt.domain.AgentStepResultCapabilityIdentity
import io.whozoss.factory.agentattempt.domain.AgentStepResultObservedIdentity
import io.whozoss.factory.agentattempt.domain.DurableAgentAttempt
import io.whozoss.factory.agentattempt.domain.IdempotencyKeyCollisionException
import io.whozoss.factory.agentattempt.domain.QuestionAlreadyAnsweredException
import io.whozoss.factory.agentattempt.domain.ResultSemanticCollisionException
import io.whozoss.factory.agentattempt.persistence.AgentStepAttemptRepository
import io.whozoss.factory.agentattempt.persistence.SpringDataNeo4jAgentStepResultRepository
import io.whozoss.factory.agentattempt.persistence.SpringDataNeo4jOutboxRepository
import io.whozoss.factory.agentattempt.service.AgentStepQuestionService
import io.whozoss.factory.agentattempt.service.AgentStepResultService
import io.whozoss.factory.agentattempt.service.BridgeRecoveryWorker
import io.whozoss.factory.agentattempt.service.DurableAgentAttemptService
import io.whozoss.factory.capability.CapabilityExecutionService
import io.whozoss.factory.error.BadRequestException
import io.whozoss.factory.error.FactoryException
import io.whozoss.factory.error.RevisionConflictException
import io.whozoss.factory.planchange.domain.DependencyChange
import io.whozoss.factory.planchange.domain.DependencyOp
import io.whozoss.factory.planchange.domain.PlanChangeDecideCommand
import io.whozoss.factory.planchange.domain.PlanChangeDecisionStatus
import io.whozoss.factory.planchange.domain.PlanChangeKind
import io.whozoss.factory.planchange.domain.PlanChangeProposalType
import io.whozoss.factory.planchange.domain.PlanChangeSubmitCommand
import io.whozoss.factory.planchange.persistence.Neo4jPlanChangeProposalRepository
import io.whozoss.factory.planchange.service.PlanChangeProposalService
import io.whozoss.factory.web.FactoryCaller
import io.whozoss.factory.workflow.domain.ControllerExecutionInput
import io.whozoss.factory.workflow.domain.HumanInteractionRecord
import io.whozoss.factory.workflow.domain.WorkflowDefinitionRecord
import io.whozoss.factory.workflow.domain.WorkflowDefinitionValidation
import io.whozoss.factory.workflow.domain.WorkflowDefinitionValidator
import io.whozoss.factory.workflow.domain.WorkflowErrorCodes
import io.whozoss.factory.workflow.domain.WorkflowException
import io.whozoss.factory.workflow.domain.WorkflowExecution
import io.whozoss.factory.workflow.domain.WorkflowStartCommand
import io.whozoss.factory.workflow.domain.hashWorkflowDefinition
import io.whozoss.factory.workflow.persistence.HumanInteractionRepository
import io.whozoss.factory.workflow.persistence.WorkflowRepository
import io.whozoss.factory.workflow.service.WorkflowService
import io.whozoss.factory.workstream.ControllerCaseService
import io.whozoss.factory.workstream.WorkstreamService
import io.whozoss.factory.workstream.domain.ControllerCaseBounds
import io.whozoss.factory.workstream.projection.WorkstreamProjectionService
import io.whozoss.factory.workstream.web.CompactControllerCaseRequest
import io.whozoss.factory.workstream.web.CreateWorkstreamRequest
import io.whozoss.factory.workstream.web.StartControllerCaseRequest
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Phase 12 — resilience and invariant end-to-end smokes (Phases 0–11).
 *
 * Each smoke drives the REAL Spring beans against the embedded Neo4j harness
 * (plus a stateful fake [AgentOsExecutionAdapter] for the AgentOS boundary) and
 * asserts ONE named safety invariant under a simulated failure/stress scenario:
 *
 *  1. (P2/P3) a factory restart mid-attempt recovers by clarifying the state —
 *     never by inferring a terminal outcome;
 *  2. (P3) an unreachable AgentOS runtime is classified `Indeterminate`, NEVER
 *     `Succeeded`/pass;
 *  3. (P1/P10) late and duplicate step-result submissions are idempotent and
 *     leave the recorded verdict unchanged;
 *  4. (P4) an unanswered human question followed by a duplicate answer never
 *     unblocks twice — attempt N is superseded and N+1 stays unique;
 *  5. (P9) a controller case renewal keeps the controller/interlocutor identity
 *     stable with a bounded recovery packet;
 *  6. (P8) an incompatible plan change proposal is rejected by the governance
 *     gate — no silent rewrite;
 *  7. (P10) a terminal workflow instance is sealed — late messages/events never
 *     reopen it;
 *  8. (P6/P7) the workstream read-only answers are correct and non-mutating,
 *     and a command-driven gate opens a governance gate that cannot be bypassed.
 *
 * Scenarios whose dependencies are not built yet (writable worker in an
 * isolated WorkUnitEnvironment, real Factory oracle after the worker, full
 * BMAD/Forge workflow) are recorded as [@Disabled] smokes with the missing
 * dependency documented, so the gap is visible and intentional.
 *
 * This class is a plain [Neo4jDomainIntegrationTest] subclass (no extra
 * `@TestConfiguration`/`@MockBean`) so Spring Test reuses the ONE cached
 * context shared by every Neo4j integration test.
 */
class Phase12ResilienceSmokesTest : Neo4jDomainIntegrationTest() {

    @Autowired
    private lateinit var attemptService: DurableAgentAttemptService

    @Autowired
    private lateinit var resultService: AgentStepResultService

    @Autowired
    private lateinit var questionService: AgentStepQuestionService

    @Autowired
    private lateinit var legacyAttempts: AgentStepAttemptRepository

    @Autowired
    private lateinit var resultNodes: SpringDataNeo4jAgentStepResultRepository

    @Autowired
    private lateinit var outboxNodes: SpringDataNeo4jOutboxRepository

    @Autowired
    private lateinit var interactions: HumanInteractionRepository

    @Autowired
    private lateinit var workflowService: WorkflowService

    @Autowired
    private lateinit var workflowRepository: WorkflowRepository

    @Autowired
    private lateinit var workstreamService: WorkstreamService

    @Autowired
    private lateinit var controllerCaseService: ControllerCaseService

    @Autowired
    private lateinit var projectionService: WorkstreamProjectionService

    @Autowired
    private lateinit var planChangeService: PlanChangeProposalService

    @Autowired
    private lateinit var planChangeRepository: Neo4jPlanChangeProposalRepository

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    private val caller: FactoryCaller
        get() = FactoryCaller(scope, "p12-tester", "p12-tester", "p12-tester")

    // ------------------------------------------------------------------
    // Stateful fake of the AgentOS execution boundary
    // ------------------------------------------------------------------

    /**
     * Records every turn dispatch and answers reconcile/observe with the
     * verdicts supplied by the test — a crashed prior run is simulated by
     * seeding durable attempts with an already-expired lease.
     */
    private class FakeAdapter(
        private val reconcileVerdict: (String) -> AgentOsExecutionVerdict,
        private val observeVerdict: (String) -> AgentOsExecutionVerdict = { caseId ->
            error("observation must not run for $caseId")
        },
    ) : AgentOsExecutionAdapter {
        val startTurns = CopyOnWriteArrayList<String>()
        val reconcileCalls = AtomicInteger()
        val observeCalls = AtomicInteger()

        override fun createOrRecoverExecution(
            namespaceId: String,
            workflowId: String,
            stepId: String,
            externalUserId: String?,
            attemptId: String,
            capabilityToken: String?,
            caseId: String,
        ): CaseHandle = CaseHandle(caseId, namespaceId, false)

        override fun startTurn(
            caseId: String,
            persona: String,
            brief: String,
            externalUserId: String?,
            attemptId: String,
            capabilityToken: String?,
        ) {
            startTurns.add(attemptId)
        }

        override fun observeTurn(caseId: String, attemptId: String, timeoutMs: Long): AgentOsExecutionVerdict {
            observeCalls.incrementAndGet()
            return observeVerdict(caseId)
        }

        override fun reconcile(caseId: String): AgentOsExecutionVerdict {
            reconcileCalls.incrementAndGet()
            return reconcileVerdict(caseId)
        }

        override fun interrupt(caseId: String, reason: String) = Unit

        override fun kill(caseId: String) = Unit
    }

    // ------------------------------------------------------------------
    // Shared durable-attempt fixtures
    // ------------------------------------------------------------------

    private fun registerDurableAttempt(namespaceId: String, workflowId: String, attemptId: String, attemptNumber: Int = 1) {
        attemptService.register(
            scope,
            DurableAgentAttempt(
                attemptId = attemptId,
                caseId = "case:$attemptId",
                namespaceId = namespaceId,
                workflowId = workflowId,
                stepId = "step-1",
                attemptNumber = attemptNumber,
                agentName = "architect",
                brief = "do the thing",
            ),
        )
    }

    /** Claims the attempt and walks it to RUNNING; `leaseTtlMs = 0` simulates a crashed worker. */
    private fun claimToRunning(namespaceId: String, workflowId: String, attemptId: String, owner: String, leaseTtlMs: Long) {
        attemptService.claim(scope, namespaceId, workflowId, "step-1", attemptId, owner, leaseTtlMs = leaseTtlMs)
        attemptService.transition(scope, namespaceId, workflowId, "step-1", attemptId, owner, AgentAttemptStatus.STARTING)
        attemptService.transition(scope, namespaceId, workflowId, "step-1", attemptId, owner, AgentAttemptStatus.RUNNING)
    }

    private fun detailCode(error: Throwable): String? =
        ((error as FactoryException).details as? Map<*, *>)?.get("code") as? String

    // ==================================================================
    // 1. Factory restart during an attempt (P2/P3)
    // ==================================================================

    /**
     * Invariant: the boot-time recovery sweep EXPLAINS the state of every
     * in-flight attempt (indeterminate / still waiting for the human) and
     * never infers a terminal success from silence, nor re-sends the turn.
     */
    @Test
    fun `factory restart during an attempt clarifies the state and never infers a terminal success`() {
        val namespaceId = "ns-p12-restart"
        val workflowId = "wf-p12-restart"
        // Two attempts left behind by a worker that crashed mid-turn (expired leases).
        registerDurableAttempt(namespaceId, workflowId, "attempt-p12-restart-ind", attemptNumber = 1)
        claimToRunning(namespaceId, workflowId, "attempt-p12-restart-ind", "crashed-worker", leaseTtlMs = 0)
        registerDurableAttempt(namespaceId, workflowId, "attempt-p12-restart-wait", attemptNumber = 2)
        claimToRunning(namespaceId, workflowId, "attempt-p12-restart-wait", "crashed-worker", leaseTtlMs = 0)

        val adapter = FakeAdapter(
            reconcileVerdict = { caseId ->
                when (caseId) {
                    "case:attempt-p12-restart-ind" -> AgentOsExecutionVerdict.Indeterminate("still running")
                    else -> AgentOsExecutionVerdict.WaitingHuman("question-1")
                }
            },
            observeVerdict = { AgentOsExecutionVerdict.Indeterminate(VerdictDeriver.RUNTIME_UNREACHABLE) },
        )

        // The restart boundary: the boot-time recovery sweep.
        val report = BridgeRecoveryWorker(attemptService, adapter).recover()

        // No turn was ever re-sent: recovery only reconciled and observed.
        assertThat(adapter.startTurns).isEmpty()
        assertThat(report.resumed).isEqualTo(1)

        // The still-running case is EXPLAINED as indeterminate — never a success.
        val indeterminate = attemptService.find(scope, namespaceId, workflowId, "step-1", "attempt-p12-restart-ind")!!
        assertThat(indeterminate.status).isEqualTo(AgentAttemptStatus.INDETERMINATE)
        assertThat(indeterminate.status.isSuccess).isFalse()

        // The waiting case is clarified as still waiting for its human — non-terminal.
        val waiting = attemptService.find(scope, namespaceId, workflowId, "step-1", "attempt-p12-restart-wait")!!
        assertThat(waiting.status).isEqualTo(AgentAttemptStatus.WAITING_HUMAN)
        assertThat(waiting.status.terminal).isFalse()

        // No attempt of the workflow was ever promoted to SUCCEEDED by the sweep.
        assertThat(
            attemptService.findByWorkflow(scope, namespaceId, workflowId)
                .count { it.status == AgentAttemptStatus.SUCCEEDED },
        ).isEqualTo(0)
    }

    // ==================================================================
    // 2. AgentOS unreachable / down (P3)
    // ==================================================================

    /**
     * Invariant: when the AgentOS runtime cannot be reached at all, the case
     * state is unknowable — the attempt stays honestly non-terminal (recovery
     * skips it) and the verdict vocabulary itself can never promote silence to
     * a success.
     */
    @Test
    fun `an unreachable AgentOS runtime is classified indeterminate and never succeeded`() {
        val namespaceId = "ns-p12-down"
        val workflowId = "wf-p12-down"
        registerDurableAttempt(namespaceId, workflowId, "attempt-p12-down")
        claimToRunning(namespaceId, workflowId, "attempt-p12-down", "crashed-worker", leaseTtlMs = 0)

        // AgentOS is down: every reconcile call fails with a connection error.
        val adapter = FakeAdapter(reconcileVerdict = { throw RuntimeException("Connection refused") })

        val report = BridgeRecoveryWorker(attemptService, adapter).recover()

        // The sweep skips the attempt: its state is unknowable, so nothing is inferred.
        assertThat(report.skipped).isEqualTo(1)
        val attempt = attemptService.find(scope, namespaceId, workflowId, "step-1", "attempt-p12-down")!!
        assertThat(attempt.status).isEqualTo(AgentAttemptStatus.RUNNING)
        assertThat(attempt.status.terminal).isFalse()
        assertThat(attempt.status.isSuccess).isFalse()

        // The verdict rules themselves: a non-quiescent case yields NO verdict.
        val silent = VerdictDeriver.derive(emptyList(), VerdictDeriver.DerivationContext(caseId = "case-p12-down"))
        assertThat(silent).isNull()

        // An IDLE case without a structured result is Indeterminate — never Succeeded.
        val idle = VerdictDeriver.derive(
            listOf(
                CaseEventView(
                    eventId = "evt-1",
                    type = CaseEventView.CASE_STATUS_EVENT,
                    caseId = "case-p12-down",
                    timestamp = "2026-01-01T00:00:00Z",
                    raw = mapOf(
                        "id" to "evt-1",
                        "type" to CaseEventView.CASE_STATUS_EVENT,
                        "caseId" to "case-p12-down",
                        "status" to "IDLE",
                    ),
                ),
            ),
            VerdictDeriver.DerivationContext(caseId = "case-p12-down"),
        )
        assertThat(idle).isInstanceOf(AgentOsExecutionVerdict.Indeterminate::class.java)
        assertThat((idle as AgentOsExecutionVerdict.Indeterminate).reason).isEqualTo(VerdictDeriver.AGENT_NO_STRUCTURED_RESULT)
        assertThat(idle).isNotInstanceOf(AgentOsExecutionVerdict.Succeeded::class.java)
    }

    // ==================================================================
    // 3. Late + duplicate step-result submission (P1/P10)
    // ==================================================================

    private val resultNamespace = "ns-p12-result"
    private val resultWorkflow = "wf-p12-result"
    private val resultStep = "step-p12-result"
    private val resultCaseId = "case-p12-result"
    private val resultAgentName = "Worker"

    /**
     * Invariant: an identical late/duplicate replay returns the SAME recorded
     * result (idempotent, one row, one outbox event), a divergent replay is an
     * explicit collision, and the terminal attempt/verdict never changes.
     */
    @Test
    fun `late and duplicate step result submissions are idempotent and leave the verdict unchanged`() {
        val attemptId = "attempt-p12-result"
        seedResultAttempt(attemptId)
        val issued = resultService.issue(scope, resultIdentity(attemptId))
        val first = resultService.submit(scope, issued.token, businessResult("PASS", "ok"), resultObserved(attemptId), "key-p12-result")
        assertThat(first.idempotent).isFalse()

        // Late duplicate replay of the exact same submission (same token + key).
        val replay = resultService.submit(scope, issued.token, businessResult("PASS", "ok"), resultObserved(attemptId), "key-p12-result")

        assertThat(replay.idempotent).isTrue()
        assertThat(replay.resultId).isEqualTo(first.resultId)
        assertThat(replay.resultHash).isEqualTo(first.resultHash)
        // Still exactly ONE result row and ONE pending outbox event.
        assertThat(resultNodes.countByAttempt(ORGANIZATION_ID, WORKSTREAM_ID, resultNamespace, resultWorkflow, resultStep, attemptId))
            .isEqualTo(1)
        assertThat(outboxNodes.findAllByOrganization(ORGANIZATION_ID)).hasSize(1)
        // The verdict is unchanged: the attempt stays terminally completed.
        assertThat(legacyAttempts.find(scope, resultNamespace, resultWorkflow, resultStep, attemptId)?.status)
            .isEqualTo("completed")

        // A divergent late submission is an explicit collision on both paths.
        assertThatThrownBy {
            resultService.submit(scope, issued.token, businessResult("FAIL", "divergent"), resultObserved(attemptId), null)
        }
            .isInstanceOf(ResultSemanticCollisionException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", "RESULT_SEMANTIC_COLLISION")
        assertThatThrownBy {
            resultService.submit(scope, issued.token, businessResult("PASS", "divergent"), resultObserved(attemptId), "key-p12-result")
        }
            .isInstanceOf(IdempotencyKeyCollisionException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", "IDEMPOTENCY_KEY_COLLISION")

        // Every rejected replay left the recorded verdict byte-for-byte intact.
        assertThat(resultNodes.countByAttempt(ORGANIZATION_ID, WORKSTREAM_ID, resultNamespace, resultWorkflow, resultStep, attemptId))
            .isEqualTo(1)
        assertThat(outboxNodes.findAllByOrganization(ORGANIZATION_ID)).hasSize(1)
        val result = resultNodes.findFirstByAttempt(ORGANIZATION_ID, WORKSTREAM_ID, resultNamespace, resultWorkflow, resultStep, attemptId)
        assertThat(result?.resultStatus).isEqualTo("success")
        assertThat(result?.semanticSignature).isEqualTo(first.resultHash)
        assertThat(legacyAttempts.find(scope, resultNamespace, resultWorkflow, resultStep, attemptId)?.status)
            .isEqualTo("completed")
    }

    private fun seedResultAttempt(attemptId: String) {
        legacyAttempts.insert(
            scope,
            AgentStepAttemptRecord(resultNamespace, resultWorkflow, resultStep, attemptId, "agent-1", "running", 1, "{}"),
        )
    }

    private fun resultIdentity(attemptId: String): AgentStepResultCapabilityIdentity =
        AgentStepResultCapabilityIdentity(
            attemptId = attemptId,
            workflowId = resultWorkflow,
            stepId = resultStep,
            namespaceId = resultNamespace,
            caseId = resultCaseId,
            agentName = resultAgentName,
            briefHash = "sha256:${"a".repeat(64)}",
        )

    private fun resultObserved(attemptId: String): AgentStepResultObservedIdentity =
        AgentStepResultObservedIdentity(attemptId = attemptId, caseId = resultCaseId, agentName = resultAgentName)

    private fun businessResult(status: String, summary: String): JsonNode =
        objectMapper.readTree("""{"status":"$status","summary":"$summary","claims":{"modifiedFiles":[]}}""")

    // ==================================================================
    // 4. Unanswered question then duplicate answer (P4)
    // ==================================================================

    private val questionNamespace = "ns-p12-question"
    private val questionWorkflow = "wf-p12-question"
    private val questionStep = "step-p12-question"
    private val questionCaseId = "case-p12-question"
    private val questionAgentName = "Worker"
    private val questionAttemptId = "attempt-p12-question"

    /**
     * Invariant: the single-use answer closes the interaction exactly once —
     * attempt N is terminally superseded, N+1 is registered pending, and a
     * duplicate answer can never create an attempt N+2 (no double unblocking).
     */
    @Test
    fun `an unanswered question then a duplicate answer never unblocks twice and N+1 stays unique`() {
        val token = seedQuestionAttemptWithCapability()

        // The worker asks: the attempt parks in waiting_human with its durable interaction.
        val asked = questionService.ask(
            scope,
            token,
            questionAttemptId,
            objectMapper.readTree("""{"prompt":"Proceed?","type":"SINGLE_CHOICE","options":["yes","no"],"contextHash":"sha256:p12"}"""),
            questionCaseId,
            questionAgentName,
            null,
        )
        assertThat(asked.status).isEqualTo("waiting_human")
        val interaction = interactions.find(scope, questionNamespace, questionWorkflow, asked.interactionId)!!

        // The human answers exactly once: N is superseded, N+1 is registered pending.
        val answered = questionService.answer(
            scope, questionNamespace, questionWorkflow, asked.interactionId, interaction.revision, "yes", "alice",
        )
        val successorId = CapabilityExecutionService.retryAttemptId(questionWorkflow, questionStep, 2)
        assertThat(answered.supersededAttemptId).isEqualTo(questionAttemptId)
        assertThat(answered.successorAttemptId).isEqualTo(successorId)
        assertThat(attemptService.find(scope, questionNamespace, questionWorkflow, questionStep, questionAttemptId)!!.status)
            .isEqualTo(AgentAttemptStatus.SUPERSEDED)
        val successor = attemptService.find(scope, questionNamespace, questionWorkflow, questionStep, successorId)!!
        assertThat(successor.status).isEqualTo(AgentAttemptStatus.PENDING)
        assertThat(successor.attemptNumber).isEqualTo(2)

        // A duplicate answer submission is rejected — no double unblocking.
        assertThatThrownBy {
            questionService.answer(scope, questionNamespace, questionWorkflow, asked.interactionId, interaction.revision, "no", "alice")
        }
            .isInstanceOf(QuestionAlreadyAnsweredException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", "QUESTION_ALREADY_ANSWERED")

        // No attempt N+2 was ever created: exactly N (superseded) and N+1 exist.
        assertThat(attemptService.findByWorkflow(scope, questionNamespace, questionWorkflow).map { it.attemptId })
            .containsExactlyInAnyOrder(questionAttemptId, successorId)
        assertThat(attemptService.nextAttemptNumber(scope, questionNamespace, questionWorkflow, questionStep)).isEqualTo(3)
    }

    /**
     * Invariant: a stale interaction revision on the answer is a conflict and
     * mutates nothing — the question keeps waiting and no successor exists.
     */
    @Test
    fun `a stale revision on the question answer is a conflict that mutates nothing`() {
        val token = seedQuestionAttemptWithCapability()
        val asked = questionService.ask(
            scope,
            token,
            questionAttemptId,
            objectMapper.readTree("""{"prompt":"Sure?","type":"FREE_TEXT","contextHash":"sha256:p12b"}"""),
            questionCaseId,
            questionAgentName,
            null,
        )
        val interaction = interactions.find(scope, questionNamespace, questionWorkflow, asked.interactionId)!!

        assertThatThrownBy {
            questionService.answer(scope, questionNamespace, questionWorkflow, asked.interactionId, interaction.revision + 1, "yes", "alice")
        }.isInstanceOf(RevisionConflictException::class.java)

        assertThat(attemptService.find(scope, questionNamespace, questionWorkflow, questionStep, questionAttemptId)!!.status)
            .isEqualTo(AgentAttemptStatus.WAITING_HUMAN)
        assertThat(attemptService.findByWorkflow(scope, questionNamespace, questionWorkflow)).hasSize(1)
        assertThat(interactions.find(scope, questionNamespace, questionWorkflow, asked.interactionId)!!.status)
            .isEqualTo("waiting")
    }

    /**
     * Seeds the legacy result-path attempt row (required for capability
     * issuance) plus the durable execution attempt driven to `running`, then
     * issues the single-use capability. Returns the clear capability token.
     */
    private fun seedQuestionAttemptWithCapability(): String {
        legacyAttempts.insert(
            scope,
            AgentStepAttemptRecord(questionNamespace, questionWorkflow, questionStep, questionAttemptId, "agent-1", "running", 1, "{}"),
        )
        attemptService.register(
            scope,
            DurableAgentAttempt(
                attemptId = questionAttemptId,
                caseId = questionCaseId,
                namespaceId = questionNamespace,
                workflowId = questionWorkflow,
                stepId = questionStep,
                attemptNumber = 1,
                agentName = questionAgentName,
                brief = "the-turn-brief",
            ),
        )
        attemptService.claim(scope, questionNamespace, questionWorkflow, questionStep, questionAttemptId, "owner-1", leaseTtlMs = 60_000)
        attemptService.transition(
            scope, questionNamespace, questionWorkflow, questionStep, questionAttemptId, "owner-1", AgentAttemptStatus.STARTING,
        )
        attemptService.transition(
            scope, questionNamespace, questionWorkflow, questionStep, questionAttemptId, "owner-1", AgentAttemptStatus.RUNNING,
        )
        return resultService.issue(
            scope,
            AgentStepResultCapabilityIdentity(
                attemptId = questionAttemptId,
                workflowId = questionWorkflow,
                stepId = questionStep,
                namespaceId = questionNamespace,
                caseId = questionCaseId,
                agentName = questionAgentName,
                briefHash = "sha256:${"d".repeat(64)}",
            ),
        ).token
    }

    // ==================================================================
    // 5. Controller case renewal (P9)
    // ==================================================================

    private val controllerNamespace = "ns-p12-controller"

    /**
     * Invariant: compacting the controller case renews it with the SAME
     * controller/interlocutor identity (agent ref + workstream) and a recovery
     * packet (bounded resumption package) that never exceeds the hard byte cap,
     * even when the projection holds far more state than the per-section caps.
     */
    @Test
    fun `controller case renewal keeps the controller identity with a bounded recovery packet`() {
        workstreamService.create(
            scope,
            CreateWorkstreamRequest(slug = WORKSTREAM_ID, name = "P12 WS", status = "active", controllerAgentRef = "agent://controller"),
        )
        // Stress the resumption package beyond every per-section cap.
        repeat(15) { index -> publishProjectionWorkflow(controllerNamespace, "wf-p12-ctx-$index", listOf("running", "blocked")) }
        repeat(12) { index -> registerDurableAttempt(controllerNamespace, "wf-p12-ctx-0", "attempt-p12-ctx-$index", index + 1) }

        val first = controllerCaseService.startControllerCase(caller, WORKSTREAM_ID, StartControllerCaseRequest())

        val renewed = controllerCaseService.compactControllerCase(
            caller,
            WORKSTREAM_ID,
            CompactControllerCaseRequest(compactionReason = "token budget"),
        )

        // The controller / interlocutor identity is stable across the renewal.
        assertThat(renewed.controllerAgentRef).isEqualTo(first.controllerAgentRef)
        assertThat(renewed.workstreamId).isEqualTo(first.workstreamId)
        assertThat(renewed.sequence).isEqualTo(first.sequence + 1)
        assertThat(renewed.caseId).isNotEqualTo(first.caseId)
        assertThat(controllerCaseService.getActiveCase(caller, WORKSTREAM_ID)?.caseId).isEqualTo(renewed.caseId)

        // The recovery packet never exceeds the hard byte cap.
        assertThat(renewed.contextSummary).isNotNull()
        assertThat(renewed.contextSummary!!.toByteArray(StandardCharsets.UTF_8).size)
            .isLessThanOrEqualTo(ControllerCaseBounds.MAX_CONTEXT_SUMMARY_BYTES)

        // A compaction reason beyond the bound is rejected and changes nothing.
        val tooLong = "r".repeat(ControllerCaseBounds.MAX_COMPACTION_REASON_CHARS + 1)
        assertThatThrownBy {
            controllerCaseService.compactControllerCase(caller, WORKSTREAM_ID, CompactControllerCaseRequest(compactionReason = tooLong))
        }
            .isInstanceOf(BadRequestException::class.java)
            .satisfies({ error -> assertThat(detailCode(error)).isEqualTo("INVALID_COMPACTION_REASON") })
        assertThat(controllerCaseService.getActiveCase(caller, WORKSTREAM_ID)?.caseId).isEqualTo(renewed.caseId)
    }

    private fun publishProjectionWorkflow(namespaceId: String, workflowId: String, stepStatuses: List<String>) {
        workflowService.publishProjection(
            scope,
            namespaceId,
            workflowId,
            linkedMapOf<String, Any?>(
                "schemaVersion" to "1",
                "workflowId" to workflowId,
                "workflowType" to "wf-p12-demo",
                "title" to "Workflow $workflowId",
                "status" to "running",
                "steps" to stepStatuses.mapIndexed { index, status ->
                    linkedMapOf<String, Any?>(
                        "id" to "step-${index + 1}",
                        "name" to "Step ${index + 1}",
                        "status" to status,
                    )
                },
            ),
            0,
            null,
        )
    }

    // ==================================================================
    // 6. Incompatible plan change proposal (P8)
    // ==================================================================

    private val planNamespace = "ns-p12-plan"

    /**
     * Invariant: an incompatible (Rule 2/3) plan change is never auto-applied —
     * the governance gate rejects the silent rewrite (409
     * `PLAN_CHANGE_GATE_REQUIRED`), the rejected decision mutates nothing, and
     * the original proposal payload is preserved byte for byte.
     */
    @Test
    fun `an incompatible plan change proposal is gated and never silently rewrites the plan`() {
        val workflowId = "wf-p12-plan"
        val submitted = planChangeService.submit(
            scope,
            "alice",
            PlanChangeSubmitCommand(
                workflowId = workflowId,
                namespaceId = planNamespace,
                expectedRevision = 1,
                reasonCode = "ORACLE_FAILURE",
                summary = "rewrite the oracle contract of step-a",
                proposalType = PlanChangeProposalType.CONTRACT_OR_ORACLE,
                affectedStepIds = listOf("step-a"),
                evidenceRefs = listOf("evidence-1"),
                idempotencyKey = "key-p12-plan",
            ),
        )

        // Deterministic classification: an incompatible change is never self-applicable.
        assertThat(submitted.proposal.kind).isEqualTo(PlanChangeKind.CONTRACT_OR_ORACLE_CHANGE_PROPOSAL)
        assertThat(submitted.proposal.recommendedVerdict).isEqualTo(PlanChangeDecisionStatus.REQUIRES_NEW_DEFINITION)
        assertThat(submitted.proposal.recommendedVerdict).isNotEqualTo(PlanChangeDecisionStatus.AUTO_APPLIED)

        // The governance gate rejects a silent auto-application.
        assertThatThrownBy {
            planChangeService.decide(
                scope,
                "alice",
                planNamespace,
                workflowId,
                submitted.proposal.proposalId,
                PlanChangeDecideCommand(expectedRevision = 1, decision = PlanChangeDecisionStatus.AUTO_APPLIED),
            )
        }
            .isInstanceOf(FactoryException::class.java)
            .satisfies({ error ->
                assertThat((error as FactoryException).statusCode).isEqualTo(409)
                assertThat((error as FactoryException).errorCode).isEqualTo("PLAN_CHANGE_GATE_REQUIRED")
            })

        // No silent rewrite: the rejected decision mutated nothing.
        val untouched = planChangeRepository.findById(scope, planNamespace, workflowId, submitted.proposal.proposalId)!!
        assertThat(untouched.currentStatus).isEqualTo(PlanChangeDecisionStatus.PENDING_VALIDATION)
        assertThat(untouched.revision).isEqualTo(1)
        assertThat(untouched.kind).isEqualTo(PlanChangeKind.CONTRACT_OR_ORACLE_CHANGE_PROPOSAL)
        assertThat(untouched.summary).isEqualTo("rewrite the oracle contract of step-a")

        // The explicit governance path records the rejection as an immutable decision.
        val rejected = planChangeService.decide(
            scope,
            "alice",
            planNamespace,
            workflowId,
            submitted.proposal.proposalId,
            PlanChangeDecideCommand(
                expectedRevision = 1,
                decision = PlanChangeDecisionStatus.REJECTED,
                reason = "incompatible with the active definition",
            ),
        )
        assertThat(rejected.proposal.currentStatus).isEqualTo(PlanChangeDecisionStatus.REJECTED)
        assertThat(rejected.decisions.map { it.status })
            .containsExactly(PlanChangeDecisionStatus.PENDING_VALIDATION, PlanChangeDecisionStatus.REJECTED)

        // Even after the decision, the original proposal payload is preserved byte for byte.
        val reloaded = planChangeRepository.findById(scope, planNamespace, workflowId, submitted.proposal.proposalId)!!
        assertThat(reloaded.kind).isEqualTo(PlanChangeKind.CONTRACT_OR_ORACLE_CHANGE_PROPOSAL)
        assertThat(reloaded.summary).isEqualTo("rewrite the oracle contract of step-a")
        assertThat(reloaded.affectedStepIds).isEqualTo(submitted.proposal.affectedStepIds)
    }

    // ==================================================================
    // 7. Terminal workflow instance sealing (P10)
    // ==================================================================

    private val sealNamespace = "ns-p12-seal"

    /**
     * Invariant: a terminal workflow run (`completed`/`failed`/`cancelled`) is
     * sealed — late transitions, code transitions and late human-interaction
     * replies are all rejected with `WORKFLOW_SEALED` (409) and never reopen
     * the instance.
     */
    @Test
    fun `a terminal workflow instance is sealed and late messages never reopen it`() {
        registerSealDefinition()
        startSealWorkflow("wf-p12-completed")
        driveSealGate("wf-p12-completed", "approve")
        assertThat(sealRunStatus("wf-p12-completed")).isEqualTo("completed")

        // A late transition request is rejected: the terminal run is sealed.
        assertThatThrownBy {
            workflowService.transition(scope, sealNamespace, "wf-p12-completed", sealTransitionRequest("wf-p12-completed", 3), sealExecution())
        }
            .isInstanceOf(WorkflowException::class.java)
            .extracting("errorCode")
            .isEqualTo(WorkflowErrorCodes.WORKFLOW_SEALED)

        assertThatThrownBy {
            workflowService.codeTransition(scope, sealNamespace, "wf-p12-completed", sealTransitionRequest("wf-p12-completed", 3))
        }
            .isInstanceOf(WorkflowException::class.java)
            .extracting("errorCode")
            .isEqualTo(WorkflowErrorCodes.WORKFLOW_SEALED)

        // A late human-interaction reply (a late message/event) is rejected too.
        insertSealInteraction("wf-p12-completed", "interaction-p12-late")
        assertThatThrownBy {
            workflowService.replyInteraction(scope, sealNamespace, "wf-p12-completed", "interaction-p12-late", 1, "approve", "late", "alice")
        }
            .isInstanceOf(WorkflowException::class.java)
            .extracting("errorCode")
            .isEqualTo(WorkflowErrorCodes.WORKFLOW_SEALED)

        // Nothing reopened the sealed instance.
        assertThat(workflowRepository.findInstance(scope, sealNamespace, "wf-p12-completed")?.revision).isEqualTo(3)
        assertThat(sealRunStatus("wf-p12-completed")).isEqualTo("completed")
    }

    private fun registerSealDefinition() {
        val raw = linkedMapOf<String, Any?>(
            "schemaVersion" to "1",
            "workflowType" to "wf-p12-seal",
            "version" to "1.0.0",
            "title" to "P12 seal workflow",
            "steps" to listOf(
                linkedMapOf(
                    "id" to "gate",
                    "name" to "Gate",
                    "responsibility" to linkedMapOf("kind" to "human", "name" to "reviewer"),
                    "dependsOn" to emptyList<String>(),
                ),
            ),
        )
        val validated = WorkflowDefinitionValidator.validate(raw)
        check(validated is WorkflowDefinitionValidation.Valid) { "test definition must be valid: $validated" }
        workflowService.registerDefinition(
            scope,
            WorkflowDefinitionRecord(
                workflowType = "wf-p12-seal",
                version = "1.0.0",
                definitionHash = hashWorkflowDefinition(validated.definition),
                definition = validated.definition,
            ),
        )
    }

    private fun startSealWorkflow(workflowId: String) {
        workflowService.start(
            scope,
            sealNamespace,
            WorkflowStartCommand(workflowId = workflowId, workflowType = "wf-p12-seal", title = "P12 $workflowId"),
            ControllerExecutionInput(runtimeId = "agentos-primary", kind = "agentos", agentId = "runner", caseId = "case-$workflowId"),
        )
    }

    /** Drives the single human gate to its terminal status (`approve` → completed, `reject` → failed). */
    @Suppress("UNCHECKED_CAST")
    private fun driveSealGate(workflowId: String, actionId: String) {
        val opened = workflowService.openInteraction(
            scope,
            sealNamespace,
            workflowId,
            "gate",
            1,
            "Decide the gate?",
            listOf(
                mapOf("id" to "approve", "label" to "Approve"),
                mapOf("id" to "reject", "label" to "Reject"),
            ),
            "open-$workflowId",
        )
        val interaction = (opened.data as Map<String, Any?>)["interaction"] as Map<String, Any?>
        workflowService.replyInteraction(
            scope,
            sealNamespace,
            workflowId,
            interaction["interactionId"] as String,
            2,
            actionId,
            "decision of $workflowId",
            "alice",
        )
    }

    private fun insertSealInteraction(workflowId: String, interactionId: String) {
        interactions.insert(
            scope,
            HumanInteractionRecord(
                interactionId = interactionId,
                namespaceId = sealNamespace,
                workflowId = workflowId,
                stepId = "gate",
                interactionType = "checkpoint",
                status = "waiting",
                revision = 1,
                payload = linkedMapOf(
                    "stepId" to "gate",
                    "prompt" to "Late checkpoint",
                    "actions" to listOf(mapOf("id" to "approve", "label" to "Approve")),
                ),
            ),
        )
    }

    private fun sealTransitionRequest(workflowId: String, expectedRevision: Int): Map<String, Any?> = mapOf(
        "workflowId" to workflowId,
        "stepId" to "gate",
        "expectedRevision" to expectedRevision,
        "requestedStatus" to "running",
        "evidenceIds" to emptyList<String>(),
    )

    private fun sealExecution(): WorkflowExecution = WorkflowExecution(
        kind = "factory-human",
        runtimeId = "factory-dashboard",
        actorId = "alice",
    )

    private fun sealRunStatus(workflowId: String): String? =
        workflowRepository.findInstance(scope, sealNamespace, workflowId)?.instance?.get("status") as? String

    // ==================================================================
    // 8. Workstream read-only tools (P6) + gate via command tool (P7)
    // ==================================================================

    private val projectionNamespace = "ns-p12-projection"
    private val gateNamespace = "ns-p12-gate"

    /**
     * Invariant (P6): the server-side services behind the workstream read-only
     * tools answer with the correct counts/summaries, repeated reads are stable
     * (same ETag) and never mutate the underlying aggregates.
     */
    @Test
    fun `workstream read only answers are correct stable and never mutate the underlying state`() {
        workstreamService.create(scope, CreateWorkstreamRequest(slug = WORKSTREAM_ID, name = "P12 WS", status = "active"))
        publishProjectionWorkflow(projectionNamespace, "wf-p12-read", listOf("running", "waiting_human", "blocked"))
        registerDurableAttempt(projectionNamespace, "wf-p12-read", "attempt-p12-read-1", 1)
        registerDurableAttempt(projectionNamespace, "wf-p12-read", "attempt-p12-read-2", 2)
        interactions.insert(
            scope,
            HumanInteractionRecord(
                interactionId = "int-p12-read",
                namespaceId = projectionNamespace,
                workflowId = "wf-p12-read",
                stepId = "step-2",
                interactionType = "approval",
                status = "waiting",
                revision = 1,
                payload = linkedMapOf("prompt" to "Approve?", "actions" to emptyList<String>()),
            ),
        )

        val attemptRevisionBefore = attemptService
            .findByWorkflow(scope, projectionNamespace, "wf-p12-read")
            .associate { it.attemptId to it.revision }

        val projection = projectionService.getAggregatedProjection(caller, WORKSTREAM_ID, projectionNamespace, null)

        // The read-only answers are correct.
        assertThat(projection.activeWorkflows.count).isEqualTo(1)
        assertThat(projection.steps.running).isEqualTo(1)
        assertThat(projection.steps.waitingHuman).isEqualTo(1)
        assertThat(projection.steps.blocked).isEqualTo(1)
        assertThat(projection.attempts.count).isEqualTo(2)
        assertThat(projection.attempts.items.map { it.attemptId })
            .containsExactlyInAnyOrder("attempt-p12-read-1", "attempt-p12-read-2")
        assertThat(projection.humanActions.count).isEqualTo(1)
        assertThat(projection.humanActions.items.single().interactionId).isEqualTo("int-p12-read")

        // Repeated reads are stable: identical state -> identical ETag.
        assertThat(projectionService.getAggregatedProjection(caller, WORKSTREAM_ID, projectionNamespace, null).workstreamRevision)
            .isEqualTo(projection.workstreamRevision)

        // Read-only invariant: the underlying aggregates are untouched.
        assertThat(attemptService.findByWorkflow(scope, projectionNamespace, "wf-p12-read").associate { it.attemptId to it.revision })
            .isEqualTo(attemptRevisionBefore)
        assertThat(interactions.find(scope, projectionNamespace, "wf-p12-read", "int-p12-read")!!.revision).isEqualTo(1)
    }

    /**
     * Invariant (P7): a command-driven decision opens a governance gate that is
     * durably recorded and cannot be bypassed by a later auto-apply command; a
     * human-gate command durably opens a waiting interaction on the workflow.
     */
    @Test
    fun `a command tool gate decision opens a governance gate that cannot be bypassed`() {
        val workflowId = "wf-p12-gate"
        val submitted = planChangeService.submit(
            scope,
            "bob",
            PlanChangeSubmitCommand(
                workflowId = workflowId,
                namespaceId = gateNamespace,
                expectedRevision = 1,
                reasonCode = "ORACLE_FAILURE",
                summary = "replace the broken edge",
                proposalType = PlanChangeProposalType.DEPENDENCY,
                affectedStepIds = listOf("step-a", "step-b"),
                proposedDependencyChanges = listOf(DependencyChange(DependencyOp.ADD, "step-a", "step-b")),
                evidenceRefs = listOf("evidence-1"),
                idempotencyKey = "key-p12-gate",
            ),
        )
        assertThat(submitted.proposal.kind).isEqualTo(PlanChangeKind.DEPENDENCY_CHANGE_PROPOSAL)
        assertThat(submitted.proposal.recommendedVerdict).isEqualTo(PlanChangeDecisionStatus.GATE_REQUIRED)

        // The command opens the gate: the GATE_REQUIRED decision is durably recorded.
        val gated = planChangeService.decide(
            scope,
            "bob",
            gateNamespace,
            workflowId,
            submitted.proposal.proposalId,
            PlanChangeDecideCommand(expectedRevision = 1, decision = PlanChangeDecisionStatus.GATE_REQUIRED, reason = "structural change"),
        )
        assertThat(gated.proposal.currentStatus).isEqualTo(PlanChangeDecisionStatus.GATE_REQUIRED)
        assertThat(planChangeRepository.findById(scope, gateNamespace, workflowId, submitted.proposal.proposalId)!!.currentStatus)
            .isEqualTo(PlanChangeDecisionStatus.GATE_REQUIRED)

        // The gate cannot be bypassed by a later auto-apply command.
        assertThatThrownBy {
            planChangeService.decide(
                scope,
                "bob",
                gateNamespace,
                workflowId,
                submitted.proposal.proposalId,
                PlanChangeDecideCommand(expectedRevision = 2, decision = PlanChangeDecisionStatus.AUTO_APPLIED),
            )
        }
            .isInstanceOf(FactoryException::class.java)
            .satisfies({ error -> assertThat((error as FactoryException).errorCode).isEqualTo("PLAN_CHANGE_GATE_REQUIRED") })
        assertThat(planChangeRepository.findById(scope, gateNamespace, workflowId, submitted.proposal.proposalId)!!.currentStatus)
            .isEqualTo(PlanChangeDecisionStatus.GATE_REQUIRED)

        // A human-gate command durably opens a waiting interaction on the workflow.
        registerGateDefinition()
        startGateWorkflow("wf-p12-humangate")
        val opened = workflowService.openInteraction(
            scope,
            gateNamespace,
            "wf-p12-humangate",
            "gate",
            1,
            "Decide the gate?",
            listOf(
                mapOf("id" to "approve", "label" to "Approve"),
                mapOf("id" to "reject", "label" to "Reject"),
            ),
            "open-wf-p12-humangate",
        )
        @Suppress("UNCHECKED_CAST")
        val interaction = (opened.data as Map<String, Any?>)["interaction"] as Map<String, Any?>
        val persisted = interactions.find(scope, gateNamespace, "wf-p12-humangate", interaction["interactionId"] as String)!!
        assertThat(persisted.status).isEqualTo("waiting")
    }

    private fun registerGateDefinition() {
        val raw = linkedMapOf<String, Any?>(
            "schemaVersion" to "1",
            "workflowType" to "wf-p12-gate-def",
            "version" to "1.0.0",
            "title" to "P12 gate workflow",
            "steps" to listOf(
                linkedMapOf(
                    "id" to "gate",
                    "name" to "Gate",
                    "responsibility" to linkedMapOf("kind" to "human", "name" to "reviewer"),
                    "dependsOn" to emptyList<String>(),
                ),
            ),
        )
        val validated = WorkflowDefinitionValidator.validate(raw)
        check(validated is WorkflowDefinitionValidation.Valid) { "test definition must be valid: $validated" }
        workflowService.registerDefinition(
            scope,
            WorkflowDefinitionRecord(
                workflowType = "wf-p12-gate-def",
                version = "1.0.0",
                definitionHash = hashWorkflowDefinition(validated.definition),
                definition = validated.definition,
            ),
        )
    }

    private fun startGateWorkflow(workflowId: String) {
        workflowService.start(
            scope,
            gateNamespace,
            WorkflowStartCommand(workflowId = workflowId, workflowType = "wf-p12-gate-def", title = "P12 $workflowId"),
            ControllerExecutionInput(runtimeId = "agentos-primary", kind = "agentos", agentId = "runner", caseId = "case-$workflowId"),
        )
    }

    // ==================================================================
    // Deferred smokes — missing infrastructure dependencies
    // ==================================================================

    /**
     * WILL assert (once the dependency exists): a writable worker running in an
     * isolated WorkUnitEnvironment can only write inside its own worktree —
     * any write escaping the isolation boundary is rejected and auditable.
     *
     * Missing dependency: the real isolated `WorkUnitEnvironment` runtime plus
     * the writable worker agent are not built yet (no writable worker hardware/
     * sandbox exists to drive end-to-end), so this smoke is skipped.
     */
    @Disabled(
        "requires the isolated WorkUnitEnvironment runtime and the writable worker agent " +
            "(not yet built): no writable worker sandbox exists to drive end-to-end",
    )
    @Test
    fun `a writable worker in an isolated work unit environment cannot escape its isolation boundary`() {
        // Deferred: see the KDoc for the invariant this smoke WILL assert.
    }

    /**
     * WILL assert (once the dependency exists): after a worker completes its
     * work unit, the real Factory oracle executes against the produced state
     * and its pass/fail verdict — never the worker's self-report — is what
     * terminalizes the step.
     *
     * Missing dependency: the real post-worker Factory oracle execution path
     * (oracle runtime bound to the worker output) is not built yet end-to-end,
     * so this smoke is skipped.
     */
    @Disabled(
        "requires the real post-worker Factory oracle execution path " +
            "(not yet built end-to-end): only the smoke oracle catalogue is available in tests",
    )
    @Test
    fun `the real factory oracle after the worker is the only authority that terminalizes the step`() {
        // Deferred: see the KDoc for the invariant this smoke WILL assert.
    }

    /**
     * WILL assert (once the dependency exists): the full BMAD/Forge workflow
     * runs end-to-end under the resilience invariants of Phases 0–11 (durable
     * attempts, single-use results, governed replanning, terminal sealing).
     *
     * Missing dependency: the full BMAD/Forge workflow runtime (agents,
     * definitions and infrastructure) is not built yet, so this smoke is
     * skipped.
     */
    @Disabled(
        "requires the full BMAD/Forge workflow runtime " +
            "(agents, workflow definitions and infrastructure not yet built)",
    )
    @Test
    fun `the full BMAD Forge workflow holds the resilience invariants end to end`() {
        // Deferred: see the KDoc for the invariant this smoke WILL assert.
    }
}
