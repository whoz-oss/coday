package io.whozoss.factory.agentattempt.domain

import java.time.Instant

/**
 * One entry of the append-only transition journal of a durable agent execution
 * attempt (Lot C durable-execution, Phase 2 durable unit).
 *
 * The [io.whozoss.factory.agentattempt.persistence.DurableAgentAttemptNode]
 * root is a mutable snapshot of the CURRENT state; this journal is the durable,
 * ordered history of how the attempt got there. Every successful state change
 * of the aggregate (registration, claim, intermediate transition, finalization,
 * cancellation) appends exactly one entry, atomically with the compare-and-set:
 * a fenced or conflicted mutation that matched nothing appends nothing.
 *
 * - [sequence] is monotone per attempt (`MAX(sequence) + 1`), mirroring the
 *   delivery journal's `recordSequence` pattern.
 * - [fromStatus] is `null` only for the initial registration entry.
 * - [revisionAfter] is the optimistic-locking revision of the attempt node
 *   right after the recorded transition landed; it is strictly increasing
 *   along the journal.
 */
data class DurableAgentAttemptJournalEntry(
    val attemptId: String,
    val sequence: Long,
    val fromStatus: AgentAttemptStatus?,
    val toStatus: AgentAttemptStatus,
    val ownerToken: String? = null,
    val failureCode: String? = null,
    val resultEvidenceId: String? = null,
    val lastObservedEventId: String? = null,
    val revisionAfter: Int,
    val recordedAt: Instant,
)
