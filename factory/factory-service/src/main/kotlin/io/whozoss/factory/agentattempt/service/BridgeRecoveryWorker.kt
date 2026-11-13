package io.whozoss.factory.agentattempt.service

import io.whozoss.factory.adapter.agentos.AgentOsExecutionAdapter
import io.whozoss.factory.adapter.agentos.AgentOsExecutionVerdict
import io.whozoss.factory.adapter.agentos.CaseEventView
import io.whozoss.factory.adapter.agentos.TrustedCaseBinding
import io.whozoss.factory.adapter.agentos.TurnToken
import io.whozoss.factory.adapter.agentos.AgentOsObservationResult
import io.whozoss.factory.adapter.agentos.VerdictDeriver
import io.whozoss.factory.agentattempt.domain.AgentAttemptStatus
import io.whozoss.factory.agentattempt.domain.AttemptClaimConflictException
import io.whozoss.factory.agentattempt.domain.DurableAgentAttempt
import io.whozoss.factory.agentattempt.domain.AgentStepResultObservedIdentity
import io.whozoss.factory.agentattempt.domain.ResultAttemptNotRefreshableException
import io.whozoss.factory.agentattempt.persistence.ScopedDurableAgentAttempt
import io.whozoss.factory.workflow.domain.WorkflowEvidenceItem
import io.whozoss.factory.workflow.persistence.WorkflowEvidenceRepository
import io.whozoss.factory.workflow.persistence.WorkflowRepository
import io.whozoss.factory.environment.persistence.WorkEnvironmentRepository
import io.whozoss.factory.worktree.ParentRunWorkspacePreflight
import io.whozoss.factory.worktree.AgentOsWorkspaceIdentityValidator
import io.whozoss.factory.worktree.WorktreeExecutionOccupancyHandle
import io.whozoss.factory.worktree.WorktreeExecutionOccupancyService
import io.whozoss.factory.workflow.sse.WorkflowSseHub
import java.util.UUID
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.workflow.domain.WorkflowInstanceRecord
import io.whozoss.factory.capability.CapabilityExecutionService
import io.whozoss.factory.proxy.AgentOsProxyClient
import io.whozoss.factory.workflow.service.SessionRunSubmissionService
import io.whozoss.factory.agentattempt.domain.AgentStepResultStatus
import io.whozoss.factory.agentattempt.persistence.DurableAgentAttemptNode
import org.springframework.context.annotation.Lazy
import org.springframework.scheduling.annotation.Scheduled
import mu.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/** Summary of one startup recovery sweep. */
data class RecoveryReport(
    val scanned: Int,
    val finalized: Int,
    val resumed: Int,
    val redriven: Int,
    val skipped: Int,
    val conflicted: Int,
)

/**
 * Factory boot recovery worker (Lot H, step 8).
 *
 * On `ApplicationReadyEvent` it sweeps every non-terminal durable agent attempt
 * across all tenant scopes and namespaces and closes the crash window of the
 * durable bridge:
 *
 *  1. reconcile the AgentOS case over REST (`reconcile`);
 *  2. if AgentOS already derived a terminal verdict, finalize the attempt under
 *     a fresh, lease-fenced owner token (no second turn is ever started);
 *  3. if the case is non-quiescent and the turn HAD started, resume SSE
 *     observation (replay + `eventId` dedup through the persistent high-water
 *     mark) and finalize on the observed verdict;
 *  4. ONLY when it is provable that `startTurn` was never accepted (the attempt
 *     is still `pending`, i.e. it was never even claimed), and the durable brief
 *     is known, re-drive the turn once — idempotent by `attemptId`, the durable
 *     command payload guarantees the exact same turn.
 *
 * A live lease owned by another worker is never stolen: the attempt is reported
 * as `conflicted`/skipped (`ATTEMPT_LEASE_FENCED` semantics). Recovery never
 * fabricates a success — a `SUCCEEDED` finalization only happens when AgentOS
 * actually reports it.
 *
 * The bean exists by default (and is absent only when `factory.adapter.agentos.enabled=false`),
 * so the default build runs the SSE bridge recovery sweep.
 */
