package io.whozoss.factory.agentattempt.service

import io.whozoss.factory.adapter.agentos.AgentOsExecutionAdapter
import io.whozoss.factory.adapter.agentos.AgentOsExecutionVerdict
import io.whozoss.factory.adapter.agentos.KillResult
import io.whozoss.factory.agentattempt.domain.AgentAttemptStatus
import io.whozoss.factory.agentattempt.domain.AttemptNotFoundException
import io.whozoss.factory.agentattempt.domain.DurableAgentAttempt
import io.whozoss.factory.agentattempt.domain.InvalidAttemptTransitionException
import io.whozoss.factory.capability.CapabilityExecutionService
import io.whozoss.factory.error.RevisionConflictException
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.workflow.domain.WorkflowErrorCodes
import io.whozoss.factory.workflow.domain.WorkflowEvidenceItem
import io.whozoss.factory.workflow.domain.WorkflowException
import io.whozoss.factory.workflow.persistence.WorkflowEvidenceRepository
import io.whozoss.factory.workflow.persistence.WorkflowRepository
import io.whozoss.factory.worktree.WorktreeExecutionOccupancyHandle
import io.whozoss.factory.worktree.WorktreeExecutionOccupancyService
import java.util.UUID
import mu.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/** Result of an explicit business cancellation request. */
data class CancellationOutcome(
    val attemptId: String,
    val workflowId: String,
    val stepId: String,
    val status: AgentAttemptStatus,
    val revision: Int,
    /** True when the attempt was already `interrupted` and the request was a replay (a retry of the Stop). */
    val idempotent: Boolean,
    /** The verdict derived from the post-kill REST reconciliation, when available. */
    val reconciledVerdict: String?,
    /** What AgentOS answered to the kill sent by this request. */
    val killResult: KillResult,
    /** True only when the kill was acknowledged AND AgentOS reports the case `KILLED`. */
    val killProven: Boolean,
)

/**
 * Explicit business cancellation of a durable agent attempt (Lot H, step 8; WZ-36379).
 *
 * Cancellation is a control-plane command, never a side effect of the SSE
 * observation. Closing the SSE stream or a browser tab merely stops *observing*;
 * it does NOT cancel the run. Only an explicit `requestCancel` (through
 * `POST /api/factory/workflows/{workflowId}/attempts/{attemptId}/cancel`) drives:
 *
 *  - **Authorization, delegated to AgentOS.** The kill is sent with the caller's identity and AgentOS decides
 *    (`Case` `DELETE`: the run owner, a namespace admin…). For the run owner (the persisted
 *    `controllerRequest.actorId`) the durable cancel is committed FIRST, so a live sequencer that observes the
 *    kill finds itself fenced and never finalizes an ordinary failure. For anyone else the kill goes first and
 *    is the access check: a refusal is `403 STOP_NOT_AUTHORIZED`, an unreachable AgentOS is
 *    `503 STOP_RUNTIME_UNAVAILABLE`, and in both cases nothing was written.
 *  - A revision-fenced durable transition to `INTERRUPTED` tagged `USER_CANCELLED` (the lease owner token is
 *    rotated, fencing any in-flight worker out of finalization). A stale revision or an attempt that already
 *    reached another terminal status is a 409 and never kills.
 *  - `adapter.abandonObservation`: the sequencer, blocked observing the case, is released within a second even
 *    when the kill was refused or AgentOS is down, so the run leaves `running`.
 *  - A post-kill status read: the kill is **proven** only when it was acknowledged and AgentOS reports the case
 *    `KILLED`. The worktree occupancy is released as soon as it is proven, and otherwise retained (fail-closed)
 *    until the periodic sweep of the recovery worker proves it.
 *  - An `agent-stop` evidence recording each Stop and whether its kill is proven, which feeds the read model
 *    (`AGENT_KILL_UNCONFIRMED`, "Retry stop").
 *
 * A Stop on an attempt that is already `USER_CANCELLED` is a retry: it sends the kill again (unless the case is
 * already `KILLED`), and can release the occupancy once the kill is proven.
 *
 * The remote calls run with NO active transaction; only the durable write is transactional.
 */
