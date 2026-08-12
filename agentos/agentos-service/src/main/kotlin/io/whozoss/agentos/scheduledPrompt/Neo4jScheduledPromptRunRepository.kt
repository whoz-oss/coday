package io.whozoss.agentos.scheduledPrompt

import mu.KLogging
import org.springframework.dao.DataIntegrityViolationException
import java.time.Instant
import java.util.UUID

/**
 * Neo4j-backed implementation of [ScheduledPromptRunRepository].
 *
 * [insert] catches [DataIntegrityViolationException] and translates it to [DuplicateRunException].
 * The only UNIQUE constraint on [ScheduledPromptRunNode] is the composite
 * `(scheduledPromptId, scheduledFor)`, so any integrity violation from insert is a duplicate slot.
 */
open class Neo4jScheduledPromptRunRepository(
    private val neo4jRepository: ScheduledPromptRunNodeNeo4jRepository,
) : ScheduledPromptRunRepository {

    override fun insert(run: ScheduledPromptRun): ScheduledPromptRun =
        try {
            neo4jRepository.save(ScheduledPromptRunNode.fromDomain(run)).toDomain()
        } catch (e: DataIntegrityViolationException) {
            // The only UNIQUE constraint on ScheduledPromptRun is (scheduledPromptId, scheduledFor).
            // Any DataIntegrityViolationException from insert is a duplicate slot.
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

    override fun countCompletedRuns(scheduledPromptId: UUID, startInstant: Instant): Int =
        neo4jRepository.countCompletedRuns(scheduledPromptId.toString(), startInstant)

    override fun findOrphanedClaimed(olderThan: Instant): List<ScheduledPromptRun> =
        neo4jRepository.findByStatusAndCreatedBefore(RunStatus.CLAIMED.name, olderThan)
            .map { it.toDomain() }

    override fun findSettledRunning(): List<ScheduledPromptRun> =
        neo4jRepository.findSettledRunning().map { it.toDomain() }

    companion object : KLogging()
}
