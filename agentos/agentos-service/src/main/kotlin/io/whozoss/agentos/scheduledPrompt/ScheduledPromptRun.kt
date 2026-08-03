package io.whozoss.agentos.scheduledPrompt

import io.whozoss.agentos.sdk.entity.Entity
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.time.Instant
import java.util.UUID

/** Execution status of a single [ScheduledPromptRun]. */
enum class RunStatus {
    /** The run has been claimed by the scheduler; execution is imminent. */
    CLAIMED,
    /** The run was skipped (misfire or overlap). */
    SKIPPED,
    /** The agent is currently executing the run. */
    RUNNING,
    /** The run completed successfully. */
    DONE,
    /** The run failed; see [ScheduledPromptRun.error] for details. */
    FAILED,
}

/**
 * Represents a single execution attempt of a [ScheduledPrompt].
 *
 * One [ScheduledPromptRun] is created per scheduler tick that processes a given prompt.
 * The [slotKey] = `"$scheduledPromptId|$scheduledFor"` is UNIQUE in Neo4j — it acts as an
 * optimistic distributed lock preventing duplicate firings for the same slot.
 *
 * ### Lifecycle
 *
 * CLAIMED → RUNNING → DONE (happy path)
 *               → FAILED (execution error)
 * SKIPPED (misfire or overlap, decided before claiming)
 *
 * [attempt] starts at 0 for the first try; future retry logic may increment it.
 * [finishedAt] and [error] are populated when the run reaches a terminal state.
 * [correlationId] is a short human-readable tag for log correlation.
 */
data class ScheduledPromptRun(
    override val metadata: EntityMetadata = EntityMetadata(),
    val scheduledPromptId: UUID,
    /** The UTC instant for which this slot was scheduled. */
    val scheduledFor: Instant,
    val status: RunStatus,
    /** Short correlation tag for log tracing (e.g. "sp-<uuid-prefix>-<epoch-second>"). */
    val correlationId: String,
    val attempt: Int = 0,
    val finishedAt: Instant? = null,
    val error: String? = null,
) : Entity
