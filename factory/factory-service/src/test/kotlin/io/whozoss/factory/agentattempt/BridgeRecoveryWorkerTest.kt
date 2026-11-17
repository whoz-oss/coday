package io.whozoss.factory.agentattempt

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.nio.file.Path
import io.whozoss.factory.proxy.AgentOsProxyClient
import io.whozoss.factory.workflow.service.SessionRunSubmissionService
import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.adapter.agentos.AgentOsExecutionAdapter
import io.whozoss.factory.adapter.agentos.AgentOsExecutionVerdict
import io.whozoss.factory.adapter.agentos.CaseHandle
import io.whozoss.factory.adapter.agentos.CaseEventView
import io.whozoss.factory.adapter.agentos.TurnToken
import io.whozoss.factory.adapter.agentos.AgentOsObservationResult
import io.whozoss.factory.adapter.agentos.VerdictDeriver
import io.whozoss.factory.agentattempt.domain.AgentAttemptStatus
import io.whozoss.factory.agentattempt.domain.AttemptLeaseFencingException
import io.whozoss.factory.agentattempt.domain.DurableAgentAttempt
import io.whozoss.factory.agentattempt.service.BridgeRecoveryWorker
import io.whozoss.factory.agentattempt.service.DurableAgentAttemptService
import io.whozoss.factory.workflow.domain.WorkflowInstanceRecord
import io.whozoss.factory.workflow.persistence.WorkflowRepository
import io.whozoss.factory.workflow.persistence.WorkflowEvidenceRepository
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Startup recovery tests (Lot H, step 8) of the durable AgentOS-Factory bridge.
 *
 * The worker runs against the real Neo4j-backed [DurableAgentAttemptService] and
 * a stateful fake [AgentOsExecutionAdapter], so the claim/fencing/state-machine
 * semantics exercised are exactly the production ones.
 */
class BridgeRecoveryWorkerTest : Neo4jDomainIntegrationTest() {

    @Autowired
    private lateinit var attempts: DurableAgentAttemptService

    @Autowired
    private lateinit var workflowRepository: WorkflowRepository

    @Autowired
    private lateinit var evidenceRepository: WorkflowEvidenceRepository

    private val namespace = "2b6f8d2f-8d1a-4f0e-9b6e-recovery"

    private data class StartTurn(val caseId: String, val brief: String, val attemptId: String)

    private data class CreateCall(
        val caseId: String,
        val attemptId: String,
        val parentCaseId: String?,
    )

    private class FakeAdapter(
        private val reconcileVerdict: (String) -> AgentOsExecutionVerdict,
        private val observeVerdict: (String) -> AgentOsExecutionVerdict,
        private val history: List<CaseEventView> = emptyList(),
        private val reconcileProof: VerdictDeriver.RuntimeCaseTerminationProof? = null,
        private val observeProof: VerdictDeriver.RuntimeCaseTerminationProof? = null,
    ) : AgentOsExecutionAdapter {
        val startTurns = CopyOnWriteArrayList<StartTurn>()
        val createCalls = CopyOnWriteArrayList<CreateCall>()
        val reconcileCalls = AtomicInteger()
        val observeCalls = AtomicInteger()
        val createCallCount = AtomicInteger()

        override fun createOrRecoverExecution(
            namespaceId: String,
            workflowId: String,
            stepId: String,
            externalUserId: String?,
            attemptId: String,
            capabilityToken: String?,
            caseId: String,
            parentCaseId: String?,
        ): CaseHandle {
            createCallCount.incrementAndGet()
            createCalls.add(CreateCall(caseId, attemptId, parentCaseId))
            return CaseHandle(caseId, namespaceId, false)
        }

        override fun startTurn(
            caseId: String,
            persona: String,
            brief: String,
            externalUserId: String?,
            attemptId: String,
            capabilityToken: String?,
        ) {
            startTurns.add(StartTurn(caseId, brief, attemptId))
        }

        override fun observeTurn(caseId: String, attemptId: String, timeoutMs: Long): AgentOsExecutionVerdict {
            observeCalls.incrementAndGet()
            return observeVerdict(caseId)
        }

        override fun reconcile(caseId: String): AgentOsExecutionVerdict {
            reconcileCalls.incrementAndGet()
            return reconcileVerdict(caseId)
        }

        override fun reconcileResult(turn: TurnToken): AgentOsObservationResult =
            AgentOsObservationResult(reconcile(turn.caseId), reconcileProof)

        override fun observeTurnResult(
            turn: TurnToken,
            timeoutMs: Long,
            onIntermediateVerdict: (AgentOsExecutionVerdict.WaitingHuman) -> Unit,
            onAnswerObserved: (CaseEventView) -> Unit,
        ): AgentOsObservationResult = AgentOsObservationResult(
            observeTurn(turn.caseId, turn.attemptId, timeoutMs),
            observeProof,
        )

        override fun persistedEvents(caseId: String): List<CaseEventView> = history

        override fun interrupt(caseId: String, reason: String) = Unit

        override fun kill(caseId: String) = Unit
    }

