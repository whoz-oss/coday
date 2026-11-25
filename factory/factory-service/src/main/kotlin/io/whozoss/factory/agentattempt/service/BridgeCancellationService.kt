package io.whozoss.factory.agentattempt.service

import io.whozoss.factory.adapter.agentos.AgentOsExecutionAdapter
import io.whozoss.factory.adapter.agentos.AgentOsExecutionVerdict
import io.whozoss.factory.agentattempt.domain.AgentAttemptStatus
import io.whozoss.factory.agentattempt.domain.AttemptNotFoundException
import io.whozoss.factory.agentattempt.domain.InvalidAttemptTransitionException
import io.whozoss.factory.capability.CapabilityExecutionService
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.workflow.domain.WorkflowErrorCodes
import io.whozoss.factory.workflow.domain.WorkflowException
import io.whozoss.factory.workflow.persistence.WorkflowRepository
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
 *  0. the authorization: only the user who launched the run (the persisted
 *     `controllerRequest.actorId`) may stop it, checked before any write or remote call;
 *  1. a revision-fenced durable transition to `INTERRUPTED` tagged `USER_CANCELLED` (the lease
 *     owner token is rotated, fencing any in-flight worker out of finalization). It comes FIRST:
 *     a live sequencer that observes the kill must find itself fenced and never finalize the
 *     attempt as an ordinary `AGENT_INTERRUPTED` failure, and a stale revision or an attempt that
 *     already reached another terminal status never kills;
 *  2. `adapter.interrupt(caseId, reason, owner)` — the authenticated best-effort stop (the
 *     interruption intent is remembered so a terminal `KILLED` derives to `Interrupted`);
 *  3. a post-kill `adapter.reconcile(caseId)` snapshot.
 *
 * The kill is issued as the run owner, who created every case of the family: AgentOS authorizes
 * it (`Case` `DELETE`). The caller is checked against that same owner, so nobody else can borrow
 * the owner's rights through Factory.
 *
 * The worktree occupancy of the cancelled attempt is deliberately RETAINED (fail-closed): an
 * acknowledged kill does not prove that a tool already running in the worktree has stopped, so
 * releasing it here could let another writer claim a worktree still being written to.
 *
 * The remote calls run with NO active transaction; only the durable write is transactional.
 */
@Component
@ConditionalOnProperty(prefix = "factory.adapter.agentos", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class BridgeCancellationService(
    private val attempts: DurableAgentAttemptService,
    private val adapter: AgentOsExecutionAdapter,
    private val workflowRepository: WorkflowRepository? = null,
) {

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

        // (0) Only the run owner may stop it, before any write or remote call — also on a replay.
        val owner = requireRunOwner(scope, namespaceId, workflowId, actorId)

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
        // (2) remember the interruption intent and issue the authenticated best-effort kill, as the owner.
        val killed = adapter.interrupt(attempt.caseId, reason, owner)
        // (3) post-kill reconciliation snapshot.
        val reconciled = runCatching { adapter.reconcile(attempt.caseId) }.getOrNull()
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

    /**
     * The owner of the run is the authenticated actor persisted on the workflow instance, never a
     * request-supplied value. The caller must be exactly that owner: a missing owner (nothing proves
     * who launched the run) or another caller is refused with `403 ACTION_NOT_ALLOWED`.
     */
    private fun requireRunOwner(scope: TenantScope, namespaceId: String, workflowId: String, actorId: String): String {
        val instance = workflowRepository?.findInstance(scope, namespaceId, workflowId)
        val controllerRequest = instance?.instance?.get("controllerRequest") as? Map<*, *>
        val owner = (controllerRequest?.get("actorId") as? String)?.takeIf { it.isNotBlank() }
        if (owner == null || owner != actorId) {
            // Explicit 403: the shared code-to-status table maps ACTION_NOT_ALLOWED to 400, but a refused
            // identity is an authorization failure.
            throw WorkflowException(
                WorkflowErrorCodes.ACTION_NOT_ALLOWED,
                "Only the user who launched the run can stop it.",
                statusCode = 403,
            )
        }
        return owner
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
