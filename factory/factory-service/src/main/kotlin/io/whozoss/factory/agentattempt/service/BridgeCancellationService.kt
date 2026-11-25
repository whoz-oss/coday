package io.whozoss.factory.agentattempt.service

import io.whozoss.factory.adapter.agentos.AgentOsExecutionAdapter
import io.whozoss.factory.adapter.agentos.AgentOsExecutionVerdict
import io.whozoss.factory.agentattempt.domain.AgentAttemptStatus
import io.whozoss.factory.agentattempt.domain.AttemptNotFoundException
import io.whozoss.factory.agentattempt.domain.InvalidAttemptTransitionException
import io.whozoss.factory.capability.CapabilityExecutionService
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.worktree.WorktreeExecutionOccupancyHandle
import io.whozoss.factory.worktree.WorktreeExecutionOccupancyService
import io.whozoss.factory.workflow.persistence.WorkflowRepository
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
    /** True when the attempt was already `interrupted` and the request was a replay. */
    val idempotent: Boolean,
    /** The verdict derived from the post-kill REST reconciliation, when available. */
    val reconciledVerdict: String?,
    /** True when AgentOS acknowledged the authenticated kill (2xx); false on replay or refusal. */
    val killAcknowledged: Boolean = false,
)

/**
 * Explicit business cancellation of a durable agent attempt (Lot H, step 8).
 *
 * Cancellation is a control-plane command, never a side effect of the SSE
 * observation. Closing the SSE stream or a browser tab merely stops *observing*;
 * it does NOT cancel the run. Only an explicit `requestCancel` (through
 * `POST /api/factory/workflows/{workflowId}/attempts/{attemptId}/cancel`) drives:
 *
 *  1. a revision-fenced durable transition to `INTERRUPTED` tagged `USER_CANCELLED` (the lease
 *     owner token is rotated, fencing any in-flight worker out of finalization). It comes FIRST:
 *     a live sequencer that observes the kill must find itself fenced and never finalize the
 *     attempt as an ordinary `AGENT_INTERRUPTED` failure, and a stale revision never kills;
 *  2. `adapter.interrupt(caseId, reason, identity)` — best-effort stop (the AgentOS contract
 *     exposes only the kill route; the interruption intent is remembered so a terminal
 *     `KILLED` derives to `Interrupted`);
 *  3. a post-kill `adapter.reconcile(caseId)` snapshot;
 *  4. when AgentOS acknowledged the kill, the release of the worktree occupancy
 *     held by the attempt (fail-closed: an unacknowledged kill keeps it).
 *
 * The kill is issued with the identity of the persisted workflow actor, which
 * created every case of the family: AgentOS authorizes it (`Case` `DELETE`).
 *
 * The remote calls run with NO active transaction; only the final durable write
 * is transactional.
 */
@Component
@ConditionalOnProperty(prefix = "factory.adapter.agentos", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class BridgeCancellationService(
    private val attempts: DurableAgentAttemptService,
    private val adapter: AgentOsExecutionAdapter,
    private val workflowRepository: WorkflowRepository? = null,
    private val worktreeOccupancyService: WorktreeExecutionOccupancyService? = null,
) {

    private val logger = KotlinLogging.logger {}

    fun requestCancel(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        attemptId: String,
        expectedRevision: Int?,
        reason: String = DEFAULT_REASON,
        actorId: String? = null,
    ): CancellationOutcome {
        val attempt = attempts.findByAttemptId(scope, namespaceId, workflowId, attemptId)
            ?: throw AttemptNotFoundException(
                "Durable attempt '$attemptId' not found",
                details = mapOf("namespaceId" to namespaceId, "workflowId" to workflowId, "attemptId" to attemptId),
            )

        if (attempt.status == AgentAttemptStatus.INTERRUPTED) {
            return CancellationOutcome(attempt.attemptId, workflowId, attempt.stepId, attempt.status, attempt.revision, idempotent = true, reconciledVerdict = null)
        }
        if (attempt.status.terminal) {
            throw InvalidAttemptTransitionException(
                "Attempt '$attemptId' is already terminal as '${attempt.status.dbValue}' and cannot be cancelled",
                details = mapOf("attemptId" to attemptId, "status" to attempt.status.dbValue),
            )
        }

        // (1) durable, revision-fenced terminal transition: rotates the lease owner, so a live
        // sequencer is fenced out of finalization before the kill can make it observe a verdict.
        val cancelled = attempts.requestCancel(
            scope,
            namespaceId,
            workflowId,
            attempt.stepId,
            attemptId,
            expectedRevision,
            CapabilityExecutionService.USER_CANCELLED,
        )
        // (2) remember the interruption intent and issue the authenticated best-effort kill.
        // The persisted workflow actor created the case family and holds the right to kill it.
        val identity = persistedActorId(scope, namespaceId, workflowId) ?: actorId?.takeIf { it.isNotBlank() }
        val killed = adapter.interrupt(attempt.caseId, reason, identity)
        // (3) post-kill reconciliation snapshot.
        val reconciled = runCatching { adapter.reconcile(attempt.caseId) }.getOrNull()
        // (4) the runtime acknowledged the kill: the attempt no longer writes in its worktree.
        if (killed) releaseOccupancy(scope, namespaceId, workflowId, attempt.stepId, attemptId)
        return CancellationOutcome(
            attemptId = cancelled.attemptId,
            workflowId = cancelled.workflowId,
            stepId = cancelled.stepId,
            status = cancelled.status,
            revision = cancelled.revision,
            idempotent = false,
            reconciledVerdict = reconciled?.label(),
            killAcknowledged = killed,
        )
    }

    /** The authenticated actor persisted on the workflow instance, never a request-supplied value. */
    private fun persistedActorId(scope: TenantScope, namespaceId: String, workflowId: String): String? {
        val instance = workflowRepository?.findInstance(scope, namespaceId, workflowId) ?: return null
        val controllerRequest = instance.instance["controllerRequest"] as? Map<*, *>
        return (controllerRequest?.get("actorId") as? String)?.takeIf { it.isNotBlank() }
    }

    /**
     * Releases the exact worktree acquisition captured by the attempt. Never fails the committed
     * cancellation: a failure is logged and leaves the occupancy retained (fail-closed).
     */
    private fun releaseOccupancy(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        stepId: String,
        attemptId: String,
    ) {
        val service = worktreeOccupancyService ?: return
        runCatching {
            val attempt = attempts.find(scope, namespaceId, workflowId, stepId, attemptId) ?: return
            val resourceIdentity = attempt.occupancyResourceIdentity ?: return
            val ownerKind = attempt.occupancyOwnerKind ?: return
            val ownerId = attempt.occupancyOwnerId ?: return
            val generation = attempt.occupancyGeneration ?: return
            service.releaseOwned(
                WorktreeExecutionOccupancyHandle(
                    environmentId = attempt.environmentRef ?: "",
                    resourceIdentity = resourceIdentity,
                    ownerScope = scope,
                    ownerKind = ownerKind,
                    ownerId = ownerId,
                    generation = generation,
                ),
            )
        }.onFailure { error ->
            logger.warn(error) { "Worktree occupation of attempt '$attemptId' retained: release after cancellation failed" }
        }
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
    }
}
