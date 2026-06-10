package io.whozoss.agentos.feedback

import io.whozoss.agentos.entity.EntityService
import io.whozoss.agentos.sdk.feedback.Feedback
import java.util.UUID

/**
 * Service for managing [Feedback] entities.
 *
 * Extends [EntityService] with [findByCaseEventId] for targeted event lookups.
 * Parent type is [UUID] representing the [Feedback.caseId].
 */
interface FeedbackService : EntityService<Feedback, UUID> {
    /**
     * Find all non-removed feedback entries targeting a specific case event.
     */
    fun findByCaseEventId(caseEventId: UUID): List<Feedback>

    /**
     * Upsert feedback for the authenticated caller.
     *
     * Delegates to [FeedbackRepository.upsert] with the current user's id so that
     * repeated submissions from the same user on the same event update the existing
     * record rather than creating duplicates.
     */
    fun upsert(entity: Feedback): Feedback
}