@Component
@ConditionalOnProperty(prefix = "factory.adapter.agentos", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class BridgeRecoveryWorker(
    private val attempts: DurableAgentAttemptService,
    private val adapter: AgentOsExecutionAdapter,
    private val evidenceRepository: WorkflowEvidenceRepository? = null,
    private val sseHub: WorkflowSseHub? = null,
    private val resultService: AgentStepResultService? = null,
    private val observationTimeoutMs: Long = DEFAULT_OBSERVATION_TIMEOUT_MS,
    private val leaseTtlMs: Long = DEFAULT_LEASE_TTL_MS,
    private val workflowRepository: WorkflowRepository? = null,
    private val workEnvironmentRepository: WorkEnvironmentRepository? = null,
    private val workspaceIdentityValidator: AgentOsWorkspaceIdentityValidator? = null,
    private val worktreeOccupancyService: WorktreeExecutionOccupancyService? = null,
    @param:Lazy
    private val capabilityExecutionService: CapabilityExecutionService? = null,
    private val sessionRunSubmissionService: SessionRunSubmissionService? = null,
    private val agentOsProxyClient: AgentOsProxyClient? = null,
) {

    private val logger = KotlinLogging.logger {}
    private var terminalReleaseCursor: String? = null
    private val terminalReleaseLock = Any()

    private enum class Outcome { FINALIZED, WAITING, RESUMED, REDRIVEN, SKIPPED, CONFLICTED }

    @EventListener(ApplicationReadyEvent::class)
    fun onApplicationReady() {
        runCatching { recover() }
            .onFailure { logger.warn(it) { "Bridge startup recovery failed" } }
    }

    @Scheduled(
        fixedDelayString = "\${factory.recovery.terminal-release-sweep-interval-ms:60000}",
        initialDelayString = "\${factory.recovery.terminal-release-sweep-initial-delay-ms:60000}",
    )
    fun sweepTerminalOccupancies() {
        runCatching { releaseAcceptedTerminalOccupancies(DurableAgentAttemptService.DEFAULT_RECOVERY_LIMIT) }
            .onFailure { logger.warn(it) { "Periodic terminal occupancy release sweep failed" } }
    }

    /** Runs one recovery sweep; exposed for tests and diagnostics. */
    fun recover(limit: Int = DurableAgentAttemptService.DEFAULT_RECOVERY_LIMIT): RecoveryReport {
        releaseAcceptedTerminalOccupancies(limit)
        val scoped = runCatching { attempts.findNonTerminal(limit) }
            .onFailure { logger.warn(it) { "Bridge recovery could not list non-terminal attempts" } }
            .getOrElse { return RecoveryReport(0, 0, 0, 0, 0, 0) }

        var finalized = 0
        var resumed = 0
        var redriven = 0
        var skipped = 0
        var conflicted = 0
        for (candidate in scoped) {
            val outcome = runCatching { recoverAttempt(candidate) }
                .onFailure {
                    logger.warn(it) {
                        "Bridge recovery failed for attempt ${candidate.attempt.attemptId}"
                    }
                }
                .getOrElse { Outcome.SKIPPED }
            when (outcome) {
                Outcome.FINALIZED, Outcome.WAITING -> finalized++
                Outcome.RESUMED -> resumed++
                Outcome.REDRIVEN -> redriven++
                Outcome.SKIPPED -> skipped++
                Outcome.CONFLICTED -> conflicted++
            }
        }
        val report = RecoveryReport(scoped.size, finalized, resumed, redriven, skipped, conflicted)
        if (scoped.isNotEmpty()) logger.info { "Bridge startup recovery: $report" }
        return report
    }

    /** Completes the post-commit release crash window without re-dispatching terminal attempts. */
    private fun releaseAcceptedTerminalOccupancies(limit: Int) = synchronized(terminalReleaseLock) {
        val service = worktreeOccupancyService ?: return@synchronized
        val results = resultService ?: return@synchronized
        var remaining = limit
        var cursor = terminalReleaseCursor
        var scanned = 0
        var wrapped = false
        while (remaining > 0 && scanned < TERMINAL_RELEASE_SCAN_BUDGET) {
            val pageSize = minOf(TERMINAL_RELEASE_SCAN_PAGE, TERMINAL_RELEASE_SCAN_BUDGET - scanned)
            var page = attempts.findTerminalWithOccupancyAfter(cursor, pageSize)
            if (page.isEmpty() && cursor != null && !wrapped) {
                cursor = null
                wrapped = true
                page = attempts.findTerminalWithOccupancyAfter(null, pageSize)
            }
            if (page.isEmpty()) break
            for (candidate in page) {
                val attempt = candidate.attempt
                cursor = DurableAgentAttemptNode.compositeId(
                    candidate.scope.organizationId, candidate.scope.workstreamId,
                    attempt.namespaceId, attempt.workflowId, attempt.stepId, attempt.attemptId,
                )
                terminalReleaseCursor = cursor
                scanned++
                runCatching {
                    results.acceptedResult(candidate.scope, attempt.namespaceId, attempt.workflowId, attempt.stepId, attempt.attemptId)
                }.getOrNull() ?: continue
                val identity = attempt.occupancyResourceIdentity ?: continue
                val kind = attempt.occupancyOwnerKind ?: continue
                val owner = attempt.occupancyOwnerId ?: continue
                val generation = attempt.occupancyGeneration ?: continue
                if (service.releaseOwned(
                    WorktreeExecutionOccupancyHandle(
                        attempt.environmentRef ?: "", identity, candidate.scope, kind, owner, generation,
                    ),
                )) remaining--
                if (remaining <= 0) break
            }
            if (remaining <= 0) break
            if (page.size < pageSize && !wrapped) {
                cursor = null
                terminalReleaseCursor = null
                wrapped = true
            } else if (page.size < pageSize) {
                break
            }
        }
    }

    private fun recoverAttempt(candidate: ScopedDurableAgentAttempt): Outcome {
        val attempt = candidate.attempt
        // Guard: caseIds created by legacy versions of the service (e.g. Node.js)
        // may not be valid UUIDs (e.g. "case:wf-179"). AgentOS endpoints use
        // @PathVariable caseId: UUID and will reject non-UUID values with a
        // MethodArgumentTypeMismatchException. Skip these attempts gracefully
        // instead of crashing the whole recovery sweep.
        if (!isValidUuid(attempt.caseId)) {
            logger.warn {
                "Skipping recovery of attempt '${attempt.attemptId}': " +
                    "caseId '${attempt.caseId}' is not a valid UUID (legacy record)"
            }
            return Outcome.SKIPPED
        }
        val snapshot = runCatching { adapter.reconcileResult(TurnToken.unbaselined(attempt.caseId, attempt.attemptId)) }.getOrNull() ?: return Outcome.SKIPPED
        // A durable business result remains authoritative even if runtime reconciliation
        // reports failure or a stale human wait instead of an indeterminate observation.
        val authoritative = resolveAuthoritativeObservation(candidate, snapshot)
        return when (authoritative.verdict) {
            is AgentOsExecutionVerdict.Succeeded,
            is AgentOsExecutionVerdict.Failed,
            is AgentOsExecutionVerdict.Interrupted,
            -> finalizeWithFreshLease(candidate, authoritative)

            is AgentOsExecutionVerdict.WaitingHuman -> reconcileWaitingHuman(candidate, authoritative.verdict)

            is AgentOsExecutionVerdict.Indeterminate -> when {
                // Never claimed ⇒ `startTurn` could not have been accepted: the
                // only safe case in which a turn may be re-driven.
                attempt.status == AgentAttemptStatus.PENDING -> redrive(candidate)
                attempt.status in STARTED_STATUSES -> observeAndFinalize(candidate)
                else -> Outcome.SKIPPED
            }
        }
    }

    /** Claim a fresh, lease-fenced owner token, or null when a live lease wins. */
    private fun claimFresh(attempt: DurableAgentAttempt, scope: TenantScope): String? {
        val ownerToken = "recovery:${UUID.randomUUID()}"
        return try {
            attempts.claim(
                scope,
                attempt.namespaceId,
                attempt.workflowId,
                attempt.stepId,
                attempt.attemptId,
                ownerToken,
                leaseTtlMs = leaseTtlMs,
            )
            ownerToken
        } catch (_: AttemptClaimConflictException) {
            null
        }
    }

    private fun finalizeWithFreshLease(candidate: ScopedDurableAgentAttempt, observation: AgentOsObservationResult): Outcome {
        val verdict = observation.verdict
        val attempt = candidate.attempt
        val owner = claimFresh(attempt, candidate.scope) ?: return Outcome.CONFLICTED
        val target = when (verdict) {
            is AgentOsExecutionVerdict.Succeeded -> if (needsResearch(verdict)) AgentAttemptStatus.INDETERMINATE else AgentAttemptStatus.SUCCEEDED
            is AgentOsExecutionVerdict.Interrupted -> AgentAttemptStatus.INTERRUPTED
            else -> AgentAttemptStatus.FAILED
        }
        // A recovered success must walk the state machine: claiming -> starting ->
        // running -> succeeded (never a shortcut straight to success).
        if (target == AgentAttemptStatus.SUCCEEDED) {
            attempts.transition(candidate.scope, attempt.namespaceId, attempt.workflowId, attempt.stepId, attempt.attemptId, owner, AgentAttemptStatus.STARTING)
            attempts.transition(candidate.scope, attempt.namespaceId, attempt.workflowId, attempt.stepId, attempt.attemptId, owner, AgentAttemptStatus.RUNNING)
        }
        val continuation = recoveryContinuation(candidate)
        if (capabilityExecutionService?.finalizeRecoveredAttempt(candidate.scope, attempt, owner, observation, continuation) == true) {
            releaseOccupancyIfResultAccepted(candidate)
            return Outcome.FINALIZED
        }
        val evidenceId = persistEvidence(candidate, verdict, observation.runtimeProof)
        attempts.finalize(
            candidate.scope,
            attempt.namespaceId,
            attempt.workflowId,
            attempt.stepId,
            attempt.attemptId,
            owner,
            target,
            failureCode = failureCodeFor(verdict),
            resultEvidenceId = evidenceId,
        )
        continuation()
        releaseOccupancyIfResultAccepted(candidate)
        return Outcome.FINALIZED
    }

    private fun reconcileWaitingHuman(
        candidate: ScopedDurableAgentAttempt,
        verdict: AgentOsExecutionVerdict.WaitingHuman,
    ): Outcome {
        val attempt = candidate.attempt
        val questionId = (verdict.evidence["questionId"] as? String) ?: verdict.questionRef
        val events = runCatching { adapter.persistedEvents(attempt.caseId) }
            .getOrElse { error ->
                logger.warn(error) { waitingDiagnostic(attempt, questionId, "CASE_HISTORY_UNAVAILABLE") }
                return Outcome.SKIPPED
            }
        val question = questionId.let { id ->
            events.lastOrNull { it.type == CaseEventView.QUESTION_EVENT && it.eventId == id }
        }
        val answer = question?.let { persistedQuestion ->
            events.lastOrNull {
                it.type == CaseEventView.ANSWER_EVENT && it.answeredQuestionId == persistedQuestion.eventId
            }
        }
        if (answer == null) {
            logger.warn { waitingDiagnostic(attempt, questionId, if (question == null) "QUESTION_EVENT_NOT_FOUND" else "ANSWER_EVENT_NOT_FOUND") }
            return ensureWaiting(candidate)
        }

        if (attempt.status != AgentAttemptStatus.WAITING_HUMAN) return ensureWaiting(candidate)
        // Recovery may only mutate an orphaned attempt after its previous lease
        // expires. claimFresh deliberately preserves that fencing invariant.
        val owner = claimFresh(attempt, candidate.scope) ?: return Outcome.CONFLICTED
        // Claiming rotates the lease and deliberately resets the aggregate to
        // CLAIMING. Re-enter the normal lifecycle before resuming; skipping
        // STARTING would violate the attempt state machine.
        attempts.transition(
            candidate.scope, attempt.namespaceId, attempt.workflowId, attempt.stepId, attempt.attemptId,
            owner, AgentAttemptStatus.STARTING,
        )
        val running = attempts.transition(
            candidate.scope, attempt.namespaceId, attempt.workflowId, attempt.stepId, attempt.attemptId,
            owner, AgentAttemptStatus.RUNNING, lastObservedEventId = answer.eventId,
        )
        refreshCapabilityAfterHumanWait(candidate.scope, running, question.eventId)
        sseHub?.publish(
            candidate.scope,
            attempt.namespaceId,
            mapOf("workflowId" to attempt.workflowId, "namespaceId" to attempt.namespaceId),
        )
        return Outcome.RESUMED
    }

    private fun refreshCapabilityAfterHumanWait(
        scope: TenantScope,
        attempt: DurableAgentAttempt,
        questionId: String,
    ) {
        val service = resultService ?: return
        val oldToken = attempt.capabilityToken?.takeIf { it.isNotBlank() } ?: return
        val refreshKey = "agentos-query-user:${attempt.attemptId}:$questionId"
        if (service.resolveCapability(scope, oldToken)?.refreshKey == refreshKey) return
        val issued = try {
            service.refresh(
                scope,
                AgentStepResultObservedIdentity(
                    attempt.attemptId, attempt.caseId, attempt.agentName, attempt.namespaceId,
                ),
                AGENTOS_RUNTIME_ID,
                refreshKey,
            )
        } catch (_: ResultAttemptNotRefreshableException) {
            return
        }
        attempts.replaceCapability(scope, attempt, oldToken, issued.token)
    }

    private fun ensureWaiting(candidate: ScopedDurableAgentAttempt): Outcome {
        val attempt = candidate.attempt
        if (attempt.status == AgentAttemptStatus.WAITING_HUMAN) return Outcome.WAITING
        val owner = claimFresh(attempt, candidate.scope) ?: return Outcome.CONFLICTED
        val row = { target: AgentAttemptStatus ->
            attempts.transition(candidate.scope, attempt.namespaceId, attempt.workflowId, attempt.stepId, attempt.attemptId, owner, target)
        }
        row(AgentAttemptStatus.STARTING)
        row(AgentAttemptStatus.RUNNING)
        row(AgentAttemptStatus.WAITING_HUMAN)
        return Outcome.WAITING
    }

    private fun waitingDiagnostic(attempt: DurableAgentAttempt, questionId: String?, reason: String): String =
        "FACTORY_WAITING_HUMAN_RECONCILIATION_FAILED reason=$reason attemptId=${attempt.attemptId} " +
            "caseId=${attempt.caseId} questionId=${questionId ?: "<missing>"}"

    private fun observeAndFinalize(candidate: ScopedDurableAgentAttempt): Outcome {
        val attempt = candidate.attempt
        val owner = claimFresh(attempt, candidate.scope) ?: return Outcome.CONFLICTED
        attempts.transition(candidate.scope, attempt.namespaceId, attempt.workflowId, attempt.stepId, attempt.attemptId, owner, AgentAttemptStatus.STARTING)
        attempts.transition(candidate.scope, attempt.namespaceId, attempt.workflowId, attempt.stepId, attempt.attemptId, owner, AgentAttemptStatus.RUNNING)
        val observation = runCatching {
            adapter.observeTurnResult(TurnToken.unbaselined(attempt.caseId, attempt.attemptId), observationTimeoutMs)
        }.getOrElse {
            AgentOsObservationResult(
                AgentOsExecutionVerdict.Indeterminate(
                    reason = "AGENT_OBSERVATION_ERROR: ${it.message ?: it.toString()}",
                    evidence = mapOf("caseId" to attempt.caseId, "attemptId" to attempt.attemptId),
                ),
            )
        }
        applyResumedVerdict(candidate, owner, resolveAuthoritativeObservation(candidate, observation))
        return Outcome.RESUMED
    }

    private fun redrive(candidate: ScopedDurableAgentAttempt): Outcome {
        val attempt = candidate.attempt
        val brief = attempt.brief ?: return Outcome.SKIPPED
        val owner = claimFresh(attempt, candidate.scope) ?: return Outcome.CONFLICTED
        // The binding carries the full trusted identity of the attempt —
        // including the Lot B case-family parent — so a re-driven turn lands in
        // the exact same case family it was originally dispatched into.
        val workflows = workflowRepository
        val instance = workflows?.findInstance(candidate.scope, attempt.namespaceId, attempt.workflowId)
        if (workflows == null || instance == null) {
            // Recovery cannot prove that an unknown workflow is autonomous; never create or redrive.
            return Outcome.SKIPPED
        }
        acquireRecoveryOccupancy(candidate)
        val boundAttempt = attempts.find(candidate.scope, attempt.namespaceId, attempt.workflowId, attempt.stepId, attempt.attemptId)
            ?: error("Recovery attempt disappeared after occupancy binding")
        val externalUserId = resolveExternalUserId(candidate.scope, instance, attempt.environmentRef)
        val binding = TrustedCaseBinding(
            caseId = attempt.caseId,
            attemptId = attempt.attemptId,
            namespaceId = attempt.namespaceId,
            parentCaseId = attempt.parentCaseId,
            capabilityToken = attempt.capabilityToken,
            agentName = attempt.agentName,
            environmentRef = attempt.environmentRef,
            environmentRevision = attempt.expectedEnvironmentRevision,
            externalUserId = externalUserId,
        )
        ParentRunWorkspacePreflight(workEnvironmentRepository, workspaceIdentityValidator).validate(
            scope = candidate.scope, namespaceId = attempt.namespaceId, instance = instance,
            attempt = boundAttempt, binding = binding.copy(caseId = instance.rootCaseId ?: binding.caseId),
        )
        val handle = adapter.createOrRecoverExecution(binding, attempt.workflowId, attempt.stepId)
        val executionBinding = binding.copy(caseId = handle.caseId)
        ParentRunWorkspacePreflight(workEnvironmentRepository, workspaceIdentityValidator).validate(
            scope = candidate.scope,
            namespaceId = attempt.namespaceId,
            instance = instance,
            attempt = boundAttempt,
            binding = executionBinding,
        )
        attempts.transition(candidate.scope, attempt.namespaceId, attempt.workflowId, attempt.stepId, attempt.attemptId, owner, AgentAttemptStatus.STARTING)
        runCatching {
            adapter.startTurn(executionBinding, attempt.agentName, brief)
        }
        attempts.transition(candidate.scope, attempt.namespaceId, attempt.workflowId, attempt.stepId, attempt.attemptId, owner, AgentAttemptStatus.RUNNING)
        val observation = runCatching {
            adapter.observeTurnResult(TurnToken.unbaselined(attempt.caseId, attempt.attemptId), observationTimeoutMs)
        }.getOrElse {
            AgentOsObservationResult(
                AgentOsExecutionVerdict.Indeterminate(
                    reason = "AGENT_OBSERVATION_ERROR: ${it.message ?: it.toString()}",
                    evidence = mapOf("caseId" to attempt.caseId, "attemptId" to attempt.attemptId),
                ),
            )
        }
        applyResumedVerdict(candidate, owner, resolveAuthoritativeObservation(candidate, observation))
        return Outcome.REDRIVEN
    }

    private fun acquireRecoveryOccupancy(candidate: ScopedDurableAgentAttempt) {
        val original = candidate.attempt
        val attempt = attempts.find(candidate.scope, original.namespaceId, original.workflowId, original.stepId, original.attemptId)
            ?: error("Claimed recovery attempt disappeared before occupancy binding")
        val instance = workflowRepository?.findInstance(candidate.scope, attempt.namespaceId, attempt.workflowId) ?: return
        val parentRun = (instance.instance["runId"] as? String)
            ?: ((instance.instance["relations"] as? Map<*, *>)?.get("runId") as? String)
            ?: return
        if (parentRun.isBlank()) return
        val service = worktreeOccupancyService
            ?: error("Worktree execution occupancy service is required for parent Run recovery")
        val environment = attempt.environmentRef
            ?: error("Parent Run recovery attempt has no captured environmentRef")
        val revision = attempt.expectedEnvironmentRevision
            ?: error("Parent Run recovery attempt has no captured environment revision")
        service.acquireAndBindForAttemptEnvironment(
            candidate.scope, environment, revision, attempt,
        ) { handle ->
            attempts.bindOccupancy(
                candidate.scope, attempt, handle.resourceIdentity, handle.ownerKind, handle.ownerId, handle.generation,
            )
        }
    }

    /** Applies the verdict observed while already owning a fenced lease. */
    private fun applyResumedVerdict(candidate: ScopedDurableAgentAttempt, owner: String, observation: AgentOsObservationResult) {
        val verdict = observation.verdict
        val attempt = candidate.attempt
        when (verdict) {
            is AgentOsExecutionVerdict.WaitingHuman -> attempts.transition(
                candidate.scope, attempt.namespaceId, attempt.workflowId, attempt.stepId, attempt.attemptId, owner,
                AgentAttemptStatus.WAITING_HUMAN,
            )
            else -> {
                val target = when (verdict) {
                    is AgentOsExecutionVerdict.Succeeded -> if (needsResearch(verdict)) AgentAttemptStatus.INDETERMINATE else AgentAttemptStatus.SUCCEEDED
                    is AgentOsExecutionVerdict.Interrupted -> AgentAttemptStatus.INTERRUPTED
                    is AgentOsExecutionVerdict.Failed -> AgentAttemptStatus.FAILED
                    is AgentOsExecutionVerdict.Indeterminate -> AgentAttemptStatus.INDETERMINATE
                    is AgentOsExecutionVerdict.WaitingHuman -> error("handled above")
                }
                val continuation = recoveryContinuation(candidate)
                if (capabilityExecutionService?.finalizeRecoveredAttempt(candidate.scope, attempt, owner, observation, continuation) == true) {
                    releaseOccupancyIfResultAccepted(candidate)
                    return
                }
                val evidenceId = persistEvidence(candidate, verdict, observation.runtimeProof)
                attempts.finalize(
                    candidate.scope, attempt.namespaceId, attempt.workflowId, attempt.stepId, attempt.attemptId, owner,
                    target, failureCode = failureCodeFor(verdict), resultEvidenceId = evidenceId,
                )
                continuation()
                releaseOccupancyIfResultAccepted(candidate)
            }
        }
    }

    /** Records an `agent-result` evidence fact (best-effort: recovery still finalizes without it). */
    private fun persistEvidence(candidate: ScopedDurableAgentAttempt, verdict: AgentOsExecutionVerdict, runtimeProof: VerdictDeriver.RuntimeCaseTerminationProof? = null): String? {
        val repository = evidenceRepository ?: return null
        val attempt = candidate.attempt
        val (outcome, facts) = when (verdict) {
            is AgentOsExecutionVerdict.Succeeded ->
                (if (needsResearch(verdict)) "needs_research" else "pass") to mapOf(
                    "status" to if (needsResearch(verdict)) "NEEDS_RESEARCH" else "PASS",
                    "outputs" to verdict.outputs, "evidence" to verdict.evidence,
                )
            is AgentOsExecutionVerdict.Interrupted ->
                "fail" to mapOf("status" to "INTERRUPTED", "reason" to verdict.reason, "evidence" to verdict.evidence)
            is AgentOsExecutionVerdict.Failed ->
                "fail" to mapOf("status" to "FAILED", "code" to verdict.code, "message" to verdict.message, "evidence" to verdict.evidence)
            is AgentOsExecutionVerdict.Indeterminate ->
                "fail" to mapOf("status" to "INDETERMINATE", "reason" to verdict.reason, "evidence" to verdict.evidence)
            is AgentOsExecutionVerdict.WaitingHuman -> return null
        }
        val evidenceId = UUID.randomUUID().toString()
        return runCatching {
            repository.append(
                candidate.scope,
                attempt.namespaceId,
                attempt.workflowId,
                WorkflowEvidenceItem(
                    evidenceId = evidenceId,
                    namespaceId = attempt.namespaceId,
                    workflowId = attempt.workflowId,
                    stepId = attempt.stepId,
                    kind = AGENT_RESULT_EVIDENCE_KIND,
                    outcome = outcome,
                    source = mapOf("kind" to "agentos-adapter", "attemptId" to attempt.attemptId, "recovered" to true),
                    facts = facts + mapOf("attemptId" to attempt.attemptId, "stepId" to attempt.stepId) + runtimeProof?.let { mapOf("runtimeCaseTerminationProof" to mapOf("caseId" to it.caseId, "attemptId" to it.attemptId, "eventId" to it.eventId, "status" to it.status)) }.orEmpty(),
                    idempotencyKey = null,
                    createdAt = null,
                ),
            )
            evidenceId
        }.getOrNull()
    }

    private fun needsResearch(verdict: AgentOsExecutionVerdict.Succeeded) = verdict.evidence["status"] == "NEEDS_RESEARCH"

    private fun resolveAuthoritativeObservation(
        candidate: ScopedDurableAgentAttempt,
        raw: AgentOsObservationResult,
    ): AgentOsObservationResult {
        val attempt = candidate.attempt
        val accepted = resultService?.acceptedResult(
            candidate.scope, attempt.namespaceId, attempt.workflowId, attempt.stepId, attempt.attemptId,
        ) ?: return raw
        val evidence = mapOf("resultId" to accepted.resultId, "source" to "factory-step-result")
        val verdict = when (accepted.status) {
            AgentStepResultStatus.FAIL -> AgentOsExecutionVerdict.Failed(
                code = "FACTORY_STEP_RESULT_FAILED", message = accepted.summary, evidence = evidence,
            )
            AgentStepResultStatus.PASS,
            AgentStepResultStatus.NEEDS_RESEARCH,
            -> AgentOsExecutionVerdict.Succeeded(
                outputs = mapOf(
                    "summary" to accepted.summary, "claims" to accepted.claims,
                    "findings" to accepted.findings, "artifacts" to accepted.artifacts,
                ).let { outputs -> accepted.selectedRoutes?.let { outputs + ("selectedRoutes" to it) } ?: outputs },
                evidence = evidence + if (accepted.status == AgentStepResultStatus.NEEDS_RESEARCH) {
                    mapOf("status" to "NEEDS_RESEARCH")
                } else emptyMap(),
            )
        }
        return AgentOsObservationResult(verdict, raw.runtimeProof)
    }

    /** Recovery must advance the DAG even if its original result event was already drained. */
    private fun recoveryContinuation(candidate: ScopedDurableAgentAttempt): () -> Unit {
        val submitter = sessionRunSubmissionService ?: return {}
        // Resolve remote namespace context BEFORE the short finalization transaction.
        val attempt = candidate.attempt
        val instance = workflowRepository?.findInstance(candidate.scope, attempt.namespaceId, attempt.workflowId)
        val actor = resolveExternalUserId(candidate.scope, instance, attempt.environmentRef)
        val root = agentOsProxyClient?.resolveRepoRoot(attempt.namespaceId, actor)
            ?.takeIf { it.isNotBlank() } ?: error("Recovery continuation requires the namespace repository root")
        return {
            submitter.submit(candidate.scope, attempt.namespaceId, attempt.workflowId, root, "attempt-recovered:${attempt.attemptId}", null)
            Unit
        }
    }

    private fun releaseOccupancyIfResultAccepted(candidate: ScopedDurableAgentAttempt) {
        val service = worktreeOccupancyService ?: return
        runCatching {
            val original = candidate.attempt
            if (resultService?.acceptedResult(candidate.scope, original.namespaceId, original.workflowId, original.stepId, original.attemptId) == null) return
            val attempt = attempts.find(candidate.scope, original.namespaceId, original.workflowId, original.stepId, original.attemptId) ?: return
            service.releaseOwned(
                WorktreeExecutionOccupancyHandle(
                    attempt.environmentRef ?: "", attempt.occupancyResourceIdentity ?: return, candidate.scope,
                    attempt.occupancyOwnerKind ?: return, attempt.occupancyOwnerId ?: return,
                    attempt.occupancyGeneration ?: return,
                ),
            )
        }.onFailure { logger.warn(it) { "Recovered attempt occupancy retained: release failed" } }
    }

    private fun failureCodeFor(verdict: AgentOsExecutionVerdict): String? = when (verdict) {
        is AgentOsExecutionVerdict.Succeeded -> if (needsResearch(verdict)) "AGENT_NEEDS_RESEARCH" else null
        is AgentOsExecutionVerdict.WaitingHuman -> null
        is AgentOsExecutionVerdict.Failed -> verdict.code
        is AgentOsExecutionVerdict.Interrupted -> "AGENT_INTERRUPTED"
        is AgentOsExecutionVerdict.Indeterminate -> "AGENT_INDETERMINATE"
    }

    private fun resolveExternalUserId(
        scope: TenantScope,
        instance: WorkflowInstanceRecord?,
        environmentRef: String?,
    ): String? {
        if (instance != null) {
            val controllerExecution = instance.instance["controllerExecution"] as? Map<*, *>
            (controllerExecution?.get("actorId") as? String)?.takeIf { it.isNotBlank() }?.let { return it }
            val controllerRequest = instance.instance["controllerRequest"] as? Map<*, *>
            (controllerRequest?.get("actorId") as? String)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        if (!environmentRef.isNullOrBlank()) {
            val env = workEnvironmentRepository?.findByEnvironmentId(scope, environmentRef)
            env?.createdBy?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    companion object {
        val STARTED_STATUSES = setOf(
            AgentAttemptStatus.STARTING,
            AgentAttemptStatus.RUNNING,
            AgentAttemptStatus.WAITING_HUMAN,
        )

        const val AGENT_RESULT_EVIDENCE_KIND = "agent-result"
        const val DEFAULT_OBSERVATION_TIMEOUT_MS = 600_000L
        const val DEFAULT_LEASE_TTL_MS = 3_600_000L
        private const val AGENTOS_RUNTIME_ID = "agentos-primary"
        private const val TERMINAL_RELEASE_SCAN_PAGE = 256
        private const val TERMINAL_RELEASE_SCAN_BUDGET = 4_096

        /**
         * Returns true when [value] is a syntactically valid UUID. Used to
         * guard against legacy caseIds (e.g. "case:wf-179") that cannot be
         * sent to AgentOS endpoints which expect `@PathVariable id: UUID`.
         */
        fun isValidUuid(value: String): Boolean = try {
            UUID.fromString(value)
            true
        } catch (_: IllegalArgumentException) {
            false
        }
    }
}
