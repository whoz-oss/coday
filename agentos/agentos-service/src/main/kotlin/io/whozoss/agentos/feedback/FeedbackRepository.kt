package io.whozoss.agentos.feedback

import io.whozoss.agentos.entity.EntityRepository
import io.whozoss.agentos.sdk.feedback.Feedback
import java.util.UUID

/**
 * Repository for [Feedback] persistence.
 *
 * Parent type is [UUID] representing the [Feedback.caseId].
 */
interface FeedbackRepository : EntityRepository<Feedback, UUID> {
    /**
     * Find all non-removed feedback nodes targeting a specific case event.
     */
    fun findByCaseEventId(caseEventId: UUID): List<Feedback>

    /**
     * Upsert feedback for the given user + case event pair.
     *
     * If a non-removed feedback node already exists for [userId] on [entity.caseEventId],
     * it is updated in-place (preserving its original `id` and `created` timestamp).
     * Otherwise a new node is created.
     *
     * [userId] must be the caller's internal UUID string (from [FeedbackNode.createdBy]).
     * Passing `null` disables the duplicate-check and always creates a new node (test/system use).
     */
    fun upsert(entity: Feedback, userId: String?): Feedback
}