@Component
@ConditionalOnProperty(prefix = "factory.adapter.agentos", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class BridgeCancellationService(
    private val attempts: DurableAgentAttemptService,
    private val adapter: AgentOsExecutionAdapter,
    private val workflowRepository: WorkflowRepository? = null,
    private val worktreeOccupancyService: WorktreeExecutionOccupancyService? = null,
    private val evidenceRepository: WorkflowEvidenceRepository? = null,
) {

    private val logger = KotlinLogging.logger {}

    fun requestCancel(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        attemptId: String,
        expectedRevision: Int?,
        reason: String = DEFAULT_REASON,
        actorId: String,
    ): CancellationOutcome {
        val attempt = attempts.findByAttemptId(scope, namespaceId, workflowId, attemptId)
            ?: throw AttemptNotFoundException(
                "Durable attempt '$attemptId' not found",
                details = mapOf("namespaceId" to namespaceId, "workflowId" to workflowId, "attemptId" to attemptId),
            )
        val isOwner = runOwner(scope, namespaceId, workflowId) == actorId
        val interruptReason = "${CapabilityExecutionService.USER_CANCELLED}: $reason"

        return when {
            attempt.status == AgentAttemptStatus.INTERRUPTED ->
                retryStop(scope, attempt, actorId, isOwner, interruptReason)
            attempt.status.terminal -> throw InvalidAttemptTransitionException(
                "Attempt '$attemptId' is already terminal as '${attempt.status.dbValue}' and cannot be cancelled",
                details = mapOf("attemptId" to attemptId, "status" to attempt.status.dbValue),
            )
            else -> firstStop(scope, attempt, expectedRevision, actorId, isOwner, interruptReason)
        }
    }

    /** The first Stop of a live attempt. Revision and terminal status are checked before any remote call. */
    private fun firstStop(
        scope: TenantScope,
        attempt: DurableAgentAttempt,
        expectedRevision: Int?,
        actorId: String,
        isOwner: Boolean,
        interruptReason: String,
    ): CancellationOutcome {
        if (expectedRevision != null && expectedRevision != attempt.revision) {
            throw RevisionConflictException(
                "Attempt '${attempt.attemptId}' is at revision ${attempt.revision}, expected $expectedRevision",
                details = mapOf(
                    "attemptId" to attempt.attemptId,
                    "currentRevision" to attempt.revision,
                    "expectedRevision" to expectedRevision,
                ),
            )
        }
        // The run owner: the durable cancel comes first (a sequencer observing the kill is then fenced), and the
        // kill is sent as the owner. Anyone else: the kill, sent as the caller, is the access check, so it goes
        // first and nothing is written unless AgentOS accepts it.
        val killResult: KillResult
        val cancelled: DurableAgentAttempt
        if (isOwner) {
            cancelled = durableCancel(scope, attempt, expectedRevision)
            killResult = adapter.interrupt(attempt.caseId, interruptReason, actorId)
        } else {
            killResult = requireAuthorized(adapter.interrupt(attempt.caseId, interruptReason, actorId), attempt)
            cancelled = durableCancel(scope, attempt, expectedRevision)
        }
        return conclude(scope, cancelled, attempt, actorId, killResult, idempotent = false)
    }

    /**
     * A Stop on an attempt a Stop already cancelled: a retry. The kill is sent again, except for the owner when
     * AgentOS already reports the case `KILLED`. A non-owner's retry always sends it, since it is their access check.
     */
    private fun retryStop(
        scope: TenantScope,
        attempt: DurableAgentAttempt,
        actorId: String,
        isOwner: Boolean,
        interruptReason: String,
    ): CancellationOutcome {
        if (attempt.failureCode != CapabilityExecutionService.USER_CANCELLED) {
            // Interrupted for another reason: nothing of this Stop to retry.
            return CancellationOutcome(
                attempt.attemptId, attempt.workflowId, attempt.stepId, attempt.status, attempt.revision,
                idempotent = true, reconciledVerdict = null, killResult = KillResult.UNREACHABLE, killProven = false,
            )
        }
        val alreadyKilled = isOwner && adapter.caseStatus(attempt.caseId, actorId) == CASE_KILLED
        val killResult = when {
            alreadyKilled -> KillResult.ACKNOWLEDGED
            isOwner -> adapter.interrupt(attempt.caseId, interruptReason, actorId)
            else -> requireAuthorized(adapter.interrupt(attempt.caseId, interruptReason, actorId), attempt)
        }
        return conclude(scope, attempt, attempt, actorId, killResult, idempotent = true)
    }

    /**
     * Common tail of a Stop once the kill was sent: release the sequencer, read the proof, free the worktree when
     * the kill is proven, and record the `agent-stop` evidence.
     */
    private fun conclude(
        scope: TenantScope,
        cancelled: DurableAgentAttempt,
        attempt: DurableAgentAttempt,
        actorId: String,
        killResult: KillResult,
        idempotent: Boolean,
    ): CancellationOutcome {
        adapter.abandonObservation(attempt.caseId)
        val caseStatus = adapter.caseStatus(attempt.caseId, actorId)
        val killProven = killResult == KillResult.ACKNOWLEDGED && caseStatus == CASE_KILLED
        if (killProven) releaseOccupancy(scope, cancelled)
        recordStopEvidence(scope, cancelled, actorId, killResult, caseStatus, killProven)
        val reconciled = runCatching { adapter.reconcile(attempt.caseId) }.getOrNull()
        return CancellationOutcome(
            attemptId = cancelled.attemptId,
            workflowId = cancelled.workflowId,
            stepId = cancelled.stepId,
            status = cancelled.status,
            revision = cancelled.revision,
            idempotent = idempotent,
            reconciledVerdict = reconciled?.label(),
            killResult = killResult,
            killProven = killProven,
        )
    }

    /** Revision-fenced durable cancel; also accepts an attempt the sequencer already sealed as the user's Stop. */
    private fun durableCancel(scope: TenantScope, attempt: DurableAgentAttempt, expectedRevision: Int?): DurableAgentAttempt =
        attempts.requestCancel(
            scope,
            attempt.namespaceId,
            attempt.workflowId,
            attempt.stepId,
            attempt.attemptId,
            expectedRevision,
            CapabilityExecutionService.USER_CANCELLED,
        )

    /** The kill of a non-owner is their access check: a refusal or an unreachable AgentOS stops the Stop, unchanged. */
    private fun requireAuthorized(result: KillResult, attempt: DurableAgentAttempt): KillResult = when (result) {
        KillResult.ACKNOWLEDGED -> result
        KillResult.REFUSED -> throw WorkflowException(
            WorkflowErrorCodes.STOP_NOT_AUTHORIZED,
            "You are not allowed to stop this run.",
            statusCode = 403,
            details = mapOf("attemptId" to attempt.attemptId),
        )
        KillResult.UNREACHABLE -> throw WorkflowException(
            WorkflowErrorCodes.STOP_RUNTIME_UNAVAILABLE,
            "AgentOS is unreachable; the run was not stopped.",
            statusCode = 503,
            details = mapOf("attemptId" to attempt.attemptId),
        )
    }

    /** The authenticated actor persisted on the workflow instance, never a request-supplied value. */
    private fun runOwner(scope: TenantScope, namespaceId: String, workflowId: String): String? {
        val controllerRequest = workflowRepository?.findInstance(scope, namespaceId, workflowId)
            ?.instance?.get("controllerRequest") as? Map<*, *>
        return (controllerRequest?.get("actorId") as? String)?.takeIf { it.isNotBlank() }
    }

    /**
     * Releases the worktree occupancy the attempt holds, now that its kill is proven. A failure leaves it retained
     * (fail-closed) for the periodic sweep, and never fails the Stop.
     */
    private fun releaseOccupancy(scope: TenantScope, attempt: DurableAgentAttempt) {
        val service = worktreeOccupancyService ?: return
        val current = attempts.find(scope, attempt.namespaceId, attempt.workflowId, attempt.stepId, attempt.attemptId) ?: return
        val handle = WorktreeExecutionOccupancyHandle(
            environmentId = current.environmentRef ?: "",
            resourceIdentity = current.occupancyResourceIdentity ?: return,
            ownerScope = scope,
            ownerKind = current.occupancyOwnerKind ?: return,
            ownerId = current.occupancyOwnerId ?: return,
            generation = current.occupancyGeneration ?: return,
        )
        runCatching { service.releaseOwned(handle) }
            .onFailure { logger.warn { "Worktree occupancy of attempt '${attempt.attemptId}' retained: release failed (${it.javaClass.simpleName})" } }
    }

    /** One `agent-stop` fact per Stop; best effort, a failed write never fails the Stop. */
    private fun recordStopEvidence(
        scope: TenantScope,
        attempt: DurableAgentAttempt,
        actorId: String,
        killResult: KillResult,
        caseStatus: String?,
        killProven: Boolean,
    ) {
        val repository = evidenceRepository ?: return
        runCatching {
            repository.append(
                scope,
                attempt.namespaceId,
                attempt.workflowId,
                WorkflowEvidenceItem(
                    evidenceId = UUID.randomUUID().toString(),
                    namespaceId = attempt.namespaceId,
                    workflowId = attempt.workflowId,
                    stepId = attempt.stepId,
                    kind = AGENT_STOP_EVIDENCE_KIND,
                    outcome = if (killProven) "proven" else "unconfirmed",
                    source = mapOf("kind" to "factory-stop"),
                    facts = mapOf(
                        "attemptId" to attempt.attemptId,
                        "stepId" to attempt.stepId,
                        "actorId" to actorId,
                        "killResult" to killResult.name,
                        "caseStatus" to caseStatus,
                        "killProven" to killProven,
                    ).filterValues { it != null },
                    idempotencyKey = null,
                    createdAt = null,
                ),
            )
        }.onFailure { logger.warn { "agent-stop evidence of attempt '${attempt.attemptId}' could not be recorded (${it.javaClass.simpleName})" } }
    }

    private fun AgentOsExecutionVerdict.label(): String = when (this) {
        is AgentOsExecutionVerdict.Succeeded -> "Succeeded"
        is AgentOsExecutionVerdict.Failed -> "Failed"
        is AgentOsExecutionVerdict.Interrupted -> "Interrupted"
        is AgentOsExecutionVerdict.WaitingHuman -> "WaitingHuman"
        is AgentOsExecutionVerdict.Indeterminate -> "Indeterminate"
    }

    companion object {
        const val DEFAULT_REASON = "User requested cancellation"

        /** Evidence kind recording one Stop, step-scoped, with the proof status of its kill. */
        const val AGENT_STOP_EVIDENCE_KIND = "agent-stop"

        /** AgentOS status of a case killed by the runtime (the root case is killed after its descendants). */
        const val CASE_KILLED = "KILLED"
    }
}
