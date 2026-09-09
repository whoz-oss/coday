package io.whozoss.agentos.feedback

import io.whozoss.agentos.entity.InMemoryEntityRepository
import io.whozoss.agentos.sdk.feedback.Feedback
import java.util.UUID

/** Test-only in-memory implementation of [FeedbackRepository]. */
class InMemoryFeedbackRepository : FeedbackRepository {
    private val delegate =
        InMemoryEntityRepository<Feedback, UUID>(
            parentIdExtractor = { it.caseId },
            comparator = compareBy { it.metadata.created },
        )

    override fun save(entity: Feedback): Feedback = delegate.save(entity)

    override fun findByIds(
        ids: Collection<UUID>,
        withRemoved: Boolean,
    ): List<Feedback> = delegate.findByIds(ids, withRemoved)

    override fun findByParent(parentId: UUID): List<Feedback> = delegate.findByParent(parentId)

    override fun delete(id: UUID): Boolean = delegate.delete(id)

    override fun deleteByParent(parentId: UUID): Int = delegate.deleteByParent(parentId)

    /**
     * Naive linear scan for test purposes.
     * Production uses a dedicated Neo4j index query.
     */
    override fun findByCaseEventId(caseEventId: UUID): List<Feedback> = delegate.findAll().filter { it.caseEventId == caseEventId }

    override fun upsert(
        entity: Feedback,
        userId: String,
    ): Feedback {
        val existing =
            userId.let { uid ->
                delegate.findAll().firstOrNull {
                    it.caseEventId == entity.caseEventId &&
                        it.metadata.createdBy == uid
                }
            }
        return when {
            existing != null -> {
                delegate.save(
                    existing.copy(
                        positive = entity.positive,
                        type = entity.type,
                        comment = entity.comment,
                    ),
                )
            }

            else -> {
                delegate.save(entity)
            }
        }
    }
}
