package io.whozoss.factory.agentattempt.service

import io.whozoss.factory.adapter.agentos.AgentOsExecutionAdapter
import io.whozoss.factory.adapter.agentos.AgentOsExecutionVerdict
import io.whozoss.factory.agentattempt.domain.AgentAttemptStatus
import io.whozoss.factory.agentattempt.domain.AttemptNotFoundException
import io.whozoss.factory.agentattempt.domain.InvalidAttemptTransitionException
import io.whozoss.factory.persistence.TenantScope
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
)

/**
 * Explicit business cancellation of a durable agent attempt (Lot H, step 8).
 *
 * Cancellation is a control-plane command, never a side effect of the SSE
 * observation. Closing the SSE stream or a browser tab merely stops *observing*;
 * it does NOT cancel the run. Only an explicit `requestCancel` (through
 * `POST /api/factory/workflows/{workflowId}/attempts/{attemptId}/cancel`) drives:
 *
 *  1. `adapter.interrupt(caseId, "User requested cancellation")` — best-effort
 *     stop (the AgentOS contract exposes only the kill route; the interruption
 *     intent is remembered so a terminal `KILLED` derives to `Interrupted`);
 *  2. a post-kill `adapter.reconcile(caseId)` snapshot;
 *  3. a revision-fenced durable transition to `INTERRUPTED` (the lease owner
 *     token is rotated, fencing any in-flight worker out of finalization).
 *
 * The remote calls run with NO active transaction; only the final durable write
 * is transactional.
 */
@Component
@ConditionalOnProperty(prefix = "factory.adapter.agentos", name = ["enabled"], havingValue = "true")
class BridgeCancellationService(
    private val attempts: DurableAgentAttemptService,
    private val adapter: AgentOsExecutionAdapter,
) {

    fun requestCancel(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        attemptId: String,
        expectedRevision: Int?,
        reason: String = DEFAULT_REASON,
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

        // (1) remember the interruption intent and issue the best-effort kill.
        adapter.interrupt(attempt.caseId, reason)
        // (2) post-kill reconciliation snapshot.
        val reconciled = runCatching { adapter.reconcile(attempt.caseId) }.getOrNull()
        // (3) durable, revision-fenced terminal transition.
        val cancelled = attempts.requestCancel(
            scope,
            namespaceId,
            workflowId,
            attempt.stepId,
            attemptId,
            expectedRevision,
            "USER_CANCELLED",
        )
        return CancellationOutcome(
            attemptId = cancelled.attemptId,
            workflowId = cancelled.workflowId,
            stepId = cancelled.stepId,
            status = cancelled.status,
            revision = cancelled.revision,
            idempotent = false,
            reconciledVerdict = reconciled?.label(),
        )
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
