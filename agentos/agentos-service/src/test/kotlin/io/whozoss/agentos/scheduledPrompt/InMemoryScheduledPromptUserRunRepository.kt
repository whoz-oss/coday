package io.whozoss.agentos.scheduledPrompt

import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory implementation of [ScheduledPromptUserRunRepository] for unit tests.
 *
 * ### Graph-traversal materialize
 *
 * The Neo4j implementation traverses the deployment graph inside the database. The in-memory
 * variant cannot do that, so it delegates user resolution to [targetUserIdsProvider], a
 * lambda injected at construction time. In tests that exercise Phase A, pass a lambda that
 * returns the desired set of target userIds. Tests that only exercise Phase B or other
 * methods can use the default no-arg constructor (provider always returns empty set).
 *
 * ### Other methods
 *
 * [claimBatch] picks the oldest PENDING entries (up to `limit`), or RUNNING entries with
 * an expired lease. It is NOT atomic (single-threaded test use only).
 *
 * [markTerminal] uses a version counter to detect concurrent modifications, mirroring the
 * [ScheduledPromptUserRunNode.version] optimistic lock in the Neo4j implementation.
 */
class InMemoryScheduledPromptUserRunRepository(
    /**
     * Provides the set of target userIds for a given `(agentConfigId, namespaceId)` pair.
     * Defaults to returning an empty set (no-op materialisation).
     */
    private val targetUserIdsProvider: (agentConfigId: UUID, namespaceId: UUID) -> Set<UUID> = { _, _ -> emptySet() },
) : ScheduledPromptUserRunRepository {
    private val store = ConcurrentHashMap<UUID, ScheduledPromptUserRun>()

    /**
     * Graph-traversal variant: resolves target users via [targetUserIdsProvider], then
     * MERGEs a PENDING UserRun for each. Idempotent — existing records are left unchanged.
     *
     * Returns the number of UserRuns actually created (not counting pre-existing ones).
     */
    override fun materialize(runId: UUID, agentConfigId: UUID, namespaceId: UUID): Int {
        val userIds = targetUserIdsProvider(agentConfigId, namespaceId)
        var created = 0
        userIds.forEach { userId ->
            val existing = store.values.firstOrNull { it.runId == runId && it.userId == userId }
            if (existing == null) {
                val userRun = ScheduledPromptUserRun(
                    runId = runId,
                    userId = userId,
                    status = UserRunStatus.PENDING,
                )
                store[userRun.id] = userRun
                created++
            }
        }
        return created
    }

    override fun claimBatch(leaseDuration: Duration, limit: Int): List<ScheduledPromptUserRun> {
        val now = Instant.now()
        val claimable = store.values
            .filter { ur ->
                ur.status == UserRunStatus.PENDING ||
                    (ur.status == UserRunStatus.RUNNING && ur.leaseUntil != null && ur.leaseUntil < now)
            }
            .sortedBy { it.metadata.created }
            .take(limit)

        return claimable.map { ur ->
            val claimed = ur.copy(
                status = UserRunStatus.RUNNING,
                leaseUntil = now.plus(leaseDuration),
            )
            store[claimed.id] = claimed
            claimed
        }
    }

    override fun markTerminal(
        id: UUID,
        status: UserRunStatus,
        now: Instant,
        error: String?,
    ): ScheduledPromptUserRun {
        val current = store[id] ?: throw NoSuchElementException("ScheduledPromptUserRun not found: $id")
        val updated = current.copy(
            status = status,
            finishedAt = now,
            error = error,
            leaseUntil = null,
        )
        store[id] = updated
        return updated
    }

    override fun findByRunId(runId: UUID): List<ScheduledPromptUserRun> =
        store.values
            .filter { it.runId == runId }
            .sortedBy { it.metadata.created }

    override fun countByRunIdAndStatus(runId: UUID, vararg statuses: UserRunStatus): Int =
        store.values.count { ur ->
            ur.runId == runId && ur.status in statuses
        }

    override fun hasAnyActive(runId: UUID): Boolean =
        store.values.any { it.runId == runId && (it.status == UserRunStatus.PENDING || it.status == UserRunStatus.RUNNING) }

    override fun hasAnyFailed(runId: UUID): Boolean =
        store.values.any { it.runId == runId && it.status == UserRunStatus.FAILED }

    /** Test helper: all stored UserRuns. */
    fun all(): List<ScheduledPromptUserRun> = store.values.toList()

    /** Test helper: clear the store between tests. */
    fun clear() = store.clear()
}
