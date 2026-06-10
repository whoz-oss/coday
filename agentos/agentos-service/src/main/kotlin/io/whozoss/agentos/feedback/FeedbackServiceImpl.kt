package io.whozoss.agentos.feedback

import io.whozoss.agentos.sdk.feedback.Feedback
import io.whozoss.agentos.user.UserService
import mu.KLogging
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Service implementation for [Feedback] entities.
 *
 * Delegates all persistence to [FeedbackRepository].
 */
@Service
class FeedbackServiceImpl(
    private val repository: FeedbackRepository,
    private val userService: UserService,
) : FeedbackService {
    override fun create(entity: Feedback): Feedback = repository.save(entity)

    override fun update(entity: Feedback): Feedback = repository.save(entity)

    override fun findByIds(
        ids: Collection<UUID>,
        withRemoved: Boolean,
    ): List<Feedback> = repository.findByIds(ids, withRemoved)

    override fun findByParent(parentId: UUID): List<Feedback> = repository.findByParent(parentId)

    override fun findByCaseEventId(caseEventId: UUID): List<Feedback> = repository.findByCaseEventId(caseEventId)

    override fun upsert(entity: Feedback): Feedback {
        val userId = userService.getCurrentUser().id.toString()
        val entityWithoutReasonIfPositive =
            entity.takeIf { !entity.positive } ?: entity.copy(comment = null, type = null)
        return repository.upsert(entityWithoutReasonIfPositive, userId)
    }

    override fun delete(id: UUID): Boolean = repository.delete(id)

    override fun deleteByParent(parentId: UUID): Int = repository.deleteByParent(parentId)

    companion object : KLogging()
}