    private fun caseIdFor(attemptId: String): String =
        UUID.nameUUIDFromBytes("case:$attemptId".toByteArray()).toString()

    private fun attempt(
        attemptId: String,
        brief: String? = "do the thing",
        parentCaseId: String? = null,
    ): DurableAgentAttempt = DurableAgentAttempt(
        attemptId = attemptId,
        caseId = caseIdFor(attemptId),
        namespaceId = namespace,
        workflowId = "wf-recovery",
        stepId = "step-1",
        attemptNumber = 1,
        agentName = "architect",
        brief = brief,
        parentCaseId = parentCaseId,
    )

    private fun claimToRunning(attemptId: String, ownerToken: String, leaseTtlMs: Long) {
        attempts.claim(scope, namespace, "wf-recovery", "step-1", attemptId, ownerToken, leaseTtlMs = leaseTtlMs)
        attempts.transition(scope, namespace, "wf-recovery", "step-1", attemptId, ownerToken, AgentAttemptStatus.STARTING)
        attempts.transition(scope, namespace, "wf-recovery", "step-1", attemptId, ownerToken, AgentAttemptStatus.RUNNING)
    }

    private fun status(attemptId: String) = attempts.find(scope, namespace, "wf-recovery", "step-1", attemptId)!!.status

    @Test
    fun `restart during a turn resumes observation without a second turn`() {
        attempts.register(scope, attempt("attempt-resume"))
        // The prior worker's lease expired: recovery may preempt it.
        claimToRunning("attempt-resume", "crashed-worker", leaseTtlMs = 0)
        val adapter = FakeAdapter(
            reconcileVerdict = { AgentOsExecutionVerdict.Indeterminate("still running") },
            observeVerdict = { AgentOsExecutionVerdict.Succeeded(mapOf("summary" to "resumed")) },
        )

        val report = BridgeRecoveryWorker(attempts, adapter).recover()

        assertThat(report.resumed).isEqualTo(1)
        assertThat(status("attempt-resume")).isEqualTo(AgentAttemptStatus.SUCCEEDED)
        // No duplicate work: the turn was already accepted, only observation resumed.
        assertThat(adapter.startTurns).isEmpty()
        assertThat(adapter.observeCalls.get()).isEqualTo(1)
    }

    @Test
    fun `crash after the AgentOS result but before the Factory commit finalizes on one reconciliation`() {
        attempts.register(scope, attempt("attempt-result"))
        claimToRunning("attempt-result", "crashed-worker", leaseTtlMs = 0)
        val adapter = FakeAdapter(
            reconcileVerdict = { AgentOsExecutionVerdict.Succeeded(mapOf("summary" to "done")) },
            observeVerdict = { error("observation must not run when the snapshot is terminal") },
        )

        val report = BridgeRecoveryWorker(attempts, adapter).recover()

        assertThat(report.finalized).isEqualTo(1)
        assertThat(status("attempt-result")).isEqualTo(AgentAttemptStatus.SUCCEEDED)
        assertThat(adapter.reconcileCalls.get()).isEqualTo(1)
        assertThat(adapter.observeCalls.get()).isZero()
        assertThat(adapter.startTurns).isEmpty()
    }

