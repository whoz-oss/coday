package io.whozoss.agentos.scheduledPrompt

import io.whozoss.agentos.sdk.entity.Entity
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.time.Instant
import java.util.UUID

/** Lifecycle status of a single [ScheduledPromptUserRun]. */
enum class UserRunStatus {
    /** Materialised by the scheduler; not yet processed. */
    PENDING,
    /** Case created and agent is executing; a lease is held to detect crashes. */
    RUNNING,
    /** Case reached a terminal status (IDLE or KILLED). */
    DONE,
    /** Execution failed (Case creation, permission grant, or Case ERROR). */
    FAILED,
}

/**
 * Tracks the execution of a single [ScheduledPromptRun] for a single target user.
 *
 * One [ScheduledPromptUserRun] is created per `(run, user)` pair during the materialization
 * phase. The [userRunKey] = `"$runId|$userId"` is UNIQUE in Neo4j — it acts as a distributed
 * lock preventing duplicate per-user launches.
 *
 * ### Lifecycle
 *
 * PENDING → RUNNING → DONE (happy path)
 *                   → FAILED (execution error)
 *
 * RUNNING entries whose [leaseUntil] has expired are re-claimed by the next tick
 * (crash-recovery without a separate ApplicationRunner).
 *
 * ### Immutability
 *
 * Like [ScheduledPromptRun], this is an append-only audit record. Only [status],
 * [error], [startedAt], [finishedAt], and [leaseUntil] are ever mutated after creation.
 */
data class ScheduledPromptUserRun(
    override val metadata: EntityMetadata = EntityMetadata(),
    /** Parent [ScheduledPromptRun]. */
    val runId: UUID,
    /** Target user. */
    val userId: UUID,
    val status: UserRunStatus,
    /** Error message if FAILED. */
    val error: String? = null,
    /** When the Case was created (transition to RUNNING). */
    val startedAt: Instant? = null,
    /** When the Case reached a terminal status (transition to DONE/FAILED). */
    val finishedAt: Instant? = null,
    /** Lease expiry for the RUNNING status; used to detect crashes and re-claim stale entries. */
    val leaseUntil: Instant? = null,
) : Entity
