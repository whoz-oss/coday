package io.whozoss.factory.agentattempt.service

import io.whozoss.factory.adapter.agentos.AgentOsExecutionAdapter
import io.whozoss.factory.adapter.agentos.AgentOsExecutionVerdict
import io.whozoss.factory.adapter.agentos.CaseEventView
import io.whozoss.factory.agentattempt.domain.AgentAttemptStatus
import io.whozoss.factory.agentattempt.domain.AttemptClaimConflictException
import io.whozoss.factory.agentattempt.domain.DurableAgentAttempt
import io.whozoss.factory.agentattempt.persistence.ScopedDurableAgentAttempt
import io.whozoss.factory.workflow.domain.WorkflowEvidenceItem
import io.whozoss.factory.workflow.persistence.WorkflowEvidenceRepository
import io.whozoss.factory.workflow.sse.WorkflowSseHub
import java.util.UUID
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
    private val observationTimeoutMs: Long = DEFAULT_OBSERVATION_TIMEOUT_MS,
    private val leaseTtlMs: Long = DEFAULT_LEASE_TTL_MS,
) {

    private val logger = KotlinLogging.logger {}

    private enum class Outcome { FINALIZED, WAITING, RESUMED, REDRIVEN, SKIPPED, CONFLICTED }

    @EventListener(ApplicationReadyEvent::class)
    fun onApplicationReady() {
        runCatching { recover() }
            .onFailure { logger.warn(it) { "Bridge startup recovery failed" } }
    }

    /** Runs one recovery sweep; exposed for tests and diagnostics. */
    fun recover(limit: Int = DurableAgentAttemptService.DEFAULT_RECOVERY_LIMIT): RecoveryReport {
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

    private fun recoverAttempt(candidate: ScopedDurableAgentAttempt): Outcome {
        val attempt = candidate.attempt
        val snapshot = runCatching { adapter.reconcile(attempt.caseId) }.getOrNull() ?: return Outcome.SKIPPED
        return when (snapshot) {
            is AgentOsExecutionVerdict.Succeeded,
            is AgentOsExecutionVerdict.Failed,
            is AgentOsExecutionVerdict.Interrupted,
            -> finalizeWithFreshLease(candidate, snapshot)

            is AgentOsExecutionVerdict.WaitingHuman -> reconcileWaitingHuman(candidate, snapshot)

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
    private fun claimFresh(attempt: DurableAgentAttempt, scope: io.whozoss.factory.persistence.TenantScope): String? {
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

    private fun finalizeWithFreshLease(candidate: ScopedDurableAgentAttempt, verdict: AgentOsExecutionVerdict): Outcome {
        val attempt = candidate.attempt
        val owner = claimFresh(attempt, candidate.scope) ?: return Outcome.CONFLICTED
        val target = when (verdict) {
            is AgentOsExecutionVerdict.Succeeded -> AgentAttemptStatus.SUCCEEDED
            is AgentOsExecutionVerdict.Interrupted -> AgentAttemptStatus.INTERRUPTED
            else -> AgentAttemptStatus.FAILED
        }
        // A recovered success must walk the state machine: claiming -> starting ->
        // running -> succeeded (never a shortcut straight to success).
        if (target == AgentAttemptStatus.SUCCEEDED) {
            attempts.transition(candidate.scope, attempt.namespaceId, attempt.workflowId, attempt.stepId, attempt.attemptId, owner, AgentAttemptStatus.STARTING)
            attempts.transition(candidate.scope, attempt.namespaceId, attempt.workflowId, attempt.stepId, attempt.attemptId, owner, AgentAttemptStatus.RUNNING)
        }
        val evidenceId = persistEvidence(candidate, verdict)
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
        val question = questionId?.let { id ->
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
        attempts.transition(
            candidate.scope, attempt.namespaceId, attempt.workflowId, attempt.stepId, attempt.attemptId,
            owner, AgentAttemptStatus.RUNNING, lastObservedEventId = answer.eventId,
        )
        sseHub?.publish(
            candidate.scope,
            attempt.namespaceId,
            mapOf("workflowId" to attempt.workflowId, "namespaceId" to attempt.namespaceId),
        )
        return Outcome.RESUMED
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
        val verdict = runCatching { adapter.observeTurn(attempt.caseId, attempt.attemptId, observationTimeoutMs) }
            .getOrElse {
                AgentOsExecutionVerdict.Indeterminate(
                    reason = "AGENT_OBSERVATION_ERROR: ${it.message ?: it.toString()}",
                    evidence = mapOf("caseId" to attempt.caseId, "attemptId" to attempt.attemptId),
                )
            }
        applyResumedVerdict(candidate, owner, verdict)
        return Outcome.RESUMED
    }

    private fun redrive(candidate: ScopedDurableAgentAttempt): Outcome {
        val attempt = candidate.attempt
        val brief = attempt.brief ?: return Outcome.SKIPPED
        val owner = claimFresh(attempt, candidate.scope) ?: return Outcome.CONFLICTED
        adapter.createOrRecoverExecution(
            namespaceId = attempt.namespaceId,
            workflowId = attempt.workflowId,
            stepId = attempt.stepId,
            externalUserId = null,
            attemptId = attempt.attemptId,
            capabilityToken = attempt.capabilityToken,
            caseId = attempt.caseId,
        )
        attempts.transition(candidate.scope, attempt.namespaceId, attempt.workflowId, attempt.stepId, attempt.attemptId, owner, AgentAttemptStatus.STARTING)
        runCatching {
            adapter.startTurn(attempt.caseId, attempt.agentName, brief, null, attempt.attemptId, attempt.capabilityToken)
        }
        attempts.transition(candidate.scope, attempt.namespaceId, attempt.workflowId, attempt.stepId, attempt.attemptId, owner, AgentAttemptStatus.RUNNING)
        val verdict = runCatching { adapter.observeTurn(attempt.caseId, attempt.attemptId, observationTimeoutMs) }
            .getOrElse {
                AgentOsExecutionVerdict.Indeterminate(
                    reason = "AGENT_OBSERVATION_ERROR: ${it.message ?: it.toString()}",
                    evidence = mapOf("caseId" to attempt.caseId, "attemptId" to attempt.attemptId),
                )
            }
        applyResumedVerdict(candidate, owner, verdict)
        return Outcome.REDRIVEN
    }

    /** Applies the verdict observed while already owning a fenced lease. */
    private fun applyResumedVerdict(candidate: ScopedDurableAgentAttempt, owner: String, verdict: AgentOsExecutionVerdict) {
        val attempt = candidate.attempt
        when (verdict) {
            is AgentOsExecutionVerdict.WaitingHuman -> attempts.transition(
                candidate.scope, attempt.namespaceId, attempt.workflowId, attempt.stepId, attempt.attemptId, owner,
                AgentAttemptStatus.WAITING_HUMAN,
            )
            else -> {
                val target = when (verdict) {
                    is AgentOsExecutionVerdict.Succeeded -> AgentAttemptStatus.SUCCEEDED
                    is AgentOsExecutionVerdict.Interrupted -> AgentAttemptStatus.INTERRUPTED
                    is AgentOsExecutionVerdict.Failed -> AgentAttemptStatus.FAILED
                    is AgentOsExecutionVerdict.Indeterminate -> AgentAttemptStatus.INDETERMINATE
                    is AgentOsExecutionVerdict.WaitingHuman -> error("handled above")
                }
                val evidenceId = persistEvidence(candidate, verdict)
                attempts.finalize(
                    candidate.scope, attempt.namespaceId, attempt.workflowId, attempt.stepId, attempt.attemptId, owner,
                    target, failureCode = failureCodeFor(verdict), resultEvidenceId = evidenceId,
                )
            }
        }
    }

    /** Records an `agent-result` evidence fact (best-effort: recovery still finalizes without it). */
    private fun persistEvidence(candidate: ScopedDurableAgentAttempt, verdict: AgentOsExecutionVerdict): String? {
        val repository = evidenceRepository ?: return null
        val attempt = candidate.attempt
        val (outcome, facts) = when (verdict) {
            is AgentOsExecutionVerdict.Succeeded ->
                "pass" to mapOf("status" to "PASS", "outputs" to verdict.outputs, "evidence" to verdict.evidence)
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
                    facts = facts + mapOf("attemptId" to attempt.attemptId, "stepId" to attempt.stepId),
                    idempotencyKey = null,
                    createdAt = null,
                ),
            )
            evidenceId
        }.getOrNull()
    }

    private fun failureCodeFor(verdict: AgentOsExecutionVerdict): String? = when (verdict) {
        is AgentOsExecutionVerdict.Succeeded, is AgentOsExecutionVerdict.WaitingHuman -> null
        is AgentOsExecutionVerdict.Failed -> verdict.code
        is AgentOsExecutionVerdict.Interrupted -> "AGENT_INTERRUPTED"
        is AgentOsExecutionVerdict.Indeterminate -> "AGENT_INDETERMINATE"
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
    }
}