    @Test
    fun `recovery finalizes before continuation with missing proxy throwing proxy and failed enqueue`() {
        for (mode in listOf("absent", "unavailable", "enqueue-fails")) {
            val id = "continuation-$mode"
            attempts.register(scope, attempt(id))
            claimToRunning(id, "crashed-worker", leaseTtlMs = 0)
            val adapter = FakeAdapter(
                reconcileVerdict = { AgentOsExecutionVerdict.Succeeded(emptyMap()) },
                observeVerdict = { error("must not observe") },
            )
            val proxy = if (mode == "absent") null else mockk<AgentOsProxyClient>()
            proxy?.let { client ->
                every { client.resolveRepoRoot(namespace, any()) } answers {
                    assertThat(status(id)).isEqualTo(AgentAttemptStatus.SUCCEEDED)
                    error("AgentOS unavailable")
                }
            }
            val submitter = mockk<SessionRunSubmissionService>()
            val root = Path.of(".").toAbsolutePath().normalize().toString()
            every { submitter.submit(scope, namespace, "wf-recovery", root, "attempt-recovered:$id", null) } answers {
                assertThat(status(id)).isEqualTo(AgentAttemptStatus.SUCCEEDED)
                if (mode == "enqueue-fails") error("outbox unavailable")
                SessionRunSubmissionService.SubmissionResult("submission", true, false, "pending")
            }
            val report = BridgeRecoveryWorker(attempts, adapter,
                sessionRunSubmissionService = submitter, agentOsProxyClient = proxy).recover()
            assertThat(report.finalized).isEqualTo(1)
            assertThat(report.skipped).isZero()
            assertThat(status(id)).isEqualTo(AgentAttemptStatus.SUCCEEDED)
            verify(exactly = 1) { submitter.submit(scope, namespace, "wf-recovery", root, "attempt-recovered:$id", null) }
        }
    }

    @Test
    fun `recovery persists selected runtime proof and omits it when absent`() {
        val proofAttempt = "attempt-proof"
        attempts.register(scope, attempt(proofAttempt))
        claimToRunning(proofAttempt, "crashed-worker", leaseTtlMs = 0)
        val proof = VerdictDeriver.RuntimeCaseTerminationProof(
            caseId = caseIdFor(proofAttempt),
            attemptId = proofAttempt,
            eventId = "idle-proof",
            status = "IDLE",
        )
        val withProof = FakeAdapter(
            reconcileVerdict = { AgentOsExecutionVerdict.Succeeded(mapOf("summary" to "done")) },
            observeVerdict = { error("not observed") },
            reconcileProof = proof,
        )
        BridgeRecoveryWorker(attempts, withProof, evidenceRepository).recover()
        val proofEvidence = evidenceRepository.list(scope, namespace, "wf-recovery", "step-1")
            .single { it.facts["attemptId"] == proofAttempt }
        assertThat(proofEvidence.facts["runtimeCaseTerminationProof"]).isEqualTo(
            mapOf("caseId" to proof.caseId, "attemptId" to proof.attemptId, "eventId" to proof.eventId, "status" to proof.status),
        )

        val noProofAttempt = "attempt-no-proof"
        attempts.register(scope, attempt(noProofAttempt))
        claimToRunning(noProofAttempt, "crashed-worker", leaseTtlMs = 0)
        val withoutProof = FakeAdapter(
            reconcileVerdict = { AgentOsExecutionVerdict.Succeeded(mapOf("summary" to "done")) },
            observeVerdict = { error("not observed") },
        )
        BridgeRecoveryWorker(attempts, withoutProof, evidenceRepository).recover()
        val noProofEvidence = evidenceRepository.list(scope, namespace, "wf-recovery", "step-1")
            .single { it.facts["attemptId"] == noProofAttempt }
        assertThat(noProofEvidence.facts).doesNotContainKey("runtimeCaseTerminationProof")
    }

