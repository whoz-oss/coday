package io.whozoss.agentos.caseEvent

import io.whozoss.agentos.entity.EntityRepository
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import java.time.Instant
import java.util.UUID

/**
 * Repository for CaseEvent persistence.
 *
 * Implementation must ensure that findByParent returns events ordered by timestamp (oldest first).
 *
 * Parent type is UUID representing the caseId.
 */
interface CaseEventRepository : EntityRepository<CaseEvent, UUID> {
    /**
     * Return the timestamp of the most recent [io.whozoss.agentos.sdk.caseEvent.MessageEvent]
     * for each of the given [caseIds], as a map of caseId → timestamp.
     *
     * Cases with no messages are absent from the result. The caller should fall back to the
     * case's own creation timestamp for such cases.
     */
    fun findLastMessageTimestamps(caseIds: Collection<UUID>): Map<UUID, Instant>

    /**
     * Soft-delete the active event [eventId] of [caseId] and every active event after it, in the order
     * of [findByParent] (timestamp, then id).
     *
     * @return the number of events removed, or 0 (nothing changed) when [eventId] is not an active
     *   event of [caseId]
     */
    fun deleteFrom(
        caseId: UUID,
        eventId: UUID,
    ): Int
}
