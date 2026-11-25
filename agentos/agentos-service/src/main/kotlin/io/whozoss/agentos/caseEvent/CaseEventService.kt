package io.whozoss.agentos.caseEvent

import io.whozoss.agentos.entity.EntityService
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import java.time.Instant
import java.util.*

/**
 * Service for managing CaseEvent entities.
 *
 * Extends EntityService with no additional methods.
 * Parent type is UUID representing the caseId.
 *
 * Implementation must ensure that findByParent returns events ordered by timestamp (oldest first).
 */
interface CaseEventService : EntityService<CaseEvent, UUID> {
    /**
     * Return the timestamp of the most recent message for each of the given [caseIds],
     * as a map of caseId → timestamp.
     *
     * Cases with no messages are absent from the result.
     */
    fun findLastMessageTimestamps(caseIds: Collection<UUID>): Map<UUID, Instant>

    /**
     * Soft-delete the event [eventId] of [caseId] and every event after it (rewind).
     *
     * @return the number of events removed, 0 when [eventId] is not an active event of [caseId]
     */
    fun deleteFrom(
        caseId: UUID,
        eventId: UUID,
    ): Int
}