    @Test
    fun `a live lease owned by another worker is never stolen`() {
        attempts.register(scope, attempt("attempt-live"))
        claimToRunning("attempt-live", "live-worker", leaseTtlMs = 60_000)
        val adapter = FakeAdapter(
            reconcileVerdict = { AgentOsExecutionVerdict.Succeeded(emptyMap()) },
            observeVerdict = { error("observation must not run") },
        )

        val report = BridgeRecoveryWorker(attempts, adapter).recover()

        assertThat(report.conflicted).isEqualTo(1)
        assertThat(status("attempt-live")).isEqualTo(AgentAttemptStatus.RUNNING)
    }

    @Test
    fun `a turn is re-driven only when it was never accepted`() {
        insertAutonomousWorkflow()
        attempts.register(scope, attempt("attempt-redrive", brief = "recover me"))
        // Still `pending`: never claimed, so `startTurn` could not have been accepted.
        val adapter = FakeAdapter(
            reconcileVerdict = { AgentOsExecutionVerdict.Indeterminate("no events yet") },
            observeVerdict = { AgentOsExecutionVerdict.Succeeded(mapOf("summary" to "ok")) },
        )

        val report = BridgeRecoveryWorker(attempts, adapter, workflowRepository = workflowRepository).recover()

        assertThat(report.redriven).isEqualTo(1)
        assertThat(status("attempt-redrive")).isEqualTo(AgentAttemptStatus.SUCCEEDED)
        assertThat(adapter.startTurns).hasSize(1)
        assertThat(adapter.startTurns.single().attemptId).isEqualTo("attempt-redrive")
        assertThat(adapter.startTurns.single().brief).isEqualTo("recover me")
    }

    @Test
    fun `a re-driven turn preserves the case-family parentCaseId`() {
        val root = caseIdFor("root-attempt")
        insertAutonomousWorkflow()
        attempts.register(scope, attempt("attempt-redrive-child", brief = "recover me", parentCaseId = root))
        val adapter = FakeAdapter(
            reconcileVerdict = { AgentOsExecutionVerdict.Indeterminate("no events yet") },
            observeVerdict = { AgentOsExecutionVerdict.Succeeded(mapOf("summary" to "ok")) },
        )

        val report = BridgeRecoveryWorker(attempts, adapter, workflowRepository = workflowRepository).recover()

        assertThat(report.redriven).isEqualTo(1)
        val create = adapter.createCalls.single()
        assertThat(create.caseId).isEqualTo(caseIdFor("attempt-redrive-child"))
        assertThat(create.attemptId).isEqualTo("attempt-redrive-child")
        assertThat(create.parentCaseId).isEqualTo(root)
    }

    private fun insertAutonomousWorkflow() {
        workflowRepository.insertInstance(
            scope,
            WorkflowInstanceRecord(
                namespaceId = namespace,
                workflowId = "wf-recovery",
                revision = 1,
                status = "active",
                creationCommandHash = "recovery-autonomous",
                instance = mapOf("workflowId" to "wf-recovery", "workflowType" to "recovery-test"),
                projection = emptyMap(),
            ),
        )
    }

    @Test
    fun `waiting human resumes only from persisted correlated answer`() {
        attempts.register(scope, attempt("attempt-answered"))
        claimToRunning("attempt-answered", "crashed-worker", leaseTtlMs = 0)
        attempts.transition(scope, namespace, "wf-recovery", "step-1", "attempt-answered", "crashed-worker", AgentAttemptStatus.WAITING_HUMAN)
        // claimToRunning already created an immediately expired lease. The
        // attempt remains WAITING_HUMAN and is therefore safely preemptible by
        // the recovery owner without any second transition in the fixture.
        val caseId = caseIdFor("attempt-answered")
        val adapter = FakeAdapter(
            reconcileVerdict = { AgentOsExecutionVerdict.WaitingHuman("question-1") },
            observeVerdict = { error("observation must not run") },
            history = listOf(
                CaseEventView("question-1", CaseEventView.QUESTION_EVENT, caseId, null, mapOf("id" to "question-1", "type" to CaseEventView.QUESTION_EVENT, "caseId" to caseId)),
                CaseEventView("answer-1", CaseEventView.ANSWER_EVENT, caseId, null, mapOf("id" to "answer-1", "type" to CaseEventView.ANSWER_EVENT, "caseId" to caseId, "questionId" to "question-1")),
            ),
        )

        val report = BridgeRecoveryWorker(attempts, adapter).recover()

        assertThat(report.resumed).isEqualTo(1)
        assertThat(status("attempt-answered")).isEqualTo(AgentAttemptStatus.RUNNING)
        assertThat(attempts.find(scope, namespace, "wf-recovery", "step-1", "attempt-answered")!!.lastObservedEventId)
            .isEqualTo("answer-1")
    }

