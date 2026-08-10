package io.whozoss.agentos.scheduledPrompt

import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory implementation of [ScheduledPromptRunRepository] for unit tests.
 *
 * Detects duplicates on the composite `(scheduledPromptId, scheduledFor)` key and throws
 * [DuplicateRunException] exactly as the Neo4j implementation does.
 *
 * [findSettledRunning] requires access to the UserRun store to replicate the Cypher
 * EXISTS sub-query logic. Wire this up by setting [userRunRepository] before calling
 * [findSettledRunning]; when null (default), the method returns an empty list.
 */
class InMemoryScheduledPromptRunRepository : ScheduledPromptRunRepository {
    private val store = ConcurrentHashMap<String, ScheduledPromptRun>()

    /** Set by scanner factory methods to enable [findSettledRunning] in tests. */
    var userRunRepository: ScheduledPromptUserRunRepository? = null

    override fun insert(run: ScheduledPromptRun): ScheduledPromptRun {
        val key = "${run.scheduledPromptId}|${run.scheduledFor.toEpochMilli()}"
        if (store.putIfAbsent(key, run) != null) {
            throw DuplicateRunException(run.scheduledPromptId, run.scheduledFor)
        }
        return run
    }

    override fun hasActive(scheduledPromptId: UUID): Boolean =
        store.values.any {
            it.scheduledPromptId == scheduledPromptId &&
                (it.status == RunStatus.CLAIMED || it.status == RunStatus.RUNNING)
        }

    override fun updateStatus(
        id: UUID,
        status: RunStatus,
        finishedAt: Instant?,
        error: String?,
    ): Boolean {
        val entry = store.entries.firstOrNull { it.value.id == id } ?: return false
        store[entry.key] = entry.value.copy(status = status, finishedAt = finishedAt, error = error)
        return true
    }

    override fun findById(id: UUID): ScheduledPromptRun? =
        store.values.firstOrNull { it.id == id }

    override fun countCompletedRuns(scheduledPromptId: UUID, startInstant: Instant): Int =
        store.values.count {
            it.scheduledPromptId == scheduledPromptId &&
                !it.scheduledFor.isBefore(startInstant)
        }

    override fun findOrphanedClaimed(olderThan: Instant): List<ScheduledPromptRun> =
        store.values.filter {
            it.status == RunStatus.CLAIMED && it.metadata.created.isBefore(olderThan)
        }.sortedBy { it.metadata.created }

    override fun findSettledRunning(): List<ScheduledPromptRun> {
        val urRepo = userRunRepository ?: return emptyList()
        return store.values
            .filter { it.status == RunStatus.RUNNING }
            .filter { run ->
                val pending = urRepo.countByRunIdAndStatus(run.id, UserRunStatus.PENDING)
                val running = urRepo.countByRunIdAndStatus(run.id, UserRunStatus.RUNNING)
                pending == 0 && running == 0
            }
            .sortedBy { it.metadata.created }
    }

    /** Test helper: all stored runs. */
    fun all(): List<ScheduledPromptRun> = store.values.toList()

    /** Test helper: clear the store between tests. */
    fun clear() = store.clear()
}
