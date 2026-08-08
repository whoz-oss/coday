package io.whozoss.agentos.scheduledPrompt

import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Repository for [ScheduledPromptUserRun] persistence.
 *
 * Intentionally does not extend [io.whozoss.agentos.sdk.entity.EntityRepository] because
 * UserRuns are append-only audit records, not editable entities — same as
 * [ScheduledPromptRunRepository].
 *
 * ### Concurrency contract
 *
 * [materialize] resolves target users via a single Cypher INSERT-SELECT that traverses the
 * deployment graph and MERGEs PENDING UserRuns atomically. Safe to replay on crash — MERGE
 * is idempotent on the composite UNIQUE `(runId, userId)` constraint.
 *
 * [claimBatch] reads candidates via a read-only query then saves each via SDN with the
 * [ScheduledPromptUserRunNode.version] optimistic lock, so concurrent instances cannot
 * double-claim the same UserRun.
 *
 * [markTerminal] uses the [ScheduledPromptUserRunNode.version] optimistic lock; callers must
 * handle [org.springframework.dao.OptimisticLockingFailureException] when two instances
 * attempt to close the same UserRun concurrently.
 *
 * ### Delivery guarantee: at-least-once
 *
 * If an instance crashes after creating a Case but before [markTerminal], the UserRun's
 * lease expires and a subsequent [claimBatch] reclaims it — potentially creating a
 * duplicate Case for the same user. This is acceptable for the scheduled-prompt use case
 * (duplicate conversation, no data corruption). Exactly-once would require an idempotence
 * key on Case creation (e.g. a UNIQUE constraint on `runId|userId` carried by the Case).
 */
interface ScheduledPromptUserRunRepository {

    /**
     * Traverses the deployment graph and materialises ALL PENDING UserRuns in one query.
     *
     * Resolves all non-removed Users that are deployment targets of [agentConfigId] in
     * [namespaceId] via UserGroup deployment only:
     * `AgentConfig -[:DEPLOYED_TO]-> UserGroup <-[:MEMBER|ADMIN]- User`
     *
     * Namespace-level membership is intentionally excluded — only users belonging to a
     * UserGroup explicitly deployed to the agent are targeted.
     *
     * Super-admins are excluded unless they are also members of a deployed UserGroup.
     *
     * Then MERGEs a PENDING [ScheduledPromptUserRun] for each distinct user.
     *
     * Safe to replay on crash — MERGE is idempotent on the composite UNIQUE `(runId, userId)` constraint.
     *
     * Returns the number of UserRuns created (0 when all already existed or no users found).
     */
    fun materialize(runId: UUID, agentConfigId: UUID, namespaceId: UUID): Int

    /**
     * Claim up to [limit] available UserRuns for execution.
     *
     * Selects the oldest PENDING entries, OR RUNNING entries whose [leaseUntil] has expired
     * (crash recovery), transitions each to RUNNING via SDN `save()` with the `@Version`
     * optimistic lock. Concurrent instances racing to claim the same UserRun will each get
     * an [org.springframework.dao.OptimisticLockingFailureException] on the save; those are
     * silently skipped so only one winner claims each entry.
     *
     * Returns an empty list when no claimable UserRuns exist.
     */
    fun claimBatch(leaseDuration: Duration, limit: Int): List<ScheduledPromptUserRun>

    /**
     * Transition a UserRun to a terminal status (DONE, TIMEOUT, or FAILED).
     *
     * Populates [ScheduledPromptUserRun.finishedAt] = [now] and [ScheduledPromptUserRun.error]
     * when [status] is FAILED. For [UserRunStatus.TIMEOUT], [error] is null — the Case is
     * still running independently; the UserRun is closed only for audit visibility.
     *
     * @throws org.springframework.dao.OptimisticLockingFailureException if the node was
     *   concurrently modified by another instance (treat as idempotent: it is already closed).
     */
    fun markTerminal(
        id: UUID,
        status: UserRunStatus,
        now: Instant,
        error: String? = null,
    ): ScheduledPromptUserRun

    /** All UserRuns belonging to the given Run, in creation order. */
    fun findByRunId(runId: UUID): List<ScheduledPromptUserRun>

    /**
     * Count of UserRuns for [runId] whose status is one of [statuses].
     * Used to determine whether the parent Run is complete.
     */
    fun countByRunIdAndStatus(runId: UUID, vararg statuses: UserRunStatus): Int

    /**
     * Returns true if at least one UserRun for [runId] is still active (PENDING or RUNNING).
     * [UserRunStatus.TIMEOUT] is NOT active — the UserRun is terminal even though the Case
     * continues running independently.
     * Used as a fast-path exit in completion checks — avoids counting when work is still in flight.
     */
    fun hasAnyActive(runId: UUID): Boolean

    /**
     * Returns true if at least one UserRun for [runId] is in FAILED status.
     * [UserRunStatus.TIMEOUT] is NOT a failure — it indicates monitoring was released, not that
     * execution failed. A Run with all UserRuns in DONE/TIMEOUT/FAILED transitions to DONE
     * unless at least one is FAILED.
     */
    fun hasAnyFailed(runId: UUID): Boolean
}