    @Test
    fun `waiting human remains waiting without persisted correlated answer`() {
        attempts.register(scope, attempt("attempt-unanswered"))
        claimToRunning("attempt-unanswered", "crashed-worker", leaseTtlMs = 0)
        attempts.transition(scope, namespace, "wf-recovery", "step-1", "attempt-unanswered", "crashed-worker", AgentAttemptStatus.WAITING_HUMAN)
        val caseId = caseIdFor("attempt-unanswered")
        val adapter = FakeAdapter(
            reconcileVerdict = { AgentOsExecutionVerdict.WaitingHuman("question-1") },
            observeVerdict = { error("observation must not run") },
            history = listOf(
                CaseEventView("question-1", CaseEventView.QUESTION_EVENT, caseId, null, mapOf("id" to "question-1", "type" to CaseEventView.QUESTION_EVENT, "caseId" to caseId)),
            ),
        )

        val report = BridgeRecoveryWorker(attempts, adapter).recover()

        assertThat(report.finalized).isEqualTo(1)
        assertThat(status("attempt-unanswered")).isEqualTo(AgentAttemptStatus.WAITING_HUMAN)
    }

    @Test
    fun `active and waiting durations survive reload and terminal replay without double counting`() {
        val id = "attempt-timing"
        val base = java.time.Instant.parse("2026-10-21T21:00:00Z")
        attempts.register(scope, attempt(id), now = base)
        attempts.claim(scope, namespace, "wf-recovery", "step-1", id, "owner", leaseTtlMs = 2_000_000, now = base)
        attempts.transition(scope, namespace, "wf-recovery", "step-1", id, "owner", AgentAttemptStatus.STARTING, now = base)
        attempts.transition(scope, namespace, "wf-recovery", "step-1", id, "owner", AgentAttemptStatus.RUNNING, now = base)
        attempts.transition(scope, namespace, "wf-recovery", "step-1", id, "owner", AgentAttemptStatus.WAITING_HUMAN, now = base.plusSeconds(40))

        val waitingReload = attempts.find(scope, namespace, "wf-recovery", "step-1", id)!!
        assertThat(waitingReload.activeDurationMs).isEqualTo(40_000)
        assertThat(waitingReload.activeSince).isNull()
        assertThat(waitingReload.waitingSince).isEqualTo(base.plusSeconds(40))

        attempts.transition(scope, namespace, "wf-recovery", "step-1", id, "owner", AgentAttemptStatus.RUNNING, now = base.plusSeconds(1_240))
        val runningReload = attempts.find(scope, namespace, "wf-recovery", "step-1", id)!!
        assertThat(runningReload.activeDurationMs).isEqualTo(40_000)
        assertThat(runningReload.waitingDurationMs).isEqualTo(1_200_000)
        assertThat(runningReload.activeSince).isEqualTo(base.plusSeconds(1_240))
        assertThat(runningReload.startedAt).isEqualTo(base)

        val terminal = attempts.finalize(
            scope, namespace, "wf-recovery", "step-1", id, "owner",
            AgentAttemptStatus.SUCCEEDED, now = base.plusSeconds(1_250),
        )
        assertThat(terminal.activeDurationMs).isEqualTo(50_000)
        assertThat(terminal.waitingDurationMs).isEqualTo(1_200_000)
        assertThat(terminal.activeSince).isNull()
        assertThat(terminal.waitingSince).isNull()

        val replay = attempts.finalize(
            scope, namespace, "wf-recovery", "step-1", id, "owner",
            AgentAttemptStatus.SUCCEEDED, now = base.plusSeconds(1_300),
        )
        assertThat(replay.activeDurationMs).isEqualTo(50_000)
        assertThat(replay.waitingDurationMs).isEqualTo(1_200_000)
        assertThat(replay.revision).isEqualTo(terminal.revision)
    }

