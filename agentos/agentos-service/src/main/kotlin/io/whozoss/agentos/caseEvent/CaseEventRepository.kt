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
     * Return the agent name of the FIRST (oldest) [io.whozoss.agentos.sdk.caseEvent.AgentSelectedEvent]
     * for each of the given [caseIds], as a map of caseId → agentName. Later selections are ignored.
     *
     * Cases with no agent selection are absent from the result. Removed events are not considered.
     */
    fun findOriginAgentNames(caseIds: Collection<UUID>): Map<UUID, String>
}
