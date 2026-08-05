package io.whozoss.agentos.scheduledPrompt

import mu.KLogging
import org.springframework.dao.DataIntegrityViolationException
import java.time.Instant
import java.util.UUID

/**
 * Neo4j-backed implementation of [ScheduledPromptRunRepository].
 *
 * [insert] wraps the SDN save in a constraint-violation detector: if Neo4j rejects the write
 * because [ScheduledPromptRunNode.slotKey] is already taken, the [DataIntegrityViolationException]
 * is caught and translated to a [DuplicateRunException] so callers can handle it gracefully.
 */
open class Neo4jScheduledPromptRunRepository(
    private val neo4jRepository: ScheduledPromptRunNodeNeo4jRepository,
) : ScheduledPromptRunRepository {

    override fun insert(run: ScheduledPromptRun): ScheduledPromptRun =
        try {
            neo4jRepository.save(ScheduledPromptRunNode.fromDomain(run)).toDomain()
        } catch (e: DataIntegrityViolationException) {
            if (!isSlotKeyConflict(e)) throw e
            logger.warn { "[Neo4jScheduledPromptRunRepository] Duplicate slot: sp=${run.scheduledPromptId} for=${run.scheduledFor}" }
            throw DuplicateRunException(run.scheduledPromptId, run.scheduledFor)
        }

    override fun hasActive(scheduledPromptId: UUID): Boolean =
        neo4jRepository.existsActiveByScheduledPromptId(scheduledPromptId.toString())

    override fun updateStatus(
        id: UUID,
        status: RunStatus,
        finishedAt: Instant?,
        error: String?,
    ): Boolean {
        val updated = neo4jRepository.updateStatus(
            id = id.toString(),
            status = status.name,
            finishedAt = finishedAt,
            error = error,
            now = Instant.now(),
        )
        return updated > 0
    }

    override fun findById(id: UUID): ScheduledPromptRun? =
        neo4jRepository.findById(id.toString()).orElse(null)?.toDomain()

    override fun countCompletedRuns(scheduledPromptId: UUID): Int =
        neo4jRepository.countCompletedRuns(scheduledPromptId.toString())

    override fun findOrphanedClaimed(olderThan: Instant): List<ScheduledPromptRun> =
        neo4jRepository.findByStatusAndCreatedBefore(RunStatus.CLAIMED.name, olderThan)
            .map { it.toDomain() }

    override fun findSettledRunning(): List<ScheduledPromptRun> =
        neo4jRepository.findSettledRunning().map { it.toDomain() }

    private fun isSlotKeyConflict(e: DataIntegrityViolationException): Boolean {
        val haystack = generateSequence<Throwable>(e) { it.cause }
            .mapNotNull { it.message }
            .joinToString(separator = " | ")
        return SLOT_KEY_CONSTRAINT_NAME in haystack || SLOT_KEY_PROPERTY in haystack
    }

    companion object : KLogging() {
        private const val SLOT_KEY_CONSTRAINT_NAME = "scheduled_prompt_run_slot_key_unique"
        private const val SLOT_KEY_PROPERTY = "slotKey"
    }
}