    @Test
    fun `terminal release scan crosses retained and stale first page without redispatch`() {
        val durable = io.mockk.mockk<DurableAgentAttemptService>()
        val resultService = io.mockk.mockk<io.whozoss.factory.agentattempt.service.AgentStepResultService>()
        val occupancy = io.mockk.mockk<io.whozoss.factory.worktree.WorktreeExecutionOccupancyService>()
        val adapter = io.mockk.mockk<AgentOsExecutionAdapter>(relaxed = true)
        val candidates = (1..1_025).map { index ->
            val row = attempt("terminal-$index").copy(
                status = AgentAttemptStatus.SUCCEEDED,
                environmentRef = "env",
                occupancyResourceIdentity = "resource-$index",
                occupancyOwnerKind = "agent",
                occupancyOwnerId = "owner-$index",
                occupancyGeneration = 1,
            )
            io.whozoss.factory.agentattempt.persistence.ScopedDurableAgentAttempt(scope, row)
        }
        io.mockk.every { durable.findTerminalWithOccupancyAfter(any(), any()) } answers {
            val after = firstArg<String?>()
            val limit = secondArg<Int>()
            val start = if (after == null) {
                0
            } else {
                candidates.indexOfFirst { candidate ->
                    io.whozoss.factory.agentattempt.persistence.DurableAgentAttemptNode.compositeId(
                        candidate.scope.organizationId,
                        candidate.scope.workstreamId,
                        candidate.attempt.namespaceId,
                        candidate.attempt.workflowId,
                        candidate.attempt.stepId,
                        candidate.attempt.attemptId,
                    ) == after
                } + 1
            }
            candidates.drop(start).take(limit)
        }
        io.mockk.every { durable.findNonTerminal(any()) } returns emptyList()
        candidates.dropLast(1).forEach { candidate ->
            io.mockk.every {
                resultService.acceptedResult(scope, namespace, "wf-recovery", "step-1", candidate.attempt.attemptId)
            } returns null
        }
        val eligible = candidates.last()
        io.mockk.every {
            resultService.acceptedResult(scope, namespace, "wf-recovery", "step-1", eligible.attempt.attemptId)
        } returns io.mockk.mockk {
            io.mockk.every { status } returns io.whozoss.factory.agentattempt.domain.AgentStepResultStatus.PASS
        }
        io.mockk.every { occupancy.releaseOwned(any()) } returns true

        BridgeRecoveryWorker(
            durable, adapter, resultService = resultService, worktreeOccupancyService = occupancy,
        ).recover(limit = 1)

        io.mockk.verify(exactly = 1) { occupancy.releaseOwned(match { it.resourceIdentity == "resource-1025" }) }
        io.mockk.verify(atLeast = 5) { durable.findTerminalWithOccupancyAfter(any(), any()) }
        io.mockk.verify(exactly = 0) { adapter.startTurn(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `terminal release cursor progresses across sweeps and wraps for a late accepted result`() {
        val durable = io.mockk.mockk<DurableAgentAttemptService>()
        val resultService = io.mockk.mockk<io.whozoss.factory.agentattempt.service.AgentStepResultService>()
        val occupancy = io.mockk.mockk<io.whozoss.factory.worktree.WorktreeExecutionOccupancyService>()
        val adapter = io.mockk.mockk<AgentOsExecutionAdapter>(relaxed = true)
        val candidates = (1..4_200).map { index ->
            io.whozoss.factory.agentattempt.persistence.ScopedDurableAgentAttempt(
                scope,
                attempt("cursor-${index.toString().padStart(5, '0')}").copy(
                    status = AgentAttemptStatus.SUCCEEDED,
                    environmentRef = "env",
                    occupancyResourceIdentity = "cursor-resource-$index",
                    occupancyOwnerKind = "agent",
                    occupancyOwnerId = "cursor-owner-$index",
                    occupancyGeneration = 1,
                ),
            )
        }
        fun idOf(candidate: io.whozoss.factory.agentattempt.persistence.ScopedDurableAgentAttempt) =
            io.whozoss.factory.agentattempt.persistence.DurableAgentAttemptNode.compositeId(
                candidate.scope.organizationId, candidate.scope.workstreamId,
                candidate.attempt.namespaceId, candidate.attempt.workflowId,
                candidate.attempt.stepId, candidate.attempt.attemptId,
            )
        val ordered = candidates.sortedBy(::idOf)
        io.mockk.every { durable.findTerminalWithOccupancyAfter(any(), any()) } answers {
            val after = firstArg<String?>()
            val size = secondArg<Int>()
            ordered.asSequence().filter { after == null || idOf(it) > after }.take(size).toList()
        }
        io.mockk.every { durable.findNonTerminal(any()) } returns emptyList()
        var lateAccepted = false
        ordered.forEach { candidate ->
            io.mockk.every {
                resultService.acceptedResult(scope, namespace, "wf-recovery", "step-1", candidate.attempt.attemptId)
            } answers {
                val accepted = candidate == ordered.last() || (lateAccepted && candidate == ordered.first())
                if (accepted) io.mockk.mockk {
                    io.mockk.every { status } returns io.whozoss.factory.agentattempt.domain.AgentStepResultStatus.PASS
                } else null
            }
        }
        io.mockk.every { occupancy.releaseOwned(any()) } returns true
        val worker = BridgeRecoveryWorker(
            durable, adapter, resultService = resultService, worktreeOccupancyService = occupancy,
        )

        worker.recover(limit = 1)
        io.mockk.verify(exactly = 0) { occupancy.releaseOwned(match { it.resourceIdentity == "cursor-resource-4200" }) }
        worker.recover(limit = 1)
        io.mockk.verify(exactly = 1) { occupancy.releaseOwned(match { it.resourceIdentity == "cursor-resource-4200" }) }

        lateAccepted = true
        worker.recover(limit = 1)
        io.mockk.verify(exactly = 1) { occupancy.releaseOwned(match { it.resourceIdentity == "cursor-resource-1" }) }
        io.mockk.verify(exactly = 0) { adapter.startTurn(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `mid page release limit preserves exact cursor for next sweep`() {
        val durable = io.mockk.mockk<DurableAgentAttemptService>()
        val results = io.mockk.mockk<io.whozoss.factory.agentattempt.service.AgentStepResultService>()
        val occupancy = io.mockk.mockk<io.whozoss.factory.worktree.WorktreeExecutionOccupancyService>()
        val adapter = io.mockk.mockk<AgentOsExecutionAdapter>(relaxed = true)
        val rows = (1..3).map { index ->
            io.whozoss.factory.agentattempt.persistence.ScopedDurableAgentAttempt(scope, attempt("mid-$index").copy(
                status = AgentAttemptStatus.INDETERMINATE, environmentRef = "env",
                occupancyResourceIdentity = "mid-resource-$index", occupancyOwnerKind = "agent",
                occupancyOwnerId = "owner-$index", occupancyGeneration = 1,
            ))
        }
        fun id(row: io.whozoss.factory.agentattempt.persistence.ScopedDurableAgentAttempt) =
            io.whozoss.factory.agentattempt.persistence.DurableAgentAttemptNode.compositeId(
                scope.organizationId, scope.workstreamId, namespace, "wf-recovery", "step-1", row.attempt.attemptId,
            )
        io.mockk.every { durable.findTerminalWithOccupancyAfter(any(), any()) } answers {
            val cursor = firstArg<String?>()
            rows.filter { cursor == null || id(it) > cursor }.take(secondArg())
        }
        io.mockk.every { durable.findNonTerminal(any()) } returns emptyList()
        io.mockk.every { results.acceptedResult(any(), any(), any(), any(), any()) } returns io.mockk.mockk(relaxed = true)
        io.mockk.every { occupancy.releaseOwned(any()) } returns true
        val worker = BridgeRecoveryWorker(durable, adapter, resultService = results, worktreeOccupancyService = occupancy)

        worker.recover(limit = 1)
        io.mockk.verify(exactly = 1) { occupancy.releaseOwned(any()) }
        worker.recover(limit = 1)
        io.mockk.verify(exactly = 1) { durable.findTerminalWithOccupancyAfter(id(rows.first()), any()) }
        io.mockk.verify(exactly = 1) { occupancy.releaseOwned(match { it.resourceIdentity == "mid-resource-2" }) }
        io.mockk.verify(exactly = 0) { occupancy.releaseOwned(match { it.resourceIdentity == "mid-resource-3" }) }
    }

    @Test
    fun `accepted result after timeout finalizes recovery without observing or starting another turn`() {
        assertAcceptedRecovery(AgentOsExecutionVerdict.Indeterminate("OBSERVATION_TIMEOUT"))
    }

    @Test
    fun `accepted PASS overrides runtime failure during recovery`() {
        assertAcceptedRecovery(AgentOsExecutionVerdict.Failed("RUNTIME_ERROR", "runtime failed after submission"))
    }

    @Test
    fun `accepted PASS overrides stale runtime human wait during recovery`() {
        assertAcceptedRecovery(AgentOsExecutionVerdict.WaitingHuman("stale-question"))
    }

    private fun assertAcceptedRecovery(runtimeVerdict: AgentOsExecutionVerdict) {
        val id = "accepted-timeout-recovery"
        attempts.register(scope, attempt(id))
        claimToRunning(id, "crashed", leaseTtlMs = 0)
        val results = io.mockk.mockk<io.whozoss.factory.agentattempt.service.AgentStepResultService>()
        io.mockk.every { results.acceptedResult(scope, namespace, "wf-recovery", "step-1", id) } returns io.mockk.mockk(relaxed = true) {
            io.mockk.every { status } returns io.whozoss.factory.agentattempt.domain.AgentStepResultStatus.PASS
        }
        val adapter = FakeAdapter(
            reconcileVerdict = { runtimeVerdict },
            observeVerdict = { error("accepted result must avoid re-observation") },
        )

        val report = BridgeRecoveryWorker(attempts, adapter, resultService = results).recover()

        assertThat(report.finalized).isEqualTo(1)
        assertThat(status(id)).isEqualTo(AgentAttemptStatus.SUCCEEDED)
        assertThat(adapter.observeCalls.get()).isZero()
        assertThat(adapter.startTurns).isEmpty()
    }

    @Test
    fun `a worker that lost its lease is fenced out of finalization`() {
        attempts.register(scope, attempt("attempt-fenced"))
        claimToRunning("attempt-fenced", "crashed-worker", leaseTtlMs = 0)
        val adapter = FakeAdapter(
            reconcileVerdict = { AgentOsExecutionVerdict.Succeeded(emptyMap()) },
            observeVerdict = { AgentOsExecutionVerdict.Succeeded(emptyMap()) },
        )
        // Recovery rotates the lease to a fresh owner and finalizes.
        BridgeRecoveryWorker(attempts, adapter).recover()
        assertThat(status("attempt-fenced")).isEqualTo(AgentAttemptStatus.SUCCEEDED)

        // The stale crashed worker can no longer finalize: its lease token diverged.
        val failure = assertThrows(AttemptLeaseFencingException::class.java) {
            attempts.finalize(
                scope,
                namespace,
                "wf-recovery",
                "step-1",
                "attempt-fenced",
                "crashed-worker",
                AgentAttemptStatus.SUCCEEDED,
            )
        }
        assertThat(failure.errorCode).isEqualTo("ATTEMPT_LEASE_FENCED")
    }
}
